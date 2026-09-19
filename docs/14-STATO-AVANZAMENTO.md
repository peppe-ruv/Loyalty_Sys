# 14 — Stato di avanzamento

Checklist **viva**: la aggiorna chi chiude una fetta (persona o agente), nello stesso commit del codice. È la prima cosa da leggere a inizio sessione insieme a `CLAUDE.md`.

**Come si usa** — `[ ]` da fare · `[~]` in corso (aggiungere data e nota) · `[x]` fatto (aggiungere l'hash breve del commit). Una feature si spunta solo se rispetta la *Definizione di fatto* (`CLAUDE.md §6`). Le feature che attraversano più milestone (es. `M1→M6`) si spuntano alla **prima** milestone e si annotano gli arricchimenti successivi nel registro in fondo.

## Quadro

| Milestone | Stato | Inizio | Fine | Demo online aggiornata | Note |
|---|---|---|---|---|---|
| M0 — Fondamenta | in corso | 2026-09-19 | | ☐ | M0.1 chiusa; monorepo compila |
| M1 — Core loop e primo deploy | da iniziare | | | ☐ | |
| M2 — Visibilità | da iniziare | | | ☐ | |
| M3 — Punti adulti | da iniziare | | | ☐ | |
| M4 — Premi | da iniziare | | | ☐ | |
| M5 — Gioco | da iniziare | | | ☐ | |
| M6 — Contenuti | da iniziare | | | ☐ | |
| M7 — Governance | da iniziare | | | ☐ | |

**Prossima fetta da lavorare:** `M0.6`

**Ambiente demo**

| Risorsa | Stato | Riferimento (URL/ID, mai segreti) |
|---|---|---|
| Repository GitHub | ✅ | branch `claude/istruzioni-dwhe86` |
| Kafka locale (compose) | ✅ | `deploy/docker-compose.yml` (KRaft); topic dal profilo `local` |
| Kafka Aiven (5 topic) | ☐ | |
| Progetto Neon | ☐ | |
| Blueprint Render | ☐ | |
| Progetto Vercel | ☐ | |

## M0 — Fondamenta

**Fette** (`docs/12 §3`)

- [x] `M0.1` — parent POM (Java 25, Boot 4.1.0), wrapper, `.editorconfig`/`.gitignore`/`.dockerignore`, struttura cartelle `CLAUDE.md §3`; `./mvnw verify` verde (`86c6666`)
- [x] `M0.2` — `libs/lh-common`: envelope CloudEvents + factory, outbox (writer/relay/cleanup), inbox (idempotenza + router), Kafka (PLAINTEXT/SSL_PEM/SASL_SSL, error handler → DLQ), errori RFC 9457, actor `X-LH-Actor` + `@RequiresRole`, approvazioni, audit, SeedLoader + SeedDates, ids/time, `V0__lh_common.sql`, auto-config; test verdi (24 unit + 5 IT con EmbeddedKafka+Zonky). SPEC-GAP Q-40 (`4f9ddaa`)
- [x] `M0.3` — `contracts/events/`: `envelope.schema.json` + 10 schemi `data` degli eventi di M1 (action/effect/fact/audit, JSON Schema 2020-12) + un esempio valido per type; test di contratto `ContractsTest` (envelope + data + coerenza `dataschema` + audit con `lhactor`). SPEC-GAP Q-42 (`10db769`)
- [x] `M0.4` — `deploy/docker-compose.yml`: Kafka KRaft + Postgres 17 + Kafka UI (infra di default) e 8 servizi + web sotto `--profile all`; limiti mem/cpu. I 5 topic li crea il profilo `local` (bean `NewTopic`, 2 partizioni), verificato da `LocalTopicsIT` (Kafka in-JVM). Compose validato con `docker compose config`. (`8d4b60d`)
- [x] `M0.5` — servizio archetipo `ingestion-service`: `POST /v1/events` → dedup `(source,id)` → arricchimento `lh*` → outbox su `lh.actions.v1` (`202 ACCEPTED`/`DUPLICATE`), consumer di prova, Flyway (`V0`+`V1 inbound_event`), Actuator, Dockerfile multi-stage. IT su Spring Boot + EmbeddedKafka + Zonky (accettato/duplicato/400). **Migrazione a Jackson 3** (default di Boot 4) in lh-common + servizio; aggiunte le auto-config Boot 4 mancanti (`spring-boot-flyway`, `@EnableKafka`, `KafkaAdmin`). Q-43 (`ebb8dcb`)
- [ ] `M0.6`
- [ ] `M0.7`

**Feature** (`docs/02`)

- [ ] `F-DEMO-01` Demo Hub (P0) — _M0/M1_
- [ ] `F-DEMO-02` Cambio persona (P0)

**Accettazione M0** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate

## M1 — Core loop e primo deploy

**Fette** (`docs/12 §3`)

- [ ] `M1.1`
- [ ] `M1.2`
- [ ] `M1.3`
- [ ] `M1.4`
- [ ] `M1.5`
- [ ] `M1.6`
- [ ] `M1.7`
- [ ] `M1.8`

**Feature** (`docs/02`)

- [ ] `F-ING-01` Ricezione CloudEvents (P0)
- [ ] `F-ING-02` Deduplica (P0)
- [ ] `F-ING-03` Risoluzione membro (P0)
- [ ] `F-ING-05` Registro fonti (P0)
- [ ] `F-ING-06` Tipi azione e schemi (P0) — _M1 (custom: M6)_
- [ ] `F-ING-09` Monitor ingressi (P0)
- [ ] `F-MBR-01` Anagrafica membro (P0)
- [ ] `F-MBR-02` Scheda 360° (P0) — _M1→M6_
- [ ] `F-MBR-04` Stati del membro (P0)
- [ ] `F-CMP-01` CRUD campagne (P0)
- [ ] `F-CMP-02` Ciclo di vita (P0)
- [ ] `F-CMP-03` Costruttore condizioni (P0)
- [ ] `F-CMP-04` Effetti (P0) — _M1 (punti) → M5_
- [ ] `F-CMP-05` Limiti (P0)
- [ ] `F-CMP-06` Pubblico (P0 tier · P1 segmenti) — _M1 / M6_
- [ ] `F-CMP-08` Simulazione (P0)
- [ ] `F-CMP-09` Registro valutazioni (P0)
- [ ] `F-CMP-11` "Come guadagnare" (P0)
- [ ] `F-WAL-01` Doppia valuta (P0)
- [ ] `F-WAL-02` Libro mastro (P0)
- [ ] `F-DEMO-03` Simulatore eventi (P0)
- [ ] `F-DEMO-05` Reset dati (P0)
- [ ] `F-DEMO-07` Keep-alive gentile (P0)

**Accettazione M1** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M2 — Visibilità

**Fette** (`docs/12 §3`)

- [ ] `M2.1`
- [ ] `M2.2`
- [ ] `M2.3`
- [ ] `M2.4`
- [ ] `M2.5`
- [ ] `M2.6`
- [ ] `M2.7`
- [ ] `M2.8`

**Feature** (`docs/02`)

- [ ] `F-CMP-10` Statistiche campagna (P1)
- [ ] `F-AUD-01` Audit log (P0)
- [ ] `F-INS-01` Flusso eventi live (P0)
- [ ] `F-INS-02` Tracciato (P0)
- [ ] `F-INS-03` KPI e serie storiche (P0)
- [ ] `F-INS-04` Storico sintetico (P0)
- [ ] `F-INS-06` Stato pipeline (P1)
- [ ] `F-DEMO-04` Scenari guidati (P0)

**Accettazione M2** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M3 — Punti adulti

**Fette** (`docs/12 §3`)

- [ ] `M3.1`
- [ ] `M3.2`
- [ ] `M3.3`
- [ ] `M3.4`
- [ ] `M3.5`
- [ ] `M3.6`
- [ ] `M3.7`
- [ ] `M3.8`
- [ ] `M3.9`

**Feature** (`docs/02`)

- [ ] `F-ING-07` Transazioni d'acquisto (P1)
- [ ] `F-ING-08` Ponte azioni interne (P0)
- [ ] `F-CMP-07` Cumulabilità (P0)
- [ ] `F-WAL-03` Lotti e scadenza (P0)
- [ ] `F-WAL-05` Punti in attesa (P1)
- [ ] `F-WAL-06` Scadenza (P0)
- [ ] `F-WAL-07` Rettifiche manuali (P0)
- [ ] `F-WAL-09` Passività (P1)
- [ ] `F-TIER-01` Definizione livelli (P0)
- [ ] `F-TIER-02` Salita immediata (P0)
- [ ] `F-TIER-03` Moltiplicatore di livello (P0)
- [ ] `F-TIER-04` Chiusura edizione con discesa morbida (P0)
- [ ] `F-TIER-05` Anteprima chiusura (P0)
- [ ] `F-TIER-06` Storico livelli (P1)
- [ ] `F-DEMO-06` Job su richiesta (P0)

**Accettazione M3** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M4 — Premi

**Fette** (`docs/12 §3`)

- [ ] `M4.1`
- [ ] `M4.2`
- [ ] `M4.3`
- [ ] `M4.4`
- [ ] `M4.5`
- [ ] `M4.6`

**Feature** (`docs/02`)

- [ ] `F-WAL-04` Spesa FIFO (P0)
- [ ] `F-WAL-08` Saga di spesa (P0)
- [ ] `F-RWD-01` Catalogo premi (P0)
- [ ] `F-RWD-02` Fasce premi (P0)
- [ ] `F-RWD-03` Disponibilità (P0)
- [ ] `F-RWD-04` Visibilità (P0 tier · P1 segmenti) — _M4 / M6_
- [ ] `F-RWD-05` Richiesta premio (P0)
- [ ] `F-RWD-06` Evasione (P0)
- [ ] `F-RWD-07` Annullamento con rimborso (P1)
- [ ] `F-RWD-08` Ciclo di vita premio (P0) — _M4 (M7 approv.)_
- [ ] `F-CPN-01` Pool di coupon (P0)
- [ ] `F-CPN-02` Emissione (P0) — _M4 / M5_
- [ ] `F-CPN-03` Utilizzo (P1)

**Accettazione M4** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M5 — Gioco

**Fette** (`docs/12 §3`)

- [ ] `M5.1`
- [ ] `M5.2`
- [ ] `M5.3`
- [ ] `M5.4`
- [ ] `M5.5`
- [ ] `M5.6`
- [ ] `M5.7`

**Feature** (`docs/02`)

- [ ] `F-MBR-06` Registrazione dal portale (P1)
- [ ] `F-MBR-07` Completamento profilo (P1)
- [ ] `F-CMP-12` Campagne di sistema (P0)
- [ ] `F-IW-01` Concorso (P0)
- [ ] `F-IW-02` Montepremi (P0)
- [ ] `F-IW-03` Istanti vincenti pre-generati (P0)
- [ ] `F-IW-04` Giocata (P0)
- [ ] `F-IW-05` Crediti di gioco (P0)
- [ ] `F-IW-06` Vincita come azione interna (P0)
- [ ] `F-IW-07` Vincitori e report (P0)
- [ ] `F-IW-08` Aiuto demo (P0)
- [ ] `F-ACH-01` Obiettivi (P0)
- [ ] `F-ACH-02` Progresso (P0)
- [ ] `F-ACH-03` Badge (P0)
- [ ] `F-LDB-01` Classifiche (P1)
- [ ] `F-REF-01` Codice amico (P1)
- [ ] `F-REF-02` Completamento referral (P1)

**Accettazione M5** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M6 — Contenuti

**Fette** (`docs/12 §3`)

- [ ] `M6.0`
- [ ] `M6.1`
- [ ] `M6.2`
- [ ] `M6.3`
- [ ] `M6.4`
- [ ] `M6.5`
- [ ] `M6.6`
- [ ] `M6.7`

**Feature** (`docs/02`)

- [ ] `F-MBR-03` Attributi custom ed etichette (P1)
- [ ] `F-SEG-01` Segmenti statici (P1)
- [ ] `F-SEG-02` Segmenti dinamici (P1)
- [ ] `F-SEG-03` Ricalcolo e fatti (P1)
- [ ] `F-CNT-01` Card (P0)
- [ ] `F-CNT-02` Pop-up (P0)
- [ ] `F-CNT-03` Card vincita (P0)
- [ ] `F-CNT-04` Anteprima (P1)
- [ ] `F-MSG-01` Inbox in-app (P0)
- [ ] `F-MSG-02` Template (P0)
- [ ] `F-THM-01` Tema del portale (P1)

**Accettazione M6** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M7 — Governance

**Fette** (`docs/12 §3`)

- [ ] `M7.1`
- [ ] `M7.2`
- [ ] `M7.3`
- [ ] `M7.4`
- [ ] `M7.5`
- [ ] `M7.6`

**Feature** (`docs/02`)

- [ ] `F-ING-04` Eventi non abbinati (P1)
- [ ] `F-MBR-05` Anonimizzazione (P1)
- [ ] `F-CMP-13` Duplica campagna (P1)
- [ ] `F-WBH-01` Webhook in uscita (P1)
- [ ] `F-APR-01` Workflow di approvazione (P0)
- [ ] `F-APR-02` Policy (P1)
- [ ] `F-APR-03` Casella approvazioni (P0)
- [ ] `F-INS-05` DLQ (P1)

**Accettazione M7** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## Fuori PoC (P2, solo predisposizione)

- `F-ING-10` Invio batch
- `F-MBR-08` Compleanno
- `F-CMP-14` Storno su reso

## Registro delle sessioni

| Data | Fetta | Esito | Commit | Domande aperte create | Note per la prossima sessione |
|---|---|---|---|---|---|
| 2026-09-19 | M0.1 | ✅ `./mvnw verify` verde (9 moduli) | `86c6666` | Q-01→Apache-2.0, Q-02→loyalty-hub (confermate dall'owner) | M0.2 `libs/lh-common`: test prima (Testcontainers Kafka+Postgres) per outbox/idempotenza/DLQ, poi implementazione. Nota ambiente: JDK locale 21; `verify` di M0.1 è verde perché i moduli sono vuoti, ma da M0.2 (codice reale) serve JDK 25 in CI/deploy. |
| 2026-09-19 | M0.2 | ✅ `./mvnw verify` verde (24 unit + 5 IT) | `4f9ddaa` | Q-40 (Testcontainers→EmbeddedKafka+Zonky, pull immagini Docker negato dal proxy), Q-41 (JDK 25 provvisto in ambiente; fissare 25 in CI) | M0.3 `contracts/events/`: envelope + schemi ed esempi degli eventi di M1 (docs/05) + test di contratto. Ambiente: JDK 25 in `/opt/jdk-25` (estratto da `mcr.microsoft.com/openjdk/jdk:25-ubuntu`); export in `~/.bashrc`. I test d'integrazione usano EmbeddedKafka + Zonky (niente Docker). |
| 2026-09-19 | M0.3 | ✅ `./mvnw verify` verde (26 unit + 5 IT) | `10db769` | Q-42 (esempi in `contracts/events/examples/` per `CLAUDE.md §3`, non `contracts/examples/` di docs/05 §9) | M0.4 `deploy/docker-compose.yml` + profilo `local` che crea i 5 topic (docs/11 §9). Nota: i contratti sono sul classpath di test di `lh-common` via `<testResource>`; da M1 ogni produttore aggiunge un test che valida l'evento realmente prodotto (docs/05 §9). |
| 2026-09-19 | M0.4 | ✅ `./mvnw verify` verde (26 unit + 6 IT); `docker compose config` OK | `8d4b60d` | — | M0.5 servizio **archetipo** = `ingestion-service` ridotto: `POST /v1/events` → outbox → `lh.actions.v1`, un consumer di prova, Flyway, Actuator, Dockerfile (docs/servizi/ingestion-service.md). Il compose ha già i riferimenti al Dockerfile dei servizi (profilo `all`). Immagini Docker non pull-abili qui: il boot dell'archetipo si verifica con Spring in-JVM (EmbeddedKafka+Zonky). |
| 2026-09-19 | M0.5 | ✅ `./mvnw verify` verde (reattore intero: 26 unit lh-common + 6 IT + 3 IT ingestion) | `ebb8dcb` | Q-43 (Jackson 3 come default runtime, Jackson 2 confinato ai test di contratto) | M0.6 `web/`: Next.js, token, shell delle tre aree, proxy `/api/lh`, cookie persona, `/api/demo/status|wake`, HUB-01 con pannello stato (docs/07). **Lezioni Boot 4**: auto-config modularizzate (serve `spring-boot-<tech>`); `@KafkaListener` via `@EnableKafka`; `TestRestTemplate` rimosso (usare `RestClient`); `@EmbeddedKafka` senza `kraft`; fat jar con classifier `boot` (i test trovano la @SpringBootConfiguration); Flyway usa il DataSource dell'app. Fix Vercel: placeholder statico `web/playground/` per sbloccare il progetto `loyalty-hub-playground` (il mio token è 403 sul team `poc-22b1`). |
