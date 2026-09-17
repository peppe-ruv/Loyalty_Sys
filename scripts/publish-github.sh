#!/usr/bin/env bash
# Pubblica il monorepo loyalty-hub in un nuovo repository GitHub.
# Uso:   GITHUB_TOKEN=ghp_xxx ./scripts/publish-github.sh <owner> <repo> [private|public]
# Esegui dalla radice del monorepo (dove c'è il commit 0.5.0 / b564707) DOPO aver copiato dentro
# il contenuto di questo bundle (docs/, web/backoffice-design-system/, scripts/).
set -euo pipefail

OWNER="${1:?owner o organizzazione GitHub}"
REPO="${2:?nome del repository}"
VISIBILITY="${3:-private}"
: "${GITHUB_TOKEN:?esporta GITHUB_TOKEN con scope repo}"

API="https://api.github.com"
HDR=(-H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json")

# 1. Crea il repository (org o utente), senza fallire se esiste già
PRIVATE=$([ "$VISIBILITY" = "private" ] && echo true || echo false)
if curl -fsS "${HDR[@]}" "$API/orgs/$OWNER" >/dev/null 2>&1; then
  ENDPOINT="$API/orgs/$OWNER/repos"
else
  ENDPOINT="$API/user/repos"
fi
curl -sS "${HDR[@]}" -X POST "$ENDPOINT" \
  -d "{\"name\":\"$REPO\",\"private\":$PRIVATE,\"description\":\"Loyalty Hub — piattaforma loyalty vendor neutral (Java 25/Spring Boot, Next.js, Payload, EKS)\",\"has_wiki\":false}" \
  | grep -E '"full_name"|"message"' || true

# 2. Inizializza git se serve, commit del bundle UX, push
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || git init -b main
git add docs web/backoffice-design-system scripts CHANGELOG.md 2>/dev/null || git add -A
git commit -m "docs(ux): linee guida UX backoffice (LG-01..48), ADR-026, design system, RF-137..142 — 0.6.0" || true
git tag -f v0.6.0
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/$OWNER/$REPO.git"
git push -u origin main --tags
git remote set-url origin "https://github.com/$OWNER/$REPO.git"   # non lasciare il token nella config

echo "Pubblicato: https://github.com/$OWNER/$REPO"
