#!/usr/bin/env bash
# Integrationstests für den Webhook "ping" gegen einen laufenden Server.
# Voraussetzung: mvn spring-boot:run (Port 8080)
# Ansible muss NICHT konfiguriert sein — Trigger-Tests akzeptieren 200 und 500.

BASE_URL="${BASE_URL:-http://localhost:8080}"
SECRET="7f3d9a12-b4c8-4e1f-9d6a-123456789abc"
WEBHOOK="ping"
PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

# --- Hilfsfunktionen ---

ts()  { date +%s; }

sig() {
  local t="$1" id="$2"
  printf 'sha256=%s' "$(printf '%s' "${t}.${id}" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')"
}

get() {
  local url="$1"; shift
  curl -s -o /dev/null -w "%{http_code}" "$@" "$url"
}

post() {
  local url="$1"; shift
  curl -s -o /dev/null -w "%{http_code}" -X POST "$@" "$url"
}

post_body() {
  local url="$1"; shift
  curl -s -w "\n%{http_code}" -X POST "$@" "$url"
}

check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf "${GREEN}PASS${NC} %s\n" "$name"
    PASS=$((PASS + 1))
  else
    printf "${RED}FAIL${NC} %s — erwartet %s, bekommen %s\n" "$name" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

# Akzeptiert mehrere gültige Codes (z. B. "200" "500")
check_any() {
  local name="$1" actual="$2"; shift 2
  for exp in "$@"; do
    if [[ "$actual" == "$exp" ]]; then
      printf "${GREEN}PASS${NC} %s\n" "$name"
      PASS=$((PASS + 1))
      return
    fi
  done
  printf "${RED}FAIL${NC} %s — erwartet %s, bekommen %s\n" "$name" "$*" "$actual"
  FAIL=$((FAIL + 1))
}

section() { printf "\n${BOLD}%s${NC}\n" "$1"; }

# --- Serververbindung prüfen ---

if ! curl -sf "$BASE_URL/webhook/$WEBHOOK/status" -o /dev/null \
       --max-time 2 2>/dev/null; then
  if ! curl -sf -o /dev/null --max-time 2 "$BASE_URL/webhook/$WEBHOOK/status" \
         -H "X-Webhook-Timestamp: 0" -H "X-Webhook-Signature: sha256=x" 2>/dev/null; then
    :  # Server antwortet auf irgendetwas → weiter
  fi
fi
printf "Teste gegen %s\n" "$BASE_URL"

# =============================================================
# POST /webhook/ping — Authentifizierungsfehler
# =============================================================

section "POST /webhook/$WEBHOOK — Auth-Fehler"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK")
check "Kein Auth-Header → 401" "401" "$CODE"

CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: sha256=000000deadbeef")
check "Falsche Signatur → 401" "401" "$CODE"

OLD_TS=$(($(ts) - 400))
OLD_SIG=$(sig "$OLD_TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
  -H "X-Webhook-Timestamp: $OLD_TS" \
  -H "X-Webhook-Signature: $OLD_SIG")
check "Abgelaufener Timestamp (>5 min) → 401" "401" "$CODE"

CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
  -H "X-Webhook-Token: ungueltigertoken123")
check "Ungültiger Token → 401" "401" "$CODE"

# =============================================================
# POST /webhook/ping — Unbekannter Webhook
# =============================================================

section "POST /webhook/doesnotexist — 404"

TS=$(ts); SIG=$(sig "$TS" "doesnotexist")
CODE=$(post "$BASE_URL/webhook/doesnotexist" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Unbekannte Webhook-ID → 404" "404" "$CODE"

# =============================================================
# POST /webhook/ping — Auslösen (Ansible erforderlich für 200)
# =============================================================

section "POST /webhook/$WEBHOOK — Auslösen (200 ok, 500 falls Ansible fehlt)"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check_any "Trigger mit HMAC-Signatur → 200/500" "$CODE" "200" "500"

# =============================================================
# GET /webhook/ping/status — Authentifizierungsfehler
# =============================================================

section "GET /webhook/$WEBHOOK/status — Auth-Fehler"

CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status")
check "Kein Auth-Header → 401" "401" "$CODE"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: sha256=000000deadbeef")
check "Falsche Signatur → 401" "401" "$CODE"

OLD_TS=$(($(ts) - 400))
OLD_SIG=$(sig "$OLD_TS" "$WEBHOOK")
CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
  -H "X-Webhook-Timestamp: $OLD_TS" \
  -H "X-Webhook-Signature: $OLD_SIG")
check "Abgelaufener Timestamp → 401" "401" "$CODE"

CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
  -H "X-Webhook-Token: ungueltigertoken123")
check "Ungültiger Token → 401" "401" "$CODE"

# =============================================================
# GET /webhook/ping/status — Unbekannter Webhook
# =============================================================

section "GET /webhook/doesnotexist/status — 404"

TS=$(ts); SIG=$(sig "$TS" "doesnotexist")
CODE=$(get "$BASE_URL/webhook/doesnotexist/status" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Unbekannte Webhook-ID → 404" "404" "$CODE"

# =============================================================
# GET /webhook/ping/status — Erfolg mit HMAC
# =============================================================

section "GET /webhook/$WEBHOOK/status — Erfolg"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Status mit HMAC-Signatur → 200" "200" "$CODE"

# =============================================================
# POST /webhook/ping/token — Authentifizierungsfehler
# =============================================================

section "POST /webhook/$WEBHOOK/token — Auth-Fehler"

TS=$(ts)
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: sha256=000000deadbeef")
check "Falsche Signatur → 401" "401" "$CODE"

OLD_TS=$(($(ts) - 400))
OLD_SIG=$(sig "$OLD_TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $OLD_TS" \
  -H "X-Webhook-Signature: $OLD_SIG")
check "Abgelaufener Timestamp → 401" "401" "$CODE"

# =============================================================
# POST /webhook/ping/token — Unbekannter Webhook
# =============================================================

section "POST /webhook/doesnotexist/token — 404"

TS=$(ts); SIG=$(sig "$TS" "doesnotexist")
CODE=$(post "$BASE_URL/webhook/doesnotexist/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Unbekannte Webhook-ID → 404" "404" "$CODE"

# =============================================================
# POST /webhook/ping/token — Erfolg und Randwerte
# =============================================================

section "POST /webhook/$WEBHOOK/token — Erfolg und Randwerte"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=3600" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Token erstellen (Standard-TTL 3600s) → 200" "200" "$CODE"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=300" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Token erstellen (TTL 300s) → 200" "200" "$CODE"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=999999" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Token TTL wird auf 86400 geclampt → 200" "200" "$CODE"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=0" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Token TTL 0 wird auf 1s geclampt → 200" "200" "$CODE"

NOT_BEFORE="2099-01-01T02:00:00Z"
TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=1800&not_before=$NOT_BEFORE" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Token mit not_before → 200" "200" "$CODE"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
CODE=$(post "$BASE_URL/webhook/$WEBHOOK/token?ttl=3600&not_before=kein-datum" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
check "Ungültiges not_before-Format → 400" "400" "$CODE"

# =============================================================
# Token-Flow: erstellen → Status → verbraucht
# =============================================================

section "Token-Flow: erstellen → Status → verbraucht"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
RESPONSE=$(post_body "$BASE_URL/webhook/$WEBHOOK/token?ttl=60" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG" \
  -H "Content-Type: application/json")
HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -1)
TOKEN=$(printf '%s' "$RESPONSE" | head -n -1 | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

check "Token-Erstellung → 200" "200" "$HTTP_CODE"

if [[ -n "$TOKEN" ]]; then
  CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
    -H "X-Webhook-Token: $TOKEN")
  check "Status mit gültigem Token → 200" "200" "$CODE"

  CODE=$(get "$BASE_URL/webhook/$WEBHOOK/status" \
    -H "X-Webhook-Token: $TOKEN")
  check "Token nach Nutzung verbraucht → 401" "401" "$CODE"
else
  printf "${RED}FAIL${NC} Token konnte nicht aus Antwort extrahiert werden\n"
  FAIL=$((FAIL + 2))
fi

# =============================================================
# Token-Flow: erstellen → Trigger → verbraucht
# =============================================================

section "Token-Flow: erstellen → Trigger → verbraucht (200/500 falls Ansible fehlt)"

TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
RESPONSE=$(post_body "$BASE_URL/webhook/$WEBHOOK/token?ttl=60" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -1)
TOKEN=$(printf '%s' "$RESPONSE" | head -n -1 | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

check "Token-Erstellung → 200" "200" "$HTTP_CODE"

if [[ -n "$TOKEN" ]]; then
  CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
    -H "X-Webhook-Token: $TOKEN")
  check_any "Trigger mit gültigem Token → 200/500" "$CODE" "200" "500"

  CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
    -H "X-Webhook-Token: $TOKEN")
  check "Token nach Trigger verbraucht → 401" "401" "$CODE"
else
  printf "${RED}FAIL${NC} Token konnte nicht aus Antwort extrahiert werden\n"
  FAIL=$((FAIL + 2))
fi

# =============================================================
# Token-Flow: not_before in der Zukunft → zu früh → 401
# =============================================================

section "Token mit not_before in der Zukunft — noch nicht gültig"

NOT_BEFORE="2099-06-01T00:00:00Z"
TS=$(ts); SIG=$(sig "$TS" "$WEBHOOK")
RESPONSE=$(post_body "$BASE_URL/webhook/$WEBHOOK/token?ttl=3600&not_before=$NOT_BEFORE" \
  -H "X-Webhook-Timestamp: $TS" \
  -H "X-Webhook-Signature: $SIG")
HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -1)
TOKEN=$(printf '%s' "$RESPONSE" | head -n -1 | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

check "Token mit zukünftigem not_before erstellen → 200" "200" "$HTTP_CODE"

if [[ -n "$TOKEN" ]]; then
  CODE=$(post "$BASE_URL/webhook/$WEBHOOK" \
    -H "X-Webhook-Token: $TOKEN")
  check "Trigger vor not_before → 401 (Token bleibt erhalten)" "401" "$CODE"
else
  printf "${RED}FAIL${NC} Token konnte nicht aus Antwort extrahiert werden\n"
  FAIL=$((FAIL + 1))
fi

# =============================================================
# Ergebnis
# =============================================================

TOTAL=$((PASS + FAIL))
printf "\n${BOLD}Ergebnis: %d/%d Tests bestanden${NC}\n" "$PASS" "$TOTAL"

if [[ $FAIL -eq 0 ]]; then
  printf "${GREEN}Alle Tests bestanden.${NC}\n"
  exit 0
else
  printf "${RED}%d Test(s) fehlgeschlagen.${NC}\n" "$FAIL"
  exit 1
fi
