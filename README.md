# ansiblehook

HTTP-Webhook-Service zum Auslösen von Ansible-Playbooks. Jeder Webhook ist per Secret-Header (`X-Webhook-Secret`) gesichert. Läuft ein Playbook bereits, wird ein zweiter Request mit `409 Conflict` abgelehnt. Die vollständigen Ansible-Logs werden als Response-Body zurückgegeben.

## Konfiguration

Webhooks werden in `src/main/resources/application.yml` definiert:

```yaml
ansiblehook:
  webhooks:
    mein-webhook:               # URL-Pfad: POST /webhook/mein-webhook
      secret: <geheimes-token>  # Pflichtfeld: Header X-Webhook-Secret
      folder: ~/git/repo        # Arbeitsverzeichnis für ansible-playbook
      hosts: hosts              # Inventory-Datei (-i)
      playbook: playbooks/site.yml
      limit: myhost             # optional: --limit
      tags: all                 # optional: --tags
```

Der Map-Key (`mein-webhook`) ist gleichzeitig die URL-ID. Das `secret` wird ausschließlich über den HTTP-Header übermittelt, nicht über die URL.

Der resultierende Befehl:

```
ansible-playbook -i hosts playbooks/site.yml --limit myhost --tags all
```

## Start

```bash
mvn spring-boot:run
```

Der Service startet auf Port `8080`.

## Webhooks aufrufen

Jeder Request muss den Header `X-Webhook-Secret` mit dem konfigurierten Secret mitschicken. Optional kann ein JSON-Payload im Body mitgegeben werden (wird geloggt).

### apt-update (mit Limit und Tags)

```bash
curl -X POST http://localhost:8080/webhook/apt-update \
  -H "X-Webhook-Secret: 550e8400-e29b-41d4-a716-446655440000"
```

### ping (ohne Tags)

```bash
curl -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Secret: 7f3d9a12-b4c8-4e1f-9d6a-123456789abc"
```

### Mit JSON-Payload (z.B. von GitHub/GitLab)

```bash
curl -X POST http://localhost:8080/webhook/apt-update \
  -H "X-Webhook-Secret: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{"ref": "refs/heads/main"}'
```

### Mit Ausgabe in Datei

```bash
curl -X POST http://localhost:8080/webhook/apt-update \
  -H "X-Webhook-Secret: 550e8400-e29b-41d4-a716-446655440000" \
  --output ansible.log
```

## Status abfragen

```bash
curl http://localhost:8080/webhook/apt-update/status \
  -H "X-Webhook-Secret: 550e8400-e29b-41d4-a716-446655440000"
# {"id":"apt-update","running":false}
```

Gibt `401` bei fehlendem/falschem Secret und `404` bei unbekannter Webhook-ID zurück.

## HTTP-Statuscodes

| Status | Bedeutung |
|--------|-----------|
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `401 Unauthorized` | Fehlender oder falscher `X-Webhook-Secret`-Header |
| `404 Not Found` | Unbekannte Webhook-ID |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen — Body enthält trotzdem die Logs |
