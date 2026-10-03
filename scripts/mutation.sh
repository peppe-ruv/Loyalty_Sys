#!/usr/bin/env bash
# Mutation testing sul diff (F2-QA-07, ADR-053 decisione 2, Q-691, Q-694). Consultivo: non fallisce mai per la soglia,
# stampa il rapporto di mutanti uccisi e lo scrive nel riepilogo del job (GITHUB_STEP_SUMMARY).
#
# Uso:  bash scripts/mutation.sh java|web|all   [BASE]      (BASE predefinito: origin/main)
#
# Java (PIT): le classi cambiate si ricavano da `git diff --name-only BASE...HEAD` e si passano a PIT con
#   -DtargetClasses (più robusto di scmMutationCoverage: non richiede il connettore SCM di Maven né un working tree
#   pulito, e funziona identico in CI e in locale). Un modulo senza classi main cambiate si salta. Gli *IT sono esclusi
#   dal pom (excludedTestClasses).
# Web (Stryker): i file cambiati di web/ (esclusi i test) vanno in --mutate; --incremental è nel config.
set -uo pipefail

MODE="${1:-all}"
BASE="${2:-origin/main}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 1
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
OUT="$ROOT/target/mutation"
mkdir -p "$OUT"
: > "$OUT/summary.md"; rm -f "$OUT/survivors.txt"

say() { printf '%s\n' "$*" | tee -a "$OUT/summary.md" >> "$SUMMARY"; }

CHANGED="$(git diff --name-only --diff-filter=d "$BASE...HEAD")"

say "## Mutation testing sul diff (consultivo)"
say ""
say "| Modulo | Classi/file mutati | Mutanti | Uccisi | Sopravvissuti | Senza copertura | Kill % |"
say "|---|---|---|---|---|---|---|"

run_java() {
  local mods module classes pattern pkg tests xml total killed survived nocov pct
  mods="$(grep -E '^(services|libs)/[^/]+/src/main/java/.*\.java$' <<< "$CHANGED" | cut -d/ -f1-2 | sort -u || true)"
  if [ -z "$mods" ]; then say "| java | nessuna classe main cambiata | - | - | - | - | - |"; return; fi
  while read -r module; do
    [ -z "$module" ] && continue
    classes="$(grep -E "^$module/src/main/java/.*\.java$" <<< "$CHANGED" | sed -E 's#^.*/src/main/java/##; s#\.java$##; s#/#.#g' | grep -v 'package-info$' || true)"
    [ -z "$classes" ] && continue
    pattern="$(sed 's/$/*/' <<< "$classes" | paste -sd, -)"   # Foo* include anche le classi interne Foo$Bar
    pkg="$(head -1 <<< "$classes" | cut -d. -f1-3)"            # es. io.loyaltyhub.wallet
    tests="$pkg.*"
    echo ">> PIT su $module: $pattern" >&2
    # test-compile prima: PIT lavora su target/classes e target/test-classes. -am per i moduli a monte, senza test.
    ./mvnw -B -ntp -q install -DskipTests -DskipITs -pl "$module" -am >&2 || { say "| $module | errore di build | - | - | - | - | - |"; continue; }
    ./mvnw -B -ntp test-compile org.pitest:pitest-maven:mutationCoverage -pl "$module" \
      -DtargetClasses="$pattern" -DtargetTests="$tests" -DskipITs >&2 || true
    xml="$ROOT/$module/target/pit-reports/mutations.xml"
    if [ ! -f "$xml" ]; then say "| $module | $(wc -l <<< "$classes" | tr -d ' ') classi | 0 | - | - | - | n/d |"; continue; fi
    total=$(grep -c '<mutation ' "$xml" || true)
    killed=$(grep -cE '<mutation [^>]*status=.(KILLED|TIMED_OUT|MEMORY_ERROR)' "$xml" || true)
    survived=$(grep -cE '<mutation [^>]*status=.SURVIVED' "$xml" || true)
    nocov=$(grep -cE '<mutation [^>]*status=.NO_COVERAGE' "$xml" || true)
    denom=$((killed + survived + nocov))
    if [ "$denom" -gt 0 ]; then pct=$(awk -v k="$killed" -v d="$denom" 'BEGIN{printf "%.1f", 100*k/d}'); else pct="n/d"; fi
    say "| $module | $(wc -l <<< "$classes" | tr -d ' ') classi | $total | $killed | $survived | $nocov | $pct |"
    # Mutanti sopravvissuti (primi 25) per il riepilogo, in forma leggibile.
    node -e '
      const x = require("fs").readFileSync(process.argv[1], "utf8"), mod = process.argv[2];
      const re = /<mutation [^>]*status=.(SURVIVED|NO_COVERAGE).[^>]*>([\s\S]*?)<\/mutation>/g;
      const g = (b, t) => ((b.match(new RegExp("<" + t + ">([\\s\\S]*?)</" + t + ">")) || [])[1] || "").trim();
      let m, n = 0;
      while ((m = re.exec(x)) && n++ < 25)
        console.log(mod + ": " + g(m[2], "sourceFile") + ":" + g(m[2], "lineNumber") + " " + m[1] + " - " + g(m[2], "description"));
    ' "$xml" "$module" >> "$OUT/survivors.txt"
  done <<< "$mods"
}

run_web() {
  local files list json k s n t pct
  files="$(grep -E '^web/(lib|components|app)/.*\.(ts|tsx)$' <<< "$CHANGED" | grep -Ev '\.(test|d)\.(ts|tsx)$|\.d\.ts$' | sed 's#^web/##' || true)"
  if [ -z "$files" ]; then say "| web | nessun file sorgente cambiato | - | - | - | - | - |"; return; fi
  list="$(paste -sd, - <<< "$files")"
  echo ">> Stryker su: $list" >&2
  ( cd web && pnpm exec stryker run --mutate "$list" >&2 ) || true
  json="web/reports/mutation/mutation.json"
  if [ ! -f "$json" ]; then say "| web | $(wc -l <<< "$files" | tr -d ' ') file | 0 | - | - | - | n/d |"; return; fi
  read -r t k s n pct < <(node -e '
    const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
    let t=0,k=0,s=0,n=0;
    for (const f of Object.values(r.files)) for (const m of f.mutants) {
      t++; if (m.status==="Killed"||m.status==="Timeout") k++; else if (m.status==="Survived") s++; else if (m.status==="NoCoverage") n++;
    }
    const d=k+s+n; console.log(t,k,s,n,d?(100*k/d).toFixed(1):"n/d");' "$json")
  say "| web | $(wc -l <<< "$files" | tr -d ' ') file | $t | $k | $s | $n | $pct |"
}

case "$MODE" in
  java) run_java ;;
  web) run_web ;;
  all) run_java; run_web ;;
  *) echo "uso: $0 java|web|all [BASE]" >&2; exit 2 ;;
esac

if [ -s "$OUT/survivors.txt" ]; then
  say ""
  say "<details><summary>Mutanti sopravvissuti Java (primi per modulo)</summary>"
  say ""
  say '```'
  say "$(cat "$OUT/survivors.txt")"
  say '```'
  say "</details>"
fi
say ""
say "Consultivo (Q-691): diventa obbligatorio, con almeno il 70 % di mutanti uccisi sul diff, alla chiusura di M9. Report HTML nell'artefatto \`mutation-report\` (14 giorni)."
exit 0
