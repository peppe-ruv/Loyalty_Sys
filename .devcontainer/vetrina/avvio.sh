#!/usr/bin/env bash
# Avvio della vetrina enterprise nel codespace (F2-DIST-09, ADR-050, M8.14 V7; Q-660…Q-663). postStartCommand di
# .devcontainer/vetrina/devcontainer.json: gira a ogni avvio del codespace, anche dopo un arresto per inattività.
#
# 1. allinea il clone a origin/main (solo fast-forward, ramo main pulito) e, senza il segreto LH_IMAGE, sceglie
#    l'immagine ghcr.io/<proprietario>/loyaltyhub:build-<commit> dell'ultimo commit che ha toccato l'immagine; con
#    LH_IMAGE esplicita usa quella (e la controlla);
# 2. aspetta Docker, scarica l'immagine (un tag v* allineato a HEAD subito; attesa fino a 15 minuti solo se una build è
#    in corso; accesso a ghcr.io con il token del codespace); poi rifiuta un'immagine più vecchia degli script
#    del repo (`vetrina.sh immagine`, M8.14);
# 3. lancia `vetrina.sh codespace` come root: configurazione, segreti, database di Keycloak ricreato da zero (ADR-051,
#    Q-672), avvio, overlay dei realm con gli utenti di test, operatori;
# 4. rende pubbliche le porte 8000 e 8001 (Q-661) e verifica che la discovery OIDC risponda dall'URL pubblico;
# 5. registra i membri di test dal portale (`vetrina.sh membri`, Q-673): serve l'indirizzo pubblico, quindi dopo il passo 4.
#    A registrazione riuscita scrive il marcatore `stato/membri-di-test` che HUB-01 aspetta (Q-728); a inizio avvio lo toglie.
# Non stampa segreti: vetrina.sh mostra solo nomi e percorsi, il token del codespace passa solo da stdin.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
DIR="$(dirname "${LH_VETRINA_CONFIG:?LH_VETRINA_CONFIG assente: avvio previsto solo dal dev container della vetrina}")"
sudo install -d -m 700 "$DIR"
LOG="$DIR/avvio.log"
# Registro dell'avvio anche su file, leggibile con `sudo cat` dal terminale del codespace. Dopo il rilancio per
# l'aggiornamento del clone il registro e' gia' attivo (stessa uscita ereditata): non si apre un secondo tee.
if [ "${LH_AVVIO_ALLINEATO:-}" = 1 ]; then
  echo "== rilancio dopo l'aggiornamento del clone"
else
  exec > >(sudo tee -a "$LOG") 2>&1
  echo "== avvio della vetrina $(date -u +%Y-%m-%dT%H:%M:%SZ)"
fi

# Q-728: ogni avvio riparte senza il marcatore dei membri di test registrati (lo scrive `vetrina.sh membri` a registrazione
# riuscita, in fondo a questo script). HUB-01 aspetta quel marcatore prima di mostrare «Apri la vetrina»: un marcatore
# rimasto dall'avvio precedente (i container possono ripartire da soli con il codespace) lo farebbe comparire troppo presto.
sudo rm -f "$DIR/stato/membri-di-test"
# ADR-055, Q-727: anche lo stato della console master (HUB-02) si riscrive a ogni avvio da `vetrina.sh` (`master_console`).
sudo rm -f "$DIR/stato/console-master"

# La vetrina segue main (M8.14, Q-726), con o senza il segreto LH_IMAGE: il clone si porta a origin/main (solo
# fast-forward, ramo main pulito, sempre come utente del codespace, mai come root) perche' gli script che girano sono
# quelli di main. Cambia solo la scelta dell'immagine: senza LH_IMAGE e' `build-<commit>` (piu' sotto), con LH_IMAGE
# esplicita vince il segreto e resta il controllo di freschezza.
IMMAGINE_ESPLICITA=0
if [ -n "${LH_IMAGE:-}" ]; then IMMAGINE_ESPLICITA=1; export LH_IMMAGINE_ESPLICITA=1; fi
git fetch --quiet --tags origin main || echo "Avviso: git fetch origin main non riuscito: il clone resta com'è" >&2
ramo="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
if [ "$ramo" != main ]; then
  echo "Avviso: il clone è sul ramo '${ramo:-?}', non su main: non lo aggiorno, HEAD resta $(git rev-parse --short HEAD)" >&2
elif [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  echo "Avviso: il clone ha modifiche locali: non lo aggiorno, HEAD resta $(git rev-parse --short HEAD)" >&2
else
  prima="$(git rev-parse HEAD)"
  git merge --ff-only --quiet origin/main || echo "Avviso: il clone non avanza in fast-forward su origin/main: HEAD resta $(git rev-parse --short HEAD)" >&2
  if [ "$(git rev-parse HEAD)" != "$prima" ]; then
    echo "clone aggiornato a origin/main: $(git rev-parse --short HEAD)"
    # Lo script può essere cambiato con l'aggiornamento: si rilancia una sola volta la versione nuova.
    if [ "${LH_AVVIO_ALLINEATO:-}" != 1 ]; then
      LH_AVVIO_ALLINEATO=1 exec bash .devcontainer/vetrina/avvio.sh
    fi
  fi
fi

for _ in $(seq 1 60); do
  sudo docker info >/dev/null 2>&1 && break
  sleep 2
done
sudo docker info >/dev/null 2>&1 || { echo "Errore: Docker non risponde nel codespace" >&2; exit 1; }

ENV_IMMAGINE=CODESPACES,GITHUB_REPOSITORY,GITHUB_TOKEN,GITHUB_USER,LH_IMAGE,LH_VETRINA_ALLOW_STALE_IMAGE,LH_AVVIO_ATTESA_S,LH_AVVIO_INTERVALLO_S,GH_TOKEN
if [ "$IMMAGINE_ESPLICITA" = 1 ]; then
  if ! sudo docker pull --quiet "$LH_IMAGE" >/dev/null 2>&1; then
    # Pacchetto non pubblico: il token del codespace legge i pacchetti del repository (solo da stdin, mai negli argomenti).
    if [ -n "${GITHUB_TOKEN:-}" ] && [ -n "${GITHUB_USER:-}" ]; then
      printf '%s' "$GITHUB_TOKEN" | sudo docker login ghcr.io -u "$GITHUB_USER" --password-stdin >/dev/null
    fi
    sudo docker pull --quiet "$LH_IMAGE" >/dev/null || { echo "Errore: immagine non scaricabile: $LH_IMAGE" >&2; exit 1; }
  fi
else
  # Immagine di main per questo HEAD, con attesa della build in corso e ripiego sul tag allineato (vetrina.sh immagine --risolvi).
  LH_IMAGE="$(sudo --preserve-env="$ENV_IMMAGINE" env "PATH=$PATH" bash deploy/vetrina/vetrina.sh immagine --risolvi)" || exit $?
fi
export LH_IMAGE
echo "immagine: $LH_IMAGE"

# Freschezza (F2-DIST-09, ADR-051): un'immagine più vecchia degli script del repo non si avvia (uscita 2, messaggio sopra).
# Solo diagnosi: LH_VETRINA_ALLOW_STALE_IMAGE=true la trasforma in un avviso forte.
sudo --preserve-env=LH_IMAGE,LH_VETRINA_ALLOW_STALE_IMAGE,LH_IMMAGINE_ESPLICITA env "PATH=$PATH" bash deploy/vetrina/vetrina.sh immagine

# Diagnosi senza terminale: se l'avvio si ferma, il registro riporta da solo lo stato dei container della vetrina e le
# ultime righe di Keycloak, del proxy e di ogni container fermo o non sano. Solo docker, nessuna configurazione: funziona
# anche se vetrina.sh si e' fermato prima di scriverla. I log dei container non contengono segreti (arrivano da file).
diagnosi() {
  echo "== diagnosi dei container della vetrina $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local filtro="label=com.docker.compose.project=loyaltyhub-vetrina" nomi nome
  sudo docker ps -a --filter "$filtro" --format '{{.Names}}\t{{.Status}}' 2>&1 || true
  nomi="$(sudo docker ps -a --filter "$filtro" --format '{{.Names}} {{.Label "com.docker.compose.service"}} {{.Status}}' 2>/dev/null \
    | awk '$2 == "idp" || $2 == "proxy" || $0 !~ /Up/ || $0 ~ /unhealthy|starting/ { print $1 }' | sort -u || true)"
  for nome in $nomi; do
    echo "-- ultime righe di $nome"
    sudo docker logs --tail 80 "$nome" 2>&1 || true
  done
  echo "== fine della diagnosi"
}

# PATH esplicito: Node.js (membri di test) non e' nel secure_path di sudo.
if ! sudo --preserve-env=CODESPACES,CODESPACE_NAME,GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN,LH_VETRINA_CONFIG,LH_IMAGE,LH_HUB_DEMO_URL,LH_VETRINA_OPERATORS,LH_VETRINA_MASTER_ADMIN_PASSWORD \
  env "PATH=$PATH" bash deploy/vetrina/vetrina.sh codespace; then
  diagnosi
  exit 1
fi

# Q-661: porte pubbliche senza clic. Il token del codespace può non avere il permesso: allora lo dice e indica il clic.
if gh codespace ports visibility 8000:public 8001:public -c "$CODESPACE_NAME" >/dev/null 2>&1; then
  echo "porte 8000 e 8001 pubbliche"
else
  echo "Avviso: non riesco a rendere pubbliche le porte. Nel pannello Porte del codespace: tasto destro su 8000 e 8001, Visibilità della porta, Pubblica." >&2
fi

idp="https://$CODESPACE_NAME-8001.$GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"
web="https://$CODESPACE_NAME-8000.$GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"
# Codice esatto, senza seguire reindirizzamenti: una porta privata risponde 302 verso il login di GitHub e `curl -f` lo
# considererebbe riuscito.
if [ "$(curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 --max-time 20 "$idp/realms/loyaltyhub/.well-known/openid-configuration" 2>/dev/null || true)" = 200 ]; then
  echo "discovery OIDC raggiungibile dall'URL pubblico"
else
  echo "Avviso: $idp non risponde dall'esterno: controlla che la porta 8001 sia pubblica." >&2
fi

# Membri di test (Q-673): Anna, Marco e Giulia registrati dal portale, Laura da zero. Idempotente: se `vetrina.sh codespace`
# l'ha gia' fatto con le porte pubbliche, qui non cambia nulla. Un errore non lascia la vetrina a meta' in silenzio.
membri_ok=1
sudo --preserve-env=CODESPACES,LH_VETRINA_CONFIG env "PATH=$PATH" bash deploy/vetrina/vetrina.sh membri || membri_ok=0
echo "vetrina pronta: $web"
if [ "$membri_ok" = 0 ]; then
  echo "Errore: i membri di test non sono stati registrati (messaggio sopra). Riprova: sudo --preserve-env=CODESPACES,LH_VETRINA_CONFIG env \"PATH=\$PATH\" bash deploy/vetrina/vetrina.sh membri" >&2
  exit 1
fi
