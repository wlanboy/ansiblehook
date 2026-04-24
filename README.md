# ansiblehook

HTTP-Webhook-Service zum Auslösen von Ansible-Playbooks. Jeder Webhook ist per UUID gesichert und kann ein Playbook mit konfigurierbaren Hosts, Limit und Tags ausführen. Läuft ein Playbook bereits, wird ein zweiter Request mit `409 Conflict` abgelehnt. Die vollständigen Ansible-Logs werden als Response-Body zurückgegeben.

## Konfiguration

Webhooks werden in `src/main/resources/application.yml` definiert:

```yaml
ansiblehook:
  webhooks:
    mein-webhook:
      id: <uuid>          # URL-Pfad: POST /webhook/<uuid>
      folder: ~/git/repo  # Arbeitsverzeichnis für ansible-playbook
      hosts: hosts        # Inventory-Datei (-i)
      playbook: playbooks/site.yml
      limit: myhost       # optional: --limit
      tags: all           # optional: --tags
```

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

### apt-update (mit Limit und Tags)

```bash
curl -X POST http://localhost:8080/webhook/550e8400-e29b-41d4-a716-446655440000
```

### ping (ohne Tags)

```bash
curl -X POST http://localhost:8080/webhook/7f3d9a12-b4c8-4e1f-9d6a-123456789abc
```

### Mit Ausgabe in Datei

```bash
curl -X POST http://localhost:8080/webhook/550e8400-e29b-41d4-a716-446655440000 \
  --output ansible.log
```

## HTTP-Statuscodes

| Status | Bedeutung |
|--------|-----------|
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen — Body enthält trotzdem die Logs |
| `404 Not Found` | Unbekannte Webhook-ID |
