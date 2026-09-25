#!/usr/bin/env bash
# testbook.sh — esegue il testbook funzionale (docs/16) e produce il rapporto riga per riga.
# I casi del testbook sono test automatici con nome che inizia per l'ID della riga ([TB-XXX-NNN]):
#   backend: classi Testbook*Test (surefire) e Testbook*IT (failsafe) in tutti i moduli del reattore;
#   web: file *.testbook.test.ts(x) (vitest, reporter JUnit).
# Uso:
#   scripts/testbook.sh                 # backend + web, rapporto in target/testbook/rapporto.md
#   TESTBOOK_WEB=0 scripts/testbook.sh  # solo backend
#   TESTBOOK_JAVA=0 scripts/testbook.sh # solo web
# Esce ≠ 0 se una riga fallisce o se una riga documentata non è eseguita da nessun test (TESTBOOK_STRICT=0 per
# avere solo il rapporto).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=target/testbook
rm -rf "$OUT" && mkdir -p "$OUT/junit"
status=0

if [ "${TESTBOOK_JAVA:-1}" = "1" ]; then
  echo "▶ testbook backend (Testbook*Test, Testbook*IT)…"
  ./mvnw -q verify -Dtest='Testbook*Test' -Dit.test='Testbook*IT' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dmaven.test.failure.ignore=true -Dfailsafe.testFailureIgnore=true > "$OUT/maven.log" 2>&1 || status=1
  find . -path ./web/node_modules -prune -o \( -path '*/target/surefire-reports/TEST-*Testbook*.xml' -o \
       -path '*/target/failsafe-reports/TEST-*Testbook*.xml' \) -print 2>/dev/null \
    | while read -r f; do cp "$f" "$OUT/junit/$(echo "$f" | tr '/' '_' | sed 's/^\._//')"; done
fi

if [ "${TESTBOOK_WEB:-1}" = "1" ]; then
  echo "▶ testbook web (*.testbook.test.ts[x])…"
  (cd web && pnpm -s exec vitest run --reporter=default --reporter=junit \
     --outputFile.junit="../$OUT/junit/web-testbook.xml" testbook) > "$OUT/vitest.log" 2>&1 || true
fi

node scripts/testbook-report.mjs "$OUT/junit" "$OUT/rapporto.md" || status=1
echo "rapporto: $OUT/rapporto.md"
exit $status
