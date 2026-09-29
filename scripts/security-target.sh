#!/usr/bin/env bash
# Bersaglio isolato del fuzzing Schemathesis e dello ZAP API scan (M8.11c, F2-SEC-12, ADR-042, docs/18 §3.10 p.12;
# docs/security/dast.md): l'hub in profilo demo su una rete Docker interna, senza uscita verso Internet, con Postgres e
# Kafka del compose locale. Sono dati fittizi del seed, su un database che `down` distrugge a fine job.
#
# Uso (dalla radice del repository):   bash scripts/security-target.sh up | down | logs
# Variabili: LH_IMAGE (default lh-image:dast, costruita da deploy/image/Dockerfile), LH_DAST_NETWORK (default lh-dast),
#            CURL_IMAGE (curl fissato per digest, come in image.yml).
# L'hub risponde su http://lh-hub:8080 a chi sta nella rete LH_DAST_NETWORK (gli strumenti girano in container lì).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

LH_IMAGE="${LH_IMAGE:-lh-image:dast}"
NET="${LH_DAST_NETWORK:-lh-dast}"
# Q-537: aggiornamento a mano
CURL_IMAGE="${CURL_IMAGE:-curlimages/curl:8.22.0@sha256:58adaa4e8dca9c988bae2aba4ab3434a0bb2da16bbe3f92dec39ec7785166777}"
COMPOSE=(docker compose -f deploy/docker-compose.yml -p lh-dast)

curl_in_net() {
  docker run --rm --network "$NET" "$CURL_IMAGE" "$@"
}

up() {
  "${COMPOSE[@]}" up -d --wait kafka postgres

  # Rete interna: nessun instradamento verso l'esterno. Kafka e Postgres del compose vi si aggiungono con lo stesso
  # nome che il compose dà loro (kafka:29092, postgres:5432), così l'hub usa la configurazione di image.yml.
  docker network create --internal "$NET"
  docker network connect --alias kafka "$NET" lh-kafka
  docker network connect --alias postgres "$NET" lh-postgres

  # Stesso ambiente della prova di avvio "Smoke Run LH_ROLE=hub" di image.yml. Sono valori di sviluppo effimeri
  # (database creato per questo job e distrutto da `down`), non segreti.
  docker run -d --name lh-hub --network "$NET" \
    --cap-drop ALL --security-opt no-new-privileges:true \
    -e LH_ROLE=hub \
    -e LH_PROFILE=demo \
    -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
    -e DB_URL="jdbc:postgresql://postgres:5432/loyaltyhub?currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement" \
    -e DB_URL_DIRECT=jdbc:postgresql://postgres:5432/loyaltyhub \
    -e DB_USERNAME=loyaltyhub \
    -e DB_PASSWORD=loyaltyhub \
    "$LH_IMAGE"

  # Attende lo stato complessivo UP (/actuator/health risponde 503 finché un componente non lo è). Esce subito se il
  # container è terminato.
  for _ in $(seq 1 60); do
    if curl_in_net -sf http://lh-hub:8080/actuator/health > /dev/null; then
      echo "Bersaglio pronto: http://lh-hub:8080 (rete $NET)"
      break
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' lh-hub)" != "true" ]; then
      echo "Il container lh-hub è terminato" >&2
      break
    fi
    sleep 5
  done
  if ! curl_in_net -sf http://lh-hub:8080/actuator/health > /dev/null; then
    echo "::error::il bersaglio non è diventato sano entro 5 minuti" >&2
    logs || true
    exit 1
  fi

  # SPEC-GAP: Q-534 — l'isolamento non si dà per scontato: se dalla rete si raggiunge Internet il bersaglio non è
  # isolato e il job non prova nulla di ciò che dichiara (nessuna nuova destinazione di rete in uscita, ADR-042).
  if curl_in_net -sS --max-time 5 -o /dev/null https://github.com > /dev/null 2>&1; then
    echo "::error::rete del bersaglio non isolata: da $NET si raggiunge Internet" >&2
    exit 1
  fi
  echo "Isolamento verificato: da $NET non si esce."
}

down() {
  docker rm -f lh-hub > /dev/null 2>&1 || true
  docker network disconnect "$NET" lh-kafka > /dev/null 2>&1 || true
  docker network disconnect "$NET" lh-postgres > /dev/null 2>&1 || true
  docker network rm "$NET" > /dev/null 2>&1 || true
  "${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
}

logs() {
  docker logs --tail 500 lh-hub || true
  "${COMPOSE[@]}" logs --tail 200 || true
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  logs) logs ;;
  *)
    echo "Uso: bash scripts/security-target.sh up | down | logs" >&2
    exit 2
    ;;
esac
