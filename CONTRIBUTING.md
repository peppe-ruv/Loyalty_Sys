# Contribuire · Contributing

> 🇮🇹 Come proporre una modifica e cosa serve perché venga accettata.
> 🇬🇧 How to propose a change and what it takes to get it accepted.

---

## 1. Prima di scrivere codice · Before writing code

1. Leggi [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §1 (principi) e [`CLAUDE.md`](CLAUDE.md) §3 (regole non
   negoziabili): la maggior parte delle modifiche rifiutate viola uno di quei punti.
2. Apri una issue che descriva **il problema**, non la soluzione. Se esiste un requisito (`RF-nn`), citalo.
3. Se la modifica cambia una scelta strutturale, serve un nuovo ADR: si aggiunge, non si riscrive un ADR approvato.

**EN**

1. Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §1 (principles) and [`CLAUDE.md`](CLAUDE.md) §3 (invariants):
   most rejected changes break one of those points.
2. Open an issue describing **the problem**, not the solution. If a requirement (`RF-nn`) exists, cite it.
3. If the change alters a structural choice, a new ADR is required: add one, never rewrite an approved ADR.

---

## 2. Rami e commit · Branches and commits

| Elemento · Item | Convenzione · Convention |
| --- | --- |
| Ramo · Branch | `feat/<ambito>-<breve>`, `fix/<ambito>-<breve>`, `docs/<breve>`, `chore/<breve>` |
| Titolo del commit · Commit subject | Imperativo, ≤ 72 caratteri, ambito in testa: `ledger: blocco unità reversibile` |
| Corpo · Body | Elenco per componente; cita i requisiti toccati (`RF-88`) · bullet list per component; cite touched requirements |
| Granularità · Granularity | Un commit = una modifica coerente; niente commit "vari" · one commit = one coherent change; no "misc" commits |

---

## 3. Lista di controllo della pull request · Pull request checklist

- [ ] `make build && make test` passano in locale · pass locally
- [ ] C'è un test che copre il comportamento nuovo o riproduce il bug corretto · a test covers the new behaviour or reproduces the fixed bug
- [ ] Nessun dato personale in log, metriche, tracce, eventi, warehouse · no personal data in logs, metrics, traces, events, warehouse
- [ ] Nessun parametro di business aggiunto a file di configurazione tecnica · no business parameter added to technical configuration files
- [ ] Nessuna scrittura diretta su tabelle di un altro servizio · no direct writes to another service's tables
- [ ] Ogni scrittura nuova ha una chiave di idempotenza · every new write has an idempotency key
- [ ] Contratti aggiornati se cambiano API o eventi · contracts updated if APIs or events change
- [ ] Documentazione aggiornata nello stesso commit (funzionalità, architettura, ADR, runbook) · documentation updated in the same commit
- [ ] Migrazioni nuove, mai modifiche a migrazioni già rilasciate · new migrations only, never edits to released ones
- [ ] Testi rivolti all'utente scritti in italiano **e** inglese · user-facing copy written in Italian **and** English

---

## 4. Revisione · Review

Chi revisiona guarda, in ordine: (1) il contratto (evento o API) prima dell'implementazione; (2) le regole non
negoziabili; (3) i test; (4) la leggibilità del dominio; (5) la documentazione. Un'obiezione sul contratto blocca;
un'obiezione di stile è un suggerimento.
**EN** — Reviewers look, in order, at: (1) the contract (event or API) before the implementation; (2) the invariants;
(3) the tests; (4) domain readability; (5) documentation. A contract objection blocks; a style objection is a
suggestion.

---

## 5. Cosa non viene accettato · What will not be accepted

| Proposta · Proposal | Perché no · Why not |
| --- | --- |
| Aggiornare un saldo senza passare dal registro · updating a balance outside the ledger | Il registro è l'unica verità e l'unico audit · the ledger is the only truth and the only audit |
| Leggere le tabelle di un altro servizio · reading another service's tables | Rompe i confini e blocca l'evoluzione indipendente · breaks boundaries and independent evolution |
| Parametri di business in file di configurazione · business parameters in configuration files | Ogni cambio richiederebbe un rilascio · every change would need a release |
| Un'azione contrattuale resa arbitrabile nel codice · making a contractual action arbitrable in code | È una scelta di policy, non di implementazione · that is a policy choice, not an implementation one |
| Una previsione che modifica punti o status · a prediction that changes points or status | Confine invalicabile del livello decisionale · hard boundary of the decision layer |
| Dati personali in eventi, metriche o warehouse · personal data in events, metrics or the warehouse | Vincolo di privacy della piattaforma · platform privacy constraint |
| Modifica di un ADR approvato · editing an approved ADR | La storia delle decisioni non si riscrive: si aggiunge un ADR · decision history is appended, not rewritten |
