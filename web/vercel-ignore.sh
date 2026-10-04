#!/bin/sh
# Ignored Build Step di Vercel (docs/11 passo 4, HUB-01): uscita 0 = salta la build, 1 = fa la build.
# Sta in uno script perché lo schema di vercel.json ammette un ignoreCommand di al massimo 256 caratteri.
# Vercel lo esegue nella root del progetto (web/) di un clone parziale del repository.
prev="${VERCEL_GIT_PREVIOUS_SHA:-}"
[ -n "$prev" ] || exit 1                                  # nessun deploy precedente: build
[ "$prev" != "${VERCEL_GIT_COMMIT_SHA:-}" ] || exit 1      # Redeploy dello stesso commit (variabili nuove): build
git cat-file -e "$prev^{commit}" 2>/dev/null || exit 1     # commit precedente assente dal clone: build, non errore
git diff --quiet "$prev" HEAD -- . ../seed ../contracts ../registry && exit 0
exit 1
