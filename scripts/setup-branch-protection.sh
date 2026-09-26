#!/usr/bin/env bash
# Applica il ruleset "main-protetto" e le impostazioni del repository (docs/18 §3.13, ADR-041).
# Da eseguire dal proprietario, con `gh` autenticato come admin, DOPO il merge della fetta M8.0.
#
# Uso:
#   scripts/setup-branch-protection.sh                 # applica su peppe-ruv/Loyalty_Sys
#   scripts/setup-branch-protection.sh --dry-run       # stampa il payload senza applicarlo
#   LH_REQUIRED_CHECKS="backend (Java 25)|web (Next.js)|seed|contracts|guard|docs" scripts/setup-branch-protection.sh
#
# Variabili:
#   LH_REPO               owner/repo (default peppe-ruv/Loyalty_Sys)
#   LH_REQUIRED_CHECKS    nomi dei job obbligatori separati da "|" (come appaiono nella scheda Checks)
#   LH_REQUIRED_APPROVALS approvazioni richieste (default 0: un solo maintainer, vedi ADR-041)
#   LH_CODE_OWNER_REVIEW  true|false (default false; true dal secondo maintainer)
set -euo pipefail

DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

REPO="${LH_REPO:-peppe-ruv/Loyalty_Sys}"
CHECKS="${LH_REQUIRED_CHECKS:-backend (Java 25)|web (Next.js)|seed|contracts|guard}"
APPROVALS="${LH_REQUIRED_APPROVALS:-0}"
CODE_OWNERS="${LH_CODE_OWNER_REVIEW:-false}"

checks_json=""
IFS='|' read -ra ARR <<< "$CHECKS"
for c in "${ARR[@]}"; do
  [[ -z "$c" ]] && continue
  checks_json+="{\"context\":\"${c}\"},"
done
checks_json="${checks_json%,}"

payload=$(cat <<JSON
{
  "name": "main-protetto",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [
    { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request" }
  ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "pull_request",
      "parameters": {
        "required_approving_review_count": ${APPROVALS},
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": ${CODE_OWNERS},
        "require_last_push_approval": false,
        "required_review_thread_resolution": true
      } },
    { "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [ ${checks_json} ]
      } }
  ]
}
JSON
)

if $DRY_RUN; then
  echo "$payload"
  exit 0
fi

command -v gh >/dev/null || { echo "Serve la GitHub CLI (gh) autenticata come admin di $REPO" >&2; exit 1; }

existing=$(gh api "repos/$REPO/rulesets" --jq '.[] | select(.name=="main-protetto") | .id' 2>/dev/null || true)
if [[ -n "$existing" ]]; then
  gh api -X PUT "repos/$REPO/rulesets/$existing" --input - <<< "$payload" >/dev/null
  echo "Ruleset main-protetto aggiornato (id $existing)"
else
  gh api -X POST "repos/$REPO/rulesets" --input - <<< "$payload" >/dev/null
  echo "Ruleset main-protetto creato"
fi

# Impostazioni del repository: solo squash, rami cancellati dopo il merge.
gh api -X PATCH "repos/$REPO" \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true -f squash_merge_commit_title=PR_TITLE >/dev/null
echo "Merge solo squash, cancellazione automatica dei rami: attivi"

# Secret scanning e push protection (repository pubblico).
gh api -X PATCH "repos/$REPO" --input - >/dev/null <<'JSON' || echo "Secret scanning: da attivare a mano in Settings → Code security"
{ "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" } } }
JSON

echo "Controlli obbligatori: ${CHECKS//|/, }"
echo "Verifica: un 'git push origin main' deve essere rifiutato; una PR deve mostrare i controlli richiesti."
