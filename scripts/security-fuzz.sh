#!/usr/bin/env bash
# Fuzzing delle API con Schemathesis sulle OpenAPI di contracts/api (M8.11c, F2-SEC-12, ADR-042, docs/18 §3.10 p.12;
# docs/security/dast.md). Una esecuzione per specifica (esclusioni di .dast/exclusions.json), con la configurazione di
# .dast/schemathesis.toml e la baseline con scadenza di .dast/schemathesis-baseline.json. Blocca sui 5xx fuori baseline.
#
# Uso (dalla radice del repository):
#   bash scripts/security-target.sh up && bash scripts/security-fuzz.sh      # con Docker: bersaglio isolato e immagine fissata
#   SCHEMATHESIS=.venv/bin/schemathesis LH_FUZZ_URL=http://127.0.0.1:8080 bash scripts/security-fuzz.sh   # senza Docker
# Variabili:
#   LH_FUZZ_OUT              cartella dei risultati, relativa alla radice (default target/security/fuzz)
#   LH_FUZZ_MAX_TIME         secondi per specifica (default 120)
#   LH_FUZZ_BASELINE_UPDATE  1 = aggiunge alla baseline i 5xx trovati (poi vanno annotati a mano); default 0
#   SCHEMATHESIS             binario locale: se impostato niente Docker, bersaglio LH_FUZZ_URL (default http://127.0.0.1:8080)
#   SCHEMATHESIS_IMAGE       immagine fissata per digest (senza SCHEMATHESIS); LH_DAST_NETWORK la rete del bersaglio
# Fuori da Docker e da loopback il limite di frequenza degli ingressi (60/min per IP) è in vigore: le operazioni
# POST /v1/events e /v1/transactions hanno perciò 25 richieste al minuto in .dast/schemathesis.toml.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

OUT="${LH_FUZZ_OUT:-target/security/fuzz}"
MAX_TIME="${LH_FUZZ_MAX_TIME:-120}"
UPDATE="${LH_FUZZ_BASELINE_UPDATE:-0}"
# Q-537: aggiornamento a mano
SCHEMATHESIS_IMAGE="${SCHEMATHESIS_IMAGE:-ghcr.io/schemathesis/schemathesis:4.28.0@sha256:0a71757c60ccdba270c154a859d9dd3d019625f782f23ab36ad604771e15f78b}"

case "$OUT" in
  /* | .. | ../* | */.. | */../*)
    echo "::error::LH_FUZZ_OUT deve essere una cartella relativa alla radice del repository, senza '..': $OUT" >&2
    exit 2
    ;;
esac
case "$MAX_TIME" in
  '' | *[!0-9]*)
    echo "::error::LH_FUZZ_MAX_TIME deve essere un numero di secondi: $MAX_TIME" >&2
    exit 2
    ;;
esac

if [ -n "${SCHEMATHESIS:-}" ]; then
  # Modalità locale: nessun Docker; il bersaglio è quello che l'utente ha avviato.
  cmd=("$SCHEMATHESIS")
  url="${LH_FUZZ_URL:-http://127.0.0.1:8080}"
else
  # Docker: la rete del bersaglio non ha uscita (security-target.sh), l'immagine è fissata per digest.
  cmd=(docker run --rm --network "${LH_DAST_NETWORK:-lh-dast}" --user "$(id -u):$(id -g)"
    --cap-drop ALL --security-opt no-new-privileges:true
    -e HOME=/tmp
    -v "$PWD:/work" -w /work "$SCHEMATHESIS_IMAGE")
  url="http://lh-hub:8080"
fi

rm -rf "$OUT"
mkdir -p "$OUT/specs"
status=0

# Con LH_FUZZ_BASELINE_UPDATE=1 la baseline può contenere voci appena registrate e non ancora annotate: qui è un avviso;
# in ogni altra esecuzione una voce senza scadenza, motivo e ticket fa fallire il job.
if [ "$UPDATE" = "1" ]; then
  node scripts/security-dast.mjs baseline-check .dast/schemathesis-baseline.json || echo "::warning::baseline con voci da annotare"
else
  node scripts/security-dast.mjs baseline-check .dast/schemathesis-baseline.json || exit 1
fi
node scripts/security-dast.mjs prepare --out "$OUT/specs" || exit 1

for spec in "$OUT"/specs/*.openapi.json; do
  name="$(basename "$spec" .openapi.json)"
  mkdir -p "$OUT/$name"
  extra=()
  if [ "$UPDATE" = "1" ]; then
    extra+=(--baseline-update)
  fi
  echo "::group::Schemathesis: $name"
  "${cmd[@]}" --config-file .dast/schemathesis.toml run "$spec" \
    --url "$url" --max-time "$MAX_TIME" \
    --report junit,json \
    --report-junit-path "$OUT/$name/junit.xml" \
    --report-json-path "$OUT/$name/report.json" \
    ${extra[@]+"${extra[@]}"}
  rc=$?
  echo "::endgroup::"
  echo "$name $rc" >> "$OUT/exit-codes.txt"
  case "$rc" in
    0) ;;
    1)
      # 1 = almeno un difetto fuori baseline (5xx) oppure errori di rete o timeout: il riepilogo distingue i due casi.
      # Con LH_FUZZ_BASELINE_UPDATE=1 lo scopo è proprio registrare i difetti.
      if [ "$UPDATE" != "1" ]; then
        echo "::error::$name: 5xx fuori baseline o errori di rete e timeout (vedi il rapporto in $OUT/$name)"
        status=1
      fi
      ;;
    *)
      echo "::error::$name: Schemathesis è uscito con $rc (errori, timeout o configurazione non valida)"
      status=1
      ;;
  esac
done

node scripts/security-dast.mjs fuzz-summary "$OUT" || status=1

if [ "$UPDATE" = "1" ]; then
  cp .dast/schemathesis-baseline.json "$OUT/"
  echo "Baseline aggiornata: annota ogni voce nuova con expires (≤ 90 giorni), reason e ticket (docs/security/dast.md)."
fi
exit "$status"
