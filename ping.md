# Webhook: ping

Löst `playbooks/ping.yml` auf dem Inventory `hosts` mit `--limit gmk` aus.

```
ansible-playbook -i hosts playbooks/ping.yml --limit gmk
```

Konfiguration (`application.yml`):

```yaml
ansiblehook:
  webhooks:
    ping:
      secret: 7f3d9a12-b4c8-4e1f-9d6a-123456789abc
      folder: ~/git/ansiblehosts
      hosts: hosts
      playbook: playbooks/ping.yml
      limit: gmk
```

---

## Vorbereitung — Signatur berechnen

Alle HMAC-gesicherten Requests benötigen einen aktuellen Timestamp und eine Signatur. Der Timestamp darf maximal 5 Minuten von der Serverzeit abweichen.

```bash
SECRET="7f3d9a12-b4c8-4e1f-9d6a-123456789abc"
TS=$(date +%s)

SIG="sha256=$(echo -n "$TS" \
  | openssl dgst -sha256 -hmac "$SECRET" \
  | awk '{print $2}')"
```

---

## Szenarien

### 1. Playbook auslösen — HMAC-Signatur

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

Erfolg (`200 OK`):

```
PLAY [gmk] ****************************

TASK [Gathering Facts] ****************
ok: [gmk]

TASK [ping] ***************************
ok: [gmk]

PLAY RECAP ****************************
gmk : ok=2  changed=0  unreachable=0  failed=0
```

---

### 2. Playbook auslösen — One-Time-Token

Token vorher erzeugen (siehe Szenario 5), dann:

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Token: <token>"
```

---

### 3. Ansible-Log in Datei speichern

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG" \
  --output ping.log

cat ping.log
```

---

### 4. Status abfragen — läuft das Playbook gerade?

#### Mit HMAC-Signatur

```bash
curl -s http://localhost:8080/webhook/ping/status \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

#### Mit One-Time-Token

```bash
curl -s http://localhost:8080/webhook/ping/status \
  -H "X-Webhook-Token: <token>"
```

Antwort wenn idle:

```json
{"id":"ping","running":false}
```

Antwort wenn aktiv:

```json
{"id":"ping","running":true}
```

---

### 5. One-Time-Token erzeugen (Standard-TTL 1 Stunde)

```bash
curl -s -X POST "http://localhost:8080/webhook/ping/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

Antwort:

```json
{
  "token": "a3f7c2d8e1b04f9a...",
  "webhook_id": "ping",
  "not_before": "2026-04-25T10:00:00Z",
  "expires_at": "2026-04-25T11:00:00Z"
}
```

Den `token`-Wert an den Entwickler weitergeben — einmalig einlösbar.

---

### 6. Token mit `not_before` — für geplantes Ausführungsfenster

Nützlich wenn der Token bereits jetzt ausgestellt, aber erst später eingelöst werden soll.

```bash
NOT_BEFORE="2026-04-26T02:00:00Z"

curl -s -X POST "http://localhost:8080/webhook/ping/token?ttl=1800&not_before=$NOT_BEFORE" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
```

Antwort:

```json
{
  "token": "b9c3a1f0e2d74e8b...",
  "webhook_id": "ping",
  "not_before": "2026-04-26T02:00:00Z",
  "expires_at": "2026-04-26T02:30:00Z"
}
```

Das Token ist zwischen `02:00` und `02:30 Uhr UTC` einmalig einlösbar.

---

## Fehlerfälle

### Fehlende Authentifizierung (`401`)

Weder Token noch Timestamp+Signatur gesetzt:

```bash
curl -s -X POST http://localhost:8080/webhook/ping
# → 401 Unauthorized
# Provide either X-Webhook-Token or X-Webhook-Timestamp + X-Webhook-Signature
```

### Ungültige Signatur (`401`)

Falsches Secret oder manipulierter Timestamp:

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: sha256=000000deadbeef"
# → 401 Unauthorized
# Invalid signature
```

### Abgelaufener Timestamp (`401`)

Timestamp älter als 5 Minuten:

```bash
OLD_TS=1700000000
OLD_SIG="sha256=$(echo -n "$OLD_TS" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')"

curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $OLD_TS" \
  -H "X-Webhook-Signature: $OLD_SIG"
# → 401 Unauthorized
# Invalid timestamp
```

### Verbrauchter oder ungültiger Token (`401`)

Token wurde bereits eingelöst oder existiert nicht:

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Token: abcdef1234567890"
# → 401 Unauthorized
# Invalid or expired token
```

### Token zu früh eingelöst — `not_before` noch nicht erreicht (`401`)

Token bleibt dabei erhalten und kann ab `not_before` noch genutzt werden.

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Token: <token-mit-not_before-in-der-zukunft>"
# → 401 Unauthorized
```

### Unbekannte Webhook-ID (`404`)

```bash
curl -s -X POST http://localhost:8080/webhook/does-not-exist \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
# → 404 Not Found
```

### Playbook läuft bereits (`409`)

Ein zweiter Request während das Playbook noch aktiv ist:

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
# → 409 Conflict
# Playbook 'ping' is already running
```

### Playbook fehlgeschlagen (`500`)

Ansible-Ausführungsfehler (z. B. Host nicht erreichbar):

```bash
curl -s -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG"
# → 500 Internal Server Error
# Playbook failed
```

---

## HTTP-Statuscodes

| Status | Bedeutung |
|--------|-----------|
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `400 Bad Request` | Ungültiger Parameter (z. B. falsches Format bei `not_before`) |
| `401 Unauthorized` | Fehlende, ungültige, abgelaufene oder noch nicht aktive Authentifizierung |
| `404 Not Found` | Webhook-ID `ping` nicht in der Konfiguration gefunden |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen |
