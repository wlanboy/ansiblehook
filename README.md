# ansiblehook

HTTP-Webhook-Service zum Auslösen von Ansible-Playbooks über eine gesicherte REST-API.

Ein typischer Anwendungsfall ist der kontrollierte Deploy-Zugriff für Entwickler: Statt ihnen direkten SSH-Zugang zum Deployment-Server oder Zugriff auf Ansible-Credentials zu geben, stellt ein Operator vorab einen zeitlich begrenzten One-Time-Token aus.
Der Entwickler kann damit genau ein Playbook einmalig auslösen — ohne Kenntnisse über Inventories, Vault-Passwörter oder Serverstruktur. Nach der Nutzung ist der Token verbraucht.
Optional lässt sich mit `not_before` ein Startzeitpunkt festlegen, sodass ein Token bereits im Voraus ausgestellt, aber erst zum geplanten Deployment-Fenster einlösbar ist.

Für automatisierte Pipelines (CI/CD, Monitoring) steht alternativ eine dauerhafte HMAC-SHA256-Signatur zur Verfügung. Läuft ein Playbook bereits, wird ein zweiter Request mit `409 Conflict` abgelehnt. Die vollständigen Ansible-Logs werden als Response-Body zurückgegeben.

## HMAC-SHA256

HMAC-SHA256 (Hash-based Message Authentication Code) schützt die Webhook-Endpunkte, ohne das gemeinsame Secret jemals über das Netzwerk zu übertragen. Anstatt das Secret direkt als Passwort mitzuschicken, berechnet der Aufrufer daraus eine Signatur über eine kontextspezifische Nachricht — der Server prüft die Signatur, ohne das Secret zu kennen (er berechnet sie selbst und vergleicht).

### Warum HMAC statt Bearer-Token?

Ein statisches Bearer-Token ist ein Shared Secret: Wer es einmal abfängt, kann beliebig viele Requests stellen. HMAC löst zwei Probleme gleichzeitig:

- **Replay-Schutz** — Die Signatur schließt den aktuellen Unix-Timestamp ein. Der Server akzeptiert nur Requests, deren Timestamp maximal 5 Minuten von der Serverzeit abweicht. Ein abgefangener Request ist nach dieser Zeitspanne wertlos.
- **Webhook-Bindung** — Die Signatur schließt die Webhook-ID ein. Eine gültige Signatur für `/webhook/deploy` kann nicht für `/webhook/rollback` wiederverwendet werden, selbst wenn beide dasselbe Secret teilen.
- **Vorab-Ausstellung** — Soll ein Deployment zu einem festen Zeitpunkt ausgelöst werden, kann ein HMAC-gesicherter Request einen One-Time-Token mit `not_before` erzeugen und diesen vorab an den Ausführenden weitergeben. Der Token wird erst ab dem vereinbarten Zeitpunkt akzeptiert — das eigentliche Deployment benötigt dann weder Secret noch HMAC-Berechnung.
- **Pipeline-Eignung** — Automatisierte Systeme (CI/CD, Monitoring) können das Secret als verschlüsselte Umgebungsvariable hinterlegen und die Signatur bei jedem Request dynamisch berechnen. Es muss kein Token vorab ausgestellt oder rotiert werden — das Secret bleibt dauerhaft gültig, solange es nicht kompromittiert wird.

### Aufbau der Signatur

```text
Nachricht  = "<unix-timestamp>.<webhook-id>"
Signatur   = HMAC-SHA256(key=secret, message=Nachricht)
Header     = "sha256=<hex-digest>"
```

Beispiel für Webhook-ID `mein-webhook` zum Zeitpunkt `1745660000`:

```text
Nachricht:  "1745660000.mein-webhook"
Signatur:   sha256=4a7f...
```

Der Server berechnet dieselbe Signatur mit dem konfigurierten Secret und vergleicht sie zeitsicher (constant-time). Stimmen Signatur und Zeitfenster überein, wird der Request verarbeitet.

## Konfiguration

Webhooks werden in `src/main/resources/application.yml` definiert:

```yaml
ansiblehook:
  webhooks:
    mein-webhook:               # URL-Pfad: POST /webhook/mein-webhook
      secret: <geheimes-token>  # Pflichtfeld: HMAC-Key
      folder: ~/git/repo        # Arbeitsverzeichnis für ansible-playbook
      hosts: hosts              # Inventory-Datei (-i)
      playbook: playbooks/site.yml
      limit: myhost             # optional: --limit
      tags: all                 # optional: --tags
      extra-vars: "env=prod"    # optional: --extra-vars
      vault-password-file: ~/.vault_pass  # optional: --vault-password-file
```

Der Map-Key (`mein-webhook`) ist gleichzeitig die URL-ID. Das `secret` ist der HMAC-Key — es wird nie direkt übertragen.

Ein sicheres Secret lässt sich einmalig erzeugen mit:

```bash
openssl rand -hex 32
```

Der resultierende Befehl:

```bash
ansible-playbook -i hosts playbooks/site.yml --limit myhost --tags all --extra-vars "env=prod"
```

## Voraussetzungen

| Komponente | Version | Zweck |
| --- | --- | --- |
| Java | 25+ | Laufzeit |
| Maven | 3.9+ | Build |
| Ansible | beliebig | Playbook-Ausführung |

Ansible muss auf demselben Server installiert und im `PATH` des Prozesses erreichbar sein, unter dem der Service läuft.

## Start

```bash
mvn spring-boot:run
```

Der Service startet auf Port `8080`.

## Authentifizierung

Jeder Endpoint unterstützt zwei Varianten:

### Variante 1 — HMAC-Signatur

Jeder Request muss einen Timestamp und eine HMAC-SHA256-Signatur des Timestamps mitschicken.

Signatur berechnen (Bash):

```bash
SECRET="7f3d9a12-b4c8-4e1f-9d6a-123456789abc"
WEBHOOK_ID="mein-webhook"
TS=$(date +%s)

SIG="sha256=$(echo -n "${TS}.${WEBHOOK_ID}" \
  | openssl dgst -sha256 -hmac "$SECRET" \
  | awk '{print $2}')"
```

Die HMAC-Nachricht ist `timestamp.webhook-id` — damit ist eine Signatur an genau diesen Webhook gebunden und kann nicht für einen anderen verwendet werden. Der Timestamp darf maximal 5 Minuten von der Serverzeit abweichen (Replay-Schutz).

### Variante 2 — One-Time-Token

Ein Token wird vorab per HMAC-gesichertem Request erzeugt und kann danach **einmalig** ohne HMAC-Signatur verwendet werden.

## Webhooks auslösen

### Mit HMAC-Signatur

```bash
curl -X POST http://localhost:8080/webhook/mein-webhook \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

### Mit One-Time-Token

```bash
curl -X POST http://localhost:8080/webhook/mein-webhook \
  -H "X-Webhook-Token: <token>"
```

### Ausgabe in Datei

```bash
curl -X POST http://localhost:8080/webhook/mein-webhook \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG" \
  --output ansible.log
```

## GitHub Actions

Das Secret wird als Repository-Secret (`WEBHOOK_SECRET`) hinterlegt, die Signatur bei jedem Workflow-Lauf frisch berechnet:

```yaml
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Ansible Playbook
        env:
          SECRET: ${{ secrets.WEBHOOK_SECRET }}
          WEBHOOK_ID: mein-webhook
          HOST: https://deploy.example.com
        run: |
          TS=$(date +%s)
          SIG="sha256=$(echo -n "${TS}.${WEBHOOK_ID}" \
            | openssl dgst -sha256 -hmac "$SECRET" \
            | awk '{print $2}')"

          curl -sf -X POST "$HOST/webhook/$WEBHOOK_ID" \
            -H "X-Webhook-Timestamp: $TS" \
            -H "X-Webhook-Signature: $SIG"
```

`-sf` bricht den Workflow bei HTTP-Fehler ab (`-f`) und unterdrückt den Fortschrittsbalken (`-s`). Der Response-Body mit den Ansible-Logs wird auf stdout ausgegeben und ist im Actions-Log sichtbar.

## Status abfragen

```bash
# Mit HMAC
curl http://localhost:8080/webhook/mein-webhook/status \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"

# Mit One-Time-Token
curl http://localhost:8080/webhook/mein-webhook/status \
  -H "X-Webhook-Token: <token>"

# {"id":"mein-webhook","running":false}
```

## One-Time-Token erzeugen

```bash
curl -X POST "http://localhost:8080/webhook/mein-webhook/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

Parameter:

| Parameter | Pflicht | Beschreibung |
| --- | --- | --- |
| `ttl` | nein | Gültigkeitsdauer in Sekunden (Standard: `3600`, Maximum: `86400`) |
| `not_before` | nein | Frühester Nutzungszeitpunkt als ISO-8601-Timestamp (Standard: sofort) |

Antwort:

```json
{
  "token": "a3f7c2...",
  "webhook_id": "mein-webhook",
  "not_before": "2026-04-26T08:00:00Z",
  "expires_at": "2026-04-26T09:00:00Z"
}
```

### Token mit Startdatum

Nützlich, wenn ein Token vorab ausgestellt werden soll, aber erst zu einem späteren Zeitpunkt nutzbar sein darf — z. B. für ein geplantes Wartungsfenster oder ein nächtliches Deployment:

```bash
# Startzeitpunkt festlegen (hier: morgen 02:00 Uhr UTC)
NOT_BEFORE="2026-04-26T02:00:00Z"

curl -X POST "http://localhost:8080/webhook/mein-webhook/token?ttl=1800&not_before=$NOT_BEFORE" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

Antwort:

```json
{
  "token": "a3f7c2...",
  "webhook_id": "mein-webhook",
  "not_before": "2026-04-26T02:00:00Z",
  "expires_at": "2026-04-26T02:30:00Z"
}
```

Den Token kann der Operator jetzt an einen Entwickler weitergeben. Dieser löst das Deployment zum vereinbarten Zeitpunkt aus:

```bash
curl -X POST http://localhost:8080/webhook/mein-webhook \
  -H "X-Webhook-Token: a3f7c2..."
```

Das Token ist zwischen `02:00` und `02:30 Uhr` einmalig einlösbar. Vor `not_before` antwortet der Server mit `401` — das Token bleibt dabei erhalten und kann nach Ablauf der Wartezeit noch genutzt werden. Nach erfolgreicher Nutzung oder nach `expires_at` ist es dauerhaft ungültig.

## HTTP-Statuscodes

| Status | Bedeutung |
| --- | --- |
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `400 Bad Request` | Ungültiger Parameter (z. B. falsches Datumsformat bei `not_before`) |
| `401 Unauthorized` | Fehlende, ungültige oder abgelaufene Authentifizierung |
| `404 Not Found` | Unbekannte Webhook-ID |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen — Body enthält die Logs |
