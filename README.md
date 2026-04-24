# ansiblehook

HTTP-Webhook-Service zum Auslösen von Ansible-Playbooks. Trigger-Requests werden per HMAC-SHA256-Signatur (`X-Hub-Signature-256`) authentifiziert — kompatibel zu GitHub/GitLab Webhooks. Läuft ein Playbook bereits, wird ein zweiter Request mit `409 Conflict` abgelehnt. Die vollständigen Ansible-Logs werden als Response-Body zurückgegeben.

## Konfiguration

Webhooks werden in `src/main/resources/application.yml` definiert:

```yaml
ansiblehook:
  webhooks:
    mein-webhook:               # URL-Pfad: POST /webhook/mein-webhook
      secret: <geheimes-token>  # Pflichtfeld: HMAC-Key für X-Hub-Signature-256
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

## Webhooks aufrufen

Trigger-Requests werden per `X-Hub-Signature-256`-Header authentifiziert. Der Header enthält eine HMAC-SHA256-Signatur des Request-Bodys, kompatibel zum GitHub/GitLab Webhook-Format.

Signatur berechnen (Bash):

```bash
SECRET="7f3d9a12-b4c8-4e1f-9d6a-123456789abc"
TS=$(date +%s)

SIG="sha256=$(echo -n "$TS" \
  | openssl dgst -sha256 -hmac "$SECRET" \
  | awk '{print $2}')"
```

### ping (leerer Body)

```bash
curl -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Signature: $SIG" \
  -H "X-Webhook-Timestamp: $TS"
```

### Mit Ausgabe in Datei

```bash
curl -X POST http://localhost:8080/webhook/ping \
  -H "X-Webhook-Signature: $SIG" \
  -H "X-Webhook-Timestamp: $TS" \
  --output ansible.log
```

## Status abfragen

```bash
curl -X GET http://localhost:8080/webhook/ping/status \
  -H "X-Webhook-Signature: $SIG" \
  -H "X-Webhook-Timestamp: $TS"
# {"id":"ping","running":false}
```

Gibt `401` bei fehlendem/falschem Secret und `404` bei unbekannter Webhook-ID zurück.

## HTTP-Statuscodes

| Status | Bedeutung |
|--------|-----------|
| `200 OK` | Playbook erfolgreich — Body enthält die Ansible-Logs |
| `401 Unauthorized` | Fehlende oder ungültige `X-Hub-Signature-256`-Signatur (Trigger) / falsches `X-Webhook-Secret` (Status) |
| `404 Not Found` | Unbekannte Webhook-ID |
| `409 Conflict` | Playbook läuft bereits |
| `500 Internal Server Error` | Playbook fehlgeschlagen — Body enthält trotzdem die Logs |
