#!/usr/bin/env sh
# Seed di esempio: un membro demo, tre azioni premianti, un accredito manuale. Le regole di esempio sono nel rules-engine (RulesConfig).
# In cluster: usa port-forward; in locale: SEED_INGRESS/SEED_LEDGER puntano ai servizi esposti da docker compose.
set -e
NAMESPACE=${NAMESPACE:-loyalty}
if [ -z "$SEED_INGRESS" ]; then
  kubectl -n "$NAMESPACE" port-forward svc/ingress-adapters 18081:8081 >/dev/null 2>&1 & PF1=$!
  kubectl -n "$NAMESPACE" port-forward svc/ledger 18083:8083 >/dev/null 2>&1 & PF2=$!
  kubectl -n "$NAMESPACE" port-forward svc/member-service 18091:8091 >/dev/null 2>&1 & PF3=$!
  sleep 3
  SEED_INGRESS=http://localhost:18081; SEED_LEDGER=http://localhost:18083; SEED_MEMBERS=http://localhost:18091
  trap 'kill $PF1 $PF2 $PF3 2>/dev/null' EXIT
fi
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "-> azioni premianti di esempio"
curl -sS -X POST "$SEED_INGRESS/v1/actions" -H 'content-type: application/json' -d @- <<JSON
{"items":[
 {"memberId":"demo-member","action":{"actionType":"SELF_READING_SENT","idempotencyKey":"app:demo-1:SELF_READING_SENT","occurredAt":"$NOW","attributes":{"meter":"GAS"}}},
 {"memberId":"demo-member","action":{"actionType":"DIRECT_DEBIT_ACTIVATED","idempotencyKey":"crm:demo-1:DIRECT_DEBIT_ACTIVATED","occurredAt":"$NOW","attributes":{}}},
 {"memberId":"demo-member","action":{"actionType":"BILL_PAID_ON_TIME","idempotencyKey":"sap:INV-DEMO-1:PAID","occurredAt":"$NOW","attributes":{"amountEur":84.30,"contractType":"GAS"}}}
]}
JSON
echo; echo "-> ripetizione della stessa chiave (atteso DUPLICATE)"
curl -sS -X POST "$SEED_INGRESS/v1/actions" -H 'content-type: application/json' -d "{\"items\":[{\"memberId\":\"demo-member\",\"action\":{\"actionType\":\"SELF_READING_SENT\",\"idempotencyKey\":\"app:demo-1:SELF_READING_SENT\",\"occurredAt\":\"$NOW\"}}]}"
echo; echo "-> adesione con consenso newsletter (RF-72) e codice referral (RF-68)"
MEMBERS=${SEED_MEMBERS:-http://localhost:8091}
curl -sS -X POST "$MEMBERS/v1/members" -H 'content-type: application/json' -d '{"memberId":"demo-member","channel":"web","consents":{"newsletter":true}}'; echo
REF=$(curl -sS "$MEMBERS/v1/members/demo-member/referral" | sed 's/.*"code":"\([A-Z0-9]*\)".*/\1/')
curl -sS -X POST "$MEMBERS/v1/members" -H 'content-type: application/json' -d "{\"memberId\":\"demo-friend\",\"channel\":\"app\",\"referralCode\":\"$REF\"}"; echo
echo "-> transazione con righe (RF-62/63): 1 punto per euro, consegna esclusa, in sospeso 14 giorni"
curl -sS -X POST "$SEED_INGRESS/v1/actions" -H 'content-type: application/json' -d @- <<JSON
{"items":[{"memberId":"demo-member","action":{"actionType":"TRANSACTION","idempotencyKey":"shop:ORD-DEMO-1:PAID","occurredAt":"$NOW","attributes":{"amountEur":129.0,"channel":"negozio",
 "lines":[{"sku":"MANUT-CALDAIA-STD","name":"Manutenzione caldaia","category":"servizi","brand":"ServiziPlus","quantity":1,"amountEur":119.0,"labels":["green"]},
          {"sku":"DELIVERY","name":"Uscita tecnico","category":"servizi","quantity":1,"amountEur":10.0,"labels":["delivery"]}]}}}]}
JSON
echo; echo "-> check-in geolocalizzato (RF-67) e codice promozionale (RF-69)"
curl -sS -X POST "$SEED_INGRESS/v1/check-ins" -H 'content-type: application/json' -d '{"memberId":"demo-member","placeId":"negozio-torino-centro","lat":45.0704,"lon":7.6870}'; echo
curl -sS -X PUT "$SEED_INGRESS/v1/codes" -H 'content-type: application/json' -d '{"code":"WELCOME-2027","campaign":"WELCOME2027","kind":"PROMO","maxUses":0,"maxUsesPerMember":1,"active":true}'; echo
curl -sS -X POST "$SEED_INGRESS/v1/codes/redeem" -H 'content-type: application/json' -d '{"memberId":"demo-member","code":"WELCOME-2027","channel":"web"}'; echo
echo "-> giro della ruota dei punti (RF-95) e simulazione della campagna cashback a scaglioni (RF-82/RF-86)"
CONTESTS=${SEED_CONTESTS:-http://localhost:8086}; RULES=${SEED_RULES:-http://localhost:8082}
curl -sS -X POST "$CONTESTS/v1/wheels/ruota-punti/spins" -H 'content-type: application/json' -d '{"memberId":"demo-member","deviceFingerprint":"seed"}'; echo
curl -sS -X POST "$RULES/v1/simulations" -H 'content-type: application/json' -d "{\"action\":{\"actionType\":\"TRANSACTION\",\"idempotencyKey\":\"sim:1:TRANSACTION\",\"occurredAt\":\"$NOW\",\"attributes\":{\"amountEur\":6000}},\"member\":{\"memberId\":\"demo-member\",\"tier\":\"TOP\",\"segments\":[],\"wallets\":{\"PREMIO\":{\"active\":0,\"earned\":0,\"spent\":0,\"pending\":0,\"blocked\":0,\"expired\":0}},\"attributes\":{},\"badges\":[]}}"; echo
sleep 3
echo "-> saldi"
curl -sS "$SEED_LEDGER/v1/ledger/members/demo-member/balances"; echo
