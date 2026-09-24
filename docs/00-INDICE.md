# 00 — Indice delle specifiche

Specifiche del PoC **Loyalty Hub**, scritte per essere eseguite da Claude Code. Ogni documento ha un ruolo-autore e un ambito preciso; nessuna informazione è duplicata: dove serve, si rimanda per ID.

## Documenti

| # | Documento | Ruolo-autore | Contiene |
|---|---|---|---|
| 01 | [Visione e scope](01-VISIONE-E-SCOPE.md) | Product | obiettivo del PoC, in/out scope, personas, criteri di successo |
| 02 | [Catalogo funzionale](02-CATALOGO-FUNZIONALE.md) | Funzionale | tutte le feature con ID, priorità, servizio proprietario, superfici UI, milestone |
| 03 | [Modello di dominio](03-MODELLO-DI-DOMINIO.md) | Funzionale / Domain | entità, invarianti, macchine a stati, algoritmi (punti, tier, instant win, regole) |
| 04 | [Architettura](04-ARCHITETTURA.md) | Architetto | servizi e confini, flussi, pattern di affidabilità, stack, repo |
| 05 | [Eventi e topic](05-EVENTI-E-TOPIC.md) | Architetto integrazione | envelope CloudEvents, 5 topic, catalogo tipi evento con esempi |
| 06 | [Convenzioni backend](06-CONVENZIONI-BACKEND.md) | Backend lead | lh-common, API, errori, DB, Kafka, profili, test |
| — | [servizi/*.md](servizi/) | Backend | una scheda per microservizio: tabelle, API, eventi, regole |
| 07 | [Frontend — fondamenta](07-FRONTEND-FONDAMENTA.md) | Frontend lead / Design | stack, struttura, token visivi, stati, proxy, realtime, Demo Hub |
| 08 | [Frontend — backoffice](08-FRONTEND-BACKOFFICE.md) | Frontend / UX | schermate `BO-xx`: layout, azioni, stati, permessi per ruolo |
| 09 | [Frontend — portale](09-FRONTEND-PORTALE.md) | Frontend / UX | schermate `PT-xx` del portale membri |
| 10 | [Dati demo](10-DATI-DEMO.md) | Funzionale / QA | personas, membri, campagne, premi, concorsi, scenari guidati |
| 11 | [Deploy a costo zero](11-DEPLOY-COSTO-ZERO.md) | DevOps | Vercel + Render + Neon + Aiven, env, Docker, CI, limiti |
| 12 | [Piano di sviluppo](12-PIANO-DI-SVILUPPO.md) | Delivery | milestone M0…M7 a fette verticali, criteri di accettazione, prompt |
| 13 | [Registro decisioni](13-REGISTRO-DECISIONI.md) | Architetto | ADR compatte |
| 14 | [Stato avanzamento](14-STATO-AVANZAMENTO.md) | Delivery | checklist viva, aggiornata da Claude Code |
| 15 | [Domande aperte](15-DOMANDE-APERTE.md) | Tutti | decisioni in sospeso e SPEC-GAP |
| 16 | [Testbook funzionale](16-TESTBOOK-FUNZIONALE.md) | Tutti | casi di prova per regola × domini dei valori, oracolo = specifica |

Alla radice: [`CLAUDE.md`](../CLAUDE.md) (regole per l'agente), [`README.md`](../README.md), [`COME-PARTIRE.md`](../COME-PARTIRE.md) (primi passi e primo prompt per Claude Code).

Ordine di lettura per chi parte da zero: 01 → 04 → 05 → 02 → 12. Il resto si legge al bisogno (vedi mappa in `CLAUDE.md`).

## Convenzioni sugli ID

| Prefisso | Significato | Esempio |
|---|---|---|
| `F-<AREA>-nn` | feature del catalogo | `F-WAL-04` |
| `BO-nn` / `PT-nn` / `HUB-nn` | schermata backoffice / portale / hub demo | `BO-12` |
| `EVT-<famiglia>-nn` | tipo evento | `EVT-FACT-07` |
| `ADR-nnn` | decisione architetturale | `ADR-004` |
| `M0…M7` | milestone | `M3` |
| `SCN-…` | scenario demo | `SCN-TIER-UP` |
| `Q-nn` | domanda aperta / SPEC-GAP | `Q-03` |

Aree feature: `ING` ingresso, `MBR` membri, `SEG` segmenti, `CMP` campagne, `WAL` wallet/punti, `TIER`, `RWD` premi, `CPN` coupon, `IW` instant win, `ACH` obiettivi/badge, `LDB` classifiche, `REF` referral, `CNT` contenuti, `MSG` messaggi, `THM` tema, `WBH` webhook, `APR` approvazioni, `AUD` audit, `INS` insight, `DEMO`.

Priorità: **P0** = indispensabile al PoC · **P1** = affinamento subito dopo · **P2** = prodotto target (fuori PoC, solo predisposizione).

## Glossario (termine UI → identificatore nel codice)

| Italiano (UI, documenti) | Inglese (codice, API, eventi) | Nota |
|---|---|---|
| Membro | `member` | iscritto al programma; ID `MBR-000123` |
| Azione premiante | `action` | qualcosa che il membro ha fatto o che gli è accaduto e che può essere premiato |
| Fonte | `source` | sistema che invia azioni (`crm`, `app`, `ecommerce`, `billing`, `partner`, `internal`) |
| Campagna | `campaign` | regola: trigger + pubblico + condizioni + effetti + limiti |
| Effetto | `effect` | decisione del motore (assegna punti, giocate, coupon, badge, messaggio) |
| Fatto | `fact` | cambiamento di stato avvenuto in un servizio |
| Punti premio | `PTS` | valuta spendibile |
| Punti status | `STS` | valuta non spendibile, determina il tier |
| Lotto punti | `points_lot` | quantità guadagnata con propria scadenza (consumo FIFO) |
| Livello | `tier` | BASE, SILVER, GOLD, PLATINUM |
| Edizione | `edition` | anno di programma (periodo di valutazione dei tier) |
| Premio | `reward` | voce di catalogo |
| Fascia premi | `reward_band` | soglia punti che raggruppa i premi |
| Richiesta premio | `redemption` | riscatto di un premio |
| Concorso | `contest` | instant win |
| Istante vincente | `winning_instant` | timestamp pre-generato associato a un premio in palio |
| Giocata | `play` / `play_grant` | tentativo / credito di gioco |
| Obiettivo | `achievement` | traguardo con progresso; al completamento può dare un badge |
| Classifica | `leaderboard` | ranking per periodo |
| Card / Pop-up / Banner | `content_item` | contenuti CMS del portale |
| Persona | `actor` (BO) / `member` (portale) | identità fittizia selezionabile, sostituisce il login |
