#!/bin/sh
# HEALTHCHECK dell'immagine e dei compose (per ruolo). L'immagine wolfi non ha wget né curl: la richiesta la fa
# Node 22 (già installato per il web) con fetch e timeout, senza pacchetti in più.

ROLE="${LH_ROLE:-all}"

# get URL MODE: MODE=up richiede lo stato complessivo "UP" di Actuator (HTTP 200 e campo status di primo livello);
# MODE=checked richiede il campo checkedAt, che /api/demo/status restituisce sempre (200), anche con l'hub giù.
get() {
    node -e '
const [url, mode] = process.argv.slice(1);
fetch(url, { signal: AbortSignal.timeout(8000) })
  .then(async (r) => {
    const body = await r.json();
    const ok = mode === "up" ? r.ok && body.status === "UP" : typeof body.checkedAt === "string";
    process.exit(ok ? 0 : 1);
  })
  .catch((e) => { console.error(e.name); process.exit(1); });
' "$1" "$2"
}

if [ "$ROLE" = "hub" ]; then
    get http://localhost:8080/actuator/health up || exit 1
elif [ "$ROLE" = "web" ]; then
    get http://localhost:3000/api/demo/status checked || exit 1
elif [ "$ROLE" = "all" ]; then
    get http://localhost:8080/actuator/health up || exit 1
    get http://localhost:3000/api/demo/status checked || exit 1
else
    exit 0
fi
