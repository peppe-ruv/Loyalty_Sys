#!/usr/bin/env bash
# smoke.sh — prova E2E del core loop (docs/12 M0/M1, CLAUDE.md §4): un'azione premiante deve tradursi in
# punti sul wallet entro 15 s. Percorso: ingestion POST /v1/events → campaign valuta → wallet accredita →
# saldo aggiornato. Non richiede jq (usa node, già dipendenza del progetto).
#
# Uso:
#   scripts/smoke.sh                       # locale (compose): ingestion :8081, wallet :8084
#   MEMBER=MBR-000003 scripts/smoke.sh
#   BASE_INGESTION=https://… BASE_WALLET=https://… scripts/smoke.sh
set -euo pipefail

MEMBER="${MEMBER:-MBR-000003}"           # SILVER: 130 € → 162 PTS (×1,25)
AMOUNT="${AMOUNT:-130}"
DEADLINE_S="${DEADLINE_S:-15}"
BASE_INGESTION="${BASE_INGESTION:-http://localhost:8081}"
BASE_WALLET="${BASE_WALLET:-http://localhost:8084}"

# Estrae un campo annidato (es. "balances.PTS.active") dal JSON su stdin, senza jq.
json_field() {
  node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8")||"{}");const v=process.argv[1].split(".").reduce((o,k)=>(o==null?undefined:o[k]),d);process.stdout.write(v==null?"":String(v));' "$1"
}

wallet_pts() {
  curl -fsS --max-time 8 "$BASE_WALLET/v1/portal/wallets/$MEMBER" 2>/dev/null | json_field "balances.PTS.active"
}

echo "smoke: membro $MEMBER, azione purchase.completed ${AMOUNT}€"

before="$(wallet_pts || true)"
if [ -z "$before" ]; then
  echo "✗ wallet non raggiungibile o membro senza wallet ($BASE_WALLET). Avvia lo stack e i seed (profilo demo)." >&2
  exit 1
fi
echo "saldo iniziale: $before PTS"

event_id="smoke-$(date +%s)-$RANDOM"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
payload=$(cat <<JSON
{"specversion":"1.0","id":"$event_id","source":"urn:loyaltyhub:source:ecommerce","type":"purchase.completed","subject":"member:$MEMBER","time":"$now","data":{"orderId":"ORD-$event_id","amount":$AMOUNT,"currency":"EUR","channel":"ONLINE"}}
JSON
)

status=$(curl -fsS --max-time 8 -X POST "$BASE_INGESTION/v1/events" \
  -H "content-type: application/json" -d "$payload" | json_field "status")
echo "ingestion: $status"
if [ "$status" != "ACCEPTED" ]; then
  echo "✗ l'azione non è stata accettata (status=$status)." >&2
  exit 1
fi

echo "attendo l'accredito (max ${DEADLINE_S}s)…"
deadline=$(( $(date +%s) + DEADLINE_S ))
while :; do
  after="$(wallet_pts || true)"
  if [ -n "$after" ] && [ "$after" -gt "$before" ] 2>/dev/null; then
    echo "✓ saldo aggiornato: $before → $after PTS (+$((after - before)))"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "✗ nessun accredito entro ${DEADLINE_S}s (saldo ancora $after)." >&2
    exit 1
  fi
  sleep 1
done
