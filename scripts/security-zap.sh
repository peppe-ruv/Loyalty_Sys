#!/usr/bin/env bash
# ZAP API scan autenticato sulle OpenAPI di contracts/api (M8.11c, F2-SEC-12, ADR-042, docs/18 §3.10 p.12;
# docs/security/dast.md). Solo Docker: ZAP gira in un container sulla rete del bersaglio (security-target.sh up), senza
# uscita verso Internet, con l'immagine fissata per digest. Una esecuzione per specifica; poi il gate su .dast/zap-exceptions.json:
# blocca sugli avvisi High con confidenza ≠ 0 non eccettuati.
#
# Uso (dalla radice del repository):   bash scripts/security-target.sh up && bash scripts/security-zap.sh
# Variabili: LH_ZAP_OUT (default target/security/zap), LH_ZAP_MAX_MINUTES (minuti per specifica, default 5),
#            LH_DAST_ACTOR (identità demo, default ADMIN:lh-dast; Q-531), LH_DAST_NETWORK (default lh-dast), ZAP_IMAGE.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

OUT="${LH_ZAP_OUT:-target/security/zap}"
MAX_MINUTES="${LH_ZAP_MAX_MINUTES:-5}"
ACTOR="${LH_DAST_ACTOR:-ADMIN:lh-dast}"
NET="${LH_DAST_NETWORK:-lh-dast}"
# Q-537: aggiornamento a mano
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:2.17.0@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef}"

case "$OUT" in
  /* | .. | ../* | */.. | */../*)
    echo "::error::LH_ZAP_OUT deve essere una cartella relativa alla radice del repository, senza '..': $OUT" >&2
    exit 2
    ;;
esac
case "$MAX_MINUTES" in
  '' | *[!0-9]*)
    echo "::error::LH_ZAP_MAX_MINUTES deve essere un numero di minuti: $MAX_MINUTES" >&2
    exit 2
    ;;
esac

rm -rf "$OUT"
mkdir -p "$OUT"
# ZAP gira come utente zap (uid 1000) e scrive i rapporti nella cartella montata.
chmod 0777 "$OUT"
status=0

node scripts/security-dast.mjs prepare --out "$OUT/specs" || exit 1

for spec in "$OUT"/specs/*.openapi.json; do
  name="$(basename "$spec" .openapi.json)"
  echo "::group::ZAP API scan: $name"
  docker run --rm --network "$NET" \
    --cap-drop ALL --security-opt no-new-privileges:true \
    -e ZAP_AUTH_HEADER=X-LH-Actor -e ZAP_AUTH_HEADER_VALUE="$ACTOR" \
    -v "$PWD/$OUT:/zap/wrk:rw" \
    "$ZAP_IMAGE" \
    zap-api-scan.py -t "/zap/wrk/specs/$name.openapi.json" -f openapi -O http://lh-hub:8080 \
    -J "zap-$name.json" -r "zap-$name.html" -I -T 10 \
    -z "-config scanner.maxScanDurationInMins=$MAX_MINUTES -config scanner.maxRuleDurationInMins=1 -config connection.timeoutInSecs=30"
  rc=$?
  echo "::endgroup::"
  echo "$name $rc" >> "$OUT/exit-codes.txt"
  # 0 = nessun avviso; 2 = avvisi (non bloccanti qui: decide il gate); 3 = errore di ZAP (importazione, avvio…).
  case "$rc" in
    0 | 2) ;;
    *)
      echo "::error::$name: ZAP è uscito con $rc (3 = errore di importazione o di esecuzione)"
      status=1
      ;;
  esac
done

node scripts/security-dast.mjs zap-gate "$OUT" --exceptions .dast/zap-exceptions.json || status=1
exit "$status"
