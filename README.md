# ansiblehook

HTTP-Webhook-Service zum Auslösen von Ansible-Playbooks über eine gesicherte REST-API.

Ein typischer Anwendungsfall ist der kontrollierte Deploy-Zugriff für Entwickler: Statt ihnen direkten SSH-Zugang zum Deployment-Server oder Zugriff auf Ansible-Credentials zu geben, stellt ein Operator vorab einen zeitlich begrenzten One-Time-Token aus. Der Entwickler kann damit genau ein Playbook einmalig auslösen — ohne Kenntnisse über Inventories, Vault-Passwörter oder Serverstruktur. Nach der Nutzung ist der Token verbraucht. Optional lässt sich mit `not_before` ein Startzeitpunkt festlegen, sodass ein Token bereits im Voraus ausgestellt, aber erst zum geplanten Deployment-Fenster einlösbar ist.

Für automatisierte Pipelines (CI/CD, Monitoring) steht alternativ eine dauerhafte HMAC-SHA256-Signatur zur Verfügung. Läuft ein Playbook bereits, wird ein zweiter Request mit `409 Conflict` abgelehnt. Die vollständigen Ansible-Logs werden als Response-Body zurückgegeben.

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

Der resultierende Befehl:

```
ansible-playbook -i hosts playbooks/site.yml --limit myhost --tags all --extra-vars "env=prod"
```

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
|-----------|---------|--------------|
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
|--------|-----------|
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `400 Bad Request` | Ungültiger Parameter (z. B. falsches Datumsformat bei `not_before`) |
| `401 Unauthorized` | Fehlende, ungültige oder abgelaufene Authentifizierung |
| `404 Not Found` | Unbekannte Webhook-ID |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen — Body enthält die Logs |
