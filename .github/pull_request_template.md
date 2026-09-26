## Cosa cambia

<!-- fetta, ID F2-*/ADR-*/docs/18 §..., una riga -->

## Definizione di fatto (CLAUDE.md §6 e §7)

- [ ] Test verdi (`./mvnw verify` e/o `pnpm test`)
- [ ] OpenAPI rigenerata dove tocca un endpoint (`contracts/api/`), `check-contracts` verde
- [ ] `pnpm registry:build` senza drift se tocca un blocco/tipo del Registry
- [ ] Stati loading/empty/error/degraded per le schermate toccate; axe verde sulla matrice `e2e-pr`
- [ ] Messaggi in entrambe le lingue (da M11)
- [ ] Nessun campo `pii:true` aggiunto a un evento pubblicato
- [ ] Migrazioni expand/contract; la versione precedente dei consumer resta funzionante
- [ ] Pagina Mintlify del concetto toccato aggiornata con almeno un diagramma Mermaid conforme (accTitle/accDescr), verificato con `node scripts/check-mermaid.mjs`; job `docs` verde
- [ ] Sicurezza (regole 18–21): nuovo endpoint con `@RequiresRole` o `@PublicEndpoint` motivato; `memberId` nel portale solo da `MemberPrincipal`; SQL solo costante o dal builder `SqlWhere`/`SqlOrder`; nuovi `type` evento in `contracts/events/producers.yaml`; nessuna nuova scrittura di configurazione senza voce in `audit_entry`
- [ ] Governo e conformità (regola 22): nessuna auto-approvazione; impostazioni nuove che in `enterprise` rifiutano l'avvio se insicure; `docs/compliance/iso27001-annex-a.md` aggiornato con i controlli toccati
- [ ] `docs/14` aggiornato con la fetta e il numero di questa PR; eventuali `SPEC-GAP: Q-nnn` registrati in `docs/15`

## Note per il revisore

<!-- decisioni prese, alternative scartate, cosa NON è in questa PR -->
