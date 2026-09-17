#!/usr/bin/env bash
#
# Pubblica il monorepo Loyalty Hub su GitHub: crea il repository se manca e spinge il
# branch corrente. Lo script non crea commit e non modifica l'albero di lavoro: pubblica
# ciò che è già stato committato, perché un `git add`/`git commit` nascosto dentro uno
# script di pubblicazione è il modo più rapido per spedire file non voluti.
#
# Uso:
#   GITHUB_TOKEN=ghp_xxx scripts/publish-github.sh <owner> <repo> [private|public]
#
# Variabili d'ambiente:
#   GITHUB_TOKEN  (obbligatoria) token con scope `repo`
#   PUSH_TAGS=1   spinge anche i tag annotati raggiungibili dal branch
#   DRY_RUN=1     stampa le operazioni senza eseguirle
#
# Uscite: 0 pubblicato, 1 uso errato o precondizione mancante, 2 errore dell'API GitHub.

set -euo pipefail

readonly API="https://api.github.com"
readonly API_VERSION="2022-11-28"

usage() {
  cat <<'USAGE'
Pubblica il monorepo Loyalty Hub su GitHub: crea il repository se manca e spinge il
branch corrente. Lo script non crea commit e non modifica l'albero di lavoro.

Uso:
  GITHUB_TOKEN=ghp_xxx scripts/publish-github.sh <owner> <repo> [private|public]

Variabili d'ambiente:
  GITHUB_TOKEN  (obbligatoria) token con scope `repo`
  PUSH_TAGS=1   spinge anche i tag annotati raggiungibili dal branch
  DRY_RUN=1     stampa le operazioni senza eseguirle

Uscite: 0 pubblicato, 1 uso errato o precondizione mancante, 2 errore dell'API GitHub.
USAGE
}

die() {
  printf 'Errore: %s\n' "$1" >&2
  exit "${2:-1}"
}

log() {
  printf '==> %s\n' "$1"
}

# Esegue il comando, oppure lo stampa soltanto se DRY_RUN=1.
run() {
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY_RUN: %s\n' "$*"
    return 0
  fi
  "$@"
}

# curl verso l'API GitHub: stampa il corpo e restituisce il codice HTTP sull'ultima riga.
api() {
  local method="$1" path="$2" body="${3:-}"
  local args=(
    --silent --show-error --location
    --write-out '\n%{http_code}'
    --request "$method"
    --header "Authorization: Bearer ${GITHUB_TOKEN}"
    --header "Accept: application/vnd.github+json"
    --header "X-GitHub-Api-Version: ${API_VERSION}"
  )
  [[ -n "$body" ]] && args+=(--header 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "${API}${path}"
}

# Estrae un campo stringa da una risposta JSON senza dipendere da jq.
json_field() {
  local json="$1" field="$2"
  printf '%s' "$json" | tr ',' '\n' | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n 1
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  local owner="${1:-}" repo="${2:-}" visibility="${3:-private}"
  [[ -n "$owner" && -n "$repo" ]] || { usage >&2; die 'servono <owner> e <repo>'; }
  [[ "$visibility" == "private" || "$visibility" == "public" ]] || die "visibilità non valida: $visibility"
  [[ -n "${GITHUB_TOKEN:-}" ]] || die 'esporta GITHUB_TOKEN con scope repo'
  command -v git >/dev/null || die 'git non disponibile'
  command -v curl >/dev/null || die 'curl non disponibile'

  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die 'esegui lo script dentro il repository'
  git rev-parse HEAD >/dev/null 2>&1 || die 'nessun commit da pubblicare'

  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  [[ "$branch" != "HEAD" ]] || die 'HEAD staccata: fai checkout di un branch prima di pubblicare'

  if [[ -n "$(git status --porcelain)" ]]; then
    printf 'Attenzione: ci sono modifiche non committate, non verranno pubblicate.\n' >&2
  fi

  ensure_repository "$owner" "$repo" "$visibility"
  push_branch "$owner" "$repo" "$branch"

  log "Pubblicato: https://github.com/${owner}/${repo} (branch ${branch})"
}

# Crea il repository se non esiste; è idempotente.
ensure_repository() {
  local owner="$1" repo="$2" visibility="$3"
  local response status

  response="$(api GET "/repos/${owner}/${repo}")"
  status="$(tail -n 1 <<<"$response")"
  if [[ "$status" == "200" ]]; then
    log "Repository ${owner}/${repo} già presente"
    return 0
  fi
  [[ "$status" == "404" ]] || die "lettura di ${owner}/${repo} fallita (HTTP ${status})" 2

  # Un owner che è un'organizzazione usa un endpoint diverso da un utente.
  local endpoint="/user/repos"
  response="$(api GET "/orgs/${owner}")"
  if [[ "$(tail -n 1 <<<"$response")" == "200" ]]; then
    endpoint="/orgs/${owner}/repos"
  fi

  local private="true"
  [[ "$visibility" == "public" ]] && private="false"

  local payload
  payload="$(printf '{"name":"%s","private":%s,"has_wiki":false,"description":"%s"}' \
    "$repo" "$private" 'Loyalty Hub — piattaforma loyalty vendor neutral (Java 25/Spring Boot, Next.js, Payload, EKS)')"

  log "Creazione di ${owner}/${repo} (${visibility})"
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY_RUN: POST %s %s\n' "$endpoint" "$payload"
    return 0
  fi

  response="$(api POST "$endpoint" "$payload")"
  status="$(tail -n 1 <<<"$response")"
  [[ "$status" == "201" ]] || die "creazione fallita (HTTP ${status}): $(json_field "$response" message)" 2
}

# Spinge il branch corrente passando il token a git senza scriverlo nella configurazione.
push_branch() {
  local owner="$1" repo="$2" branch="$3"
  local url="https://github.com/${owner}/${repo}.git"
  # Il credential helper legge il token dall'ambiente: non finisce né in .git/config né in argv.
  local helper='!f() { echo username=x-access-token; echo "password=${GITHUB_TOKEN}"; }; f'
  local args=(-c "credential.helper=${helper}" push "$url" "${branch}:${branch}")
  [[ "${PUSH_TAGS:-0}" == "1" ]] && args+=(--follow-tags)

  log "Push di ${branch} su ${url}"
  run git "${args[@]}"

  # Allinea il remoto locale senza token, così i push successivi usano `git push origin`.
  if ! git remote get-url origin >/dev/null 2>&1; then
    run git remote add origin "$url"
  fi
  run git branch --set-upstream-to "origin/${branch}" "$branch" 2>/dev/null || true
}

main "$@"
