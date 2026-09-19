#!/usr/bin/env bash
# wake.sh — accende i servizi serverless (docs/11 §3, docs/12 M1.7): interroga l'health di ogni servizio
# finché tutti rispondono o scade il timeout. I servizi del piano gratuito si addormentano per inattività;
# la prima richiesta li risveglia (1–3 min). Usato da HUB-01 "Accendi la demo" e prima di smoke.sh.
#
# Uso:
#   scripts/wake.sh                       # servizi locali (compose): http://localhost:8081..8088
#   BASE_INGESTION=https://… … scripts/wake.sh   # override per il deploy
#   TIMEOUT=180 scripts/wake.sh           # secondi complessivi (default 180)
set -euo pipefail

TIMEOUT="${TIMEOUT:-180}"
INTERVAL="${INTERVAL:-5}"

# Servizio → porta locale. Gli URL si possono sovrascrivere con BASE_<SERVIZIO> (maiuscolo).
SERVICES="ingestion:8081 member:8082 campaign:8083 wallet:8084 reward:8085 gamification:8086 engagement:8087 insight:8088"

base_url() {
  local name="$1" port="$2"
  local var="BASE_${name^^}"
  echo "${!var:-http://localhost:${port}}"
}

is_up() {
  curl -fsS --max-time 5 "$1/actuator/health/liveness" >/dev/null 2>&1
}

echo "Risveglio dei servizi (timeout ${TIMEOUT}s)…"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  ready=0
  total=0
  down=""
  for entry in $SERVICES; do
    name="${entry%%:*}"; port="${entry##*:}"
    total=$((total + 1))
    url="$(base_url "$name" "$port")"
    if is_up "$url"; then
      ready=$((ready + 1))
    else
      down="$down $name"
      # Una richiesta "a vuoto" all'health innesca comunque il risveglio del servizio dormiente.
      curl -fsS --max-time 5 "$url/actuator/health" >/dev/null 2>&1 || true
    fi
  done
  echo "pronti ${ready}/${total}${down:+ — in avvio:${down}}"
  if [ "$ready" -eq "$total" ]; then
    echo "Tutti i servizi sono attivi."
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "Timeout: alcuni servizi non si sono svegliati:${down}" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done
