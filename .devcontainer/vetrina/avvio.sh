#!/usr/bin/env bash
# Avvio della vetrina enterprise nel codespace (F2-DIST-09, ADR-050, M8.14 V7; Q-660…Q-663). postStartCommand di
# .devcontainer/vetrina/devcontainer.json: gira a ogni avvio del codespace, anche dopo un arresto per inattività.
#
# 1. sceglie l'immagine unica: il segreto del codespace LH_IMAGE, altrimenti ghcr.io/<proprietario>/loyaltyhub all'ultimo
#    tag v* del repository;
# 2. aspetta Docker e, se l'immagine non si scarica, accede a ghcr.io con il token del codespace;
# 3. lancia `vetrina.sh codespace` come root: configurazione, segreti, avvio, overlay del realm al primo avvio, operatori;
# 4. rende pubbliche le porte 8000 e 8001 (Q-661) e verifica che la discovery OIDC risponda dall'URL pubblico.
# Non stampa segreti: vetrina.sh mostra solo nomi e percorsi, il token del codespace passa solo da stdin.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
DIR="$(dirname "${LH_VETRINA_CONFIG:?LH_VETRINA_CONFIG assente: avvio previsto solo dal dev container della vetrina}")"
sudo install -d -m 700 "$DIR"
LOG="$DIR/avvio.log"
# Registro dell'avvio anche su file, leggibile con `sudo cat` dal terminale del codespace.
exec > >(sudo tee -a "$LOG") 2>&1
echo "== avvio della vetrina $(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ -z "${LH_IMAGE:-}" ]; then
  owner="${GITHUB_REPOSITORY%%/*}"
  git fetch --quiet --tags origin || true
  tag="$(git tag --list 'v*' --sort=-v:refname | head -n 1)"
  [ -n "$owner" ] && [ -n "$tag" ] || { echo "Errore: imposta il segreto del codespace LH_IMAGE (nessun tag v* trovato)" >&2; exit 2; }
  LH_IMAGE="ghcr.io/${owner,,}/loyaltyhub:$tag"
fi
export LH_IMAGE
echo "immagine: $LH_IMAGE"

for _ in $(seq 1 60); do
  sudo docker info >/dev/null 2>&1 && break
  sleep 2
done
sudo docker info >/dev/null 2>&1 || { echo "Errore: Docker non risponde nel codespace" >&2; exit 1; }

if ! sudo docker pull --quiet "$LH_IMAGE" >/dev/null 2>&1; then
  # Pacchetto non pubblico: il token del codespace legge i pacchetti del repository (solo da stdin, mai negli argomenti).
  if [ -n "${GITHUB_TOKEN:-}" ] && [ -n "${GITHUB_USER:-}" ]; then
    printf '%s' "$GITHUB_TOKEN" | sudo docker login ghcr.io -u "$GITHUB_USER" --password-stdin >/dev/null
  fi
  sudo docker pull --quiet "$LH_IMAGE" >/dev/null || { echo "Errore: immagine non scaricabile: $LH_IMAGE" >&2; exit 1; }
fi

sudo --preserve-env=CODESPACES,CODESPACE_NAME,GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN,LH_VETRINA_CONFIG,LH_IMAGE,LH_HUB_DEMO_URL,LH_VETRINA_OPERATORS \
  bash deploy/vetrina/vetrina.sh codespace

# Q-661: porte pubbliche senza clic. Il token del codespace può non avere il permesso: allora lo dice e indica il clic.
if gh codespace ports visibility 8000:public 8001:public -c "$CODESPACE_NAME" >/dev/null 2>&1; then
  echo "porte 8000 e 8001 pubbliche"
else
  echo "Avviso: non riesco a rendere pubbliche le porte. Nel pannello Porte del codespace: tasto destro su 8000 e 8001, Visibilità della porta, Pubblica." >&2
fi

idp="https://$CODESPACE_NAME-8001.$GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"
web="https://$CODESPACE_NAME-8000.$GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"
if curl -fsS -o /dev/null --max-time 20 "$idp/realms/loyaltyhub/.well-known/openid-configuration"; then
  echo "discovery OIDC raggiungibile dall'URL pubblico"
else
  echo "Avviso: $idp non risponde dall'esterno: controlla che la porta 8001 sia pubblica." >&2
fi
echo "vetrina pronta: $web"
