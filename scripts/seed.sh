#!/usr/bin/env sh
# Seed di esempio: un membro demo, tre azioni premianti, un accredito manuale. Le regole di esempio sono nel rules-engine (RulesConfig).
# In cluster: usa port-forward; in locale: SEED_INGRESS/SEED_LEDGER puntano ai servizi esposti da docker compose.
set -e
NAMESPACE=${NAMESPACE:-loyalty}
if [ -z "$SEED_INGRESS" ]; then
  kubectl -n "$NAMESPACE" port-forward svc/ingress-adapters 18081:8081 >/dev/null 2>&1 & PF1=$!
  kubectl -n "$NAMESPACE" port-forward svc/ledger 18083:8083 >/dev/null 2>&1 & PF2=$!
  sleep 3
  SEED_INGRESS=http://localhost:18081; SEED_LEDGER=http://localhost:18083
  trap 'kill $PF1 $PF2 2>/dev/null' EXIT
fi
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "-> azioni premianti di esempio"
curl -sS -X POST "$SEED_INGRESS/v1/actions" -H 'content-type: application/json' -d @- <<JSON
{"items":[
 {"memberId":"demo-member","action":{"actionType":"SELF_READING_SENT","idempotencyKey":"irenyou:demo-1:SELF_READING_SENT","occurredAt":"$NOW","attributes":{"meter":"GAS"}}},
 {"memberId":"demo-member","action":{"actionType":"DIRECT_DEBIT_ACTIVATED","idempotencyKey":"salesforce:demo-1:DIRECT_DEBIT_ACTIVATED","occurredAt":"$NOW","attributes":{}}},
 {"memberId":"demo-member","action":{"actionType":"BILL_PAID_ON_TIME","idempotencyKey":"sap:INV-DEMO-1:PAID","occurredAt":"$NOW","attributes":{"amountEur":84.30,"contractType":"GAS"}}}
]}
JSON
echo; echo "-> ripetizione della stessa chiave (atteso DUPLICATE)"
curl -sS -X POST "$SEED_INGRESS/v1/actions" -H 'content-type: application/json' -d "{\"items\":[{\"memberId\":\"demo-member\",\"action\":{\"actionType\":\"SELF_READING_SENT\",\"idempotencyKey\":\"irenyou:demo-1:SELF_READING_SENT\",\"occurredAt\":\"$NOW\"}}]}"
echo; sleep 3
echo "-> saldi"
curl -sS "$SEED_LEDGER/v1/ledger/members/demo-member/balances"; echo
