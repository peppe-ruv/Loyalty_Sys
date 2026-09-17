#!/bin/bash
# Prepara la sessione di Claude Code sul web: dipendenze installate, così lint, tipi,
# test e build funzionano subito. Idempotente e non interattivo.
set -euo pipefail

# In locale le dipendenze le gestisce chi sviluppa.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

# `npm install` riusa la cache del container fra una sessione e l'altra; il lockfile
# resta la fonte di verità perché `npm ci` gira comunque in CI.
npm install --no-audit --no-fund
