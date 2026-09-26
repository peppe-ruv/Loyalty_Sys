# CLAUDE.md — Loyalty Hub

Piattaforma loyalty **open source ed event-driven**: microservizi Spring Boot + topic Kafka, frontend Next.js (backoffice + portale membri). Questo file è il contratto operativo per Claude Code.

> **Il codice segue le specifiche in `docs/`, non il contrario.** Se una specifica manca o è contraddittoria: non inventare, segui la procedura "Fermati e chiedi" in fondo.

## 1. Regole d'oro

1. **Spec-first.** Ogni modifica cita gli ID che implementa (`F-…`, `BO-…`, `PT-…`, `EVT-…`, `ADR-…`) nel messaggio di commit.
2. **Un solo proprietario per entità.** Ogni entità vive in un solo servizio (vedi `docs/04`). Il backoffice è un *client* delle API dei servizi: niente CMS o collezioni parallele. **Nessuna entità senza lettore**: se crei un'entità configurabile deve esistere chi la consuma (motore, portale o report).
3. **Nessuna chiamata sincrona tra servizi.** I servizi comunicano solo via Kafka (outbox + consumer idempotenti). Le chiamate HTTP arrivano solo dal frontend (via proxy Next.js) o da fonti esterne.
4. **Contratti evento immutabili.** I tipi evento sono quelli di `docs/05` e `contracts/events/`. Aggiungere campi opzionali è lecito; rinominare/rimuovere no (serve nuova versione + ADR).
5. **5 topic, non uno di più** (limite del Kafka gratuito, ADR-004). Nuovi flussi = nuovi `type` su topic esistenti.
6. **Niente login nel PoC.** Identità simulata con header `X-LH-Actor` (backoffice) e `memberId` esplicito (portale). Non introdurre Spring Security, sessioni o JWT.
7. **Neutralità.** Nessun riferimento ad aziende reali in codice, seed, testi, commit. Package `io.loyaltyhub`, URN `urn:loyaltyhub:*`, brand demo fittizio "Club Aurora".
8. **Costo zero.** Non aggiungere componenti infrastrutturali (Redis, registry, gateway, broker aggiuntivi…) senza ADR approvata. Ogni servizio deve girare in 512 MB RAM / 0.1 CPU.
9. **Dati demo = cittadini di prima classe.** `seed/` è l'unica fonte dei dati fittizi; i servizi la caricano con profilo `demo`. Ogni schermata deve essere "piena" a demo appena accesa.

> **Fase 2.** Le regole 5, 6 e 8 valgono nel profilo `demo`; nel profilo `enterprise` vedi §7 (regole 5-bis, 6-bis, 8-bis e 10–23). Da M8.0 si lavora solo per pull request (regola 16).

## 2. Mappa di lettura (leggi solo ciò che serve al task)

| Se devi… | Leggi |
|---|---|
| Capire cosa costruire e cosa no | `docs/01-VISIONE-E-SCOPE.md`, `docs/02-CATALOGO-FUNZIONALE.md` |
| Capire regole di dominio (punti, tier, concorsi…) | `docs/03-MODELLO-DI-DOMINIO.md` |
| Toccare confini tra servizi, topic, pattern | `docs/04-ARCHITETTURA.md`, `docs/05-EVENTI-E-TOPIC.md` |
| Scrivere codice backend | `docs/06-CONVENZIONI-BACKEND.md` + `docs/servizi/<servizio>.md` |
| Scrivere codice frontend | `docs/07-FRONTEND-FONDAMENTA.md` + `docs/08` (backoffice) o `docs/09` (portale) |
| Creare/modificare dati fittizi | `docs/10-DATI-DEMO.md` |
| Deploy, env, Docker, CI | `docs/11-DEPLOY-COSTO-ZERO.md` |
| Sapere cosa fare adesso | `docs/12-PIANO-DI-SVILUPPO.md` + `docs/14-STATO-AVANZAMENTO.md` |
| Capire il perché di una scelta | `docs/13-REGISTRO-DECISIONI.md` |
| Lavorare una fetta di Fase 2 (M8–M15) | `docs/18-FASE-2.md` (§3 architettura, §4 catalogo `F2-*`, §5 schermate, §6 milestone) + `docs/prompts/fase2-kickoff.md` |
| Toccare blocchi, pagine, meccaniche del portale | `registry/` (Element Registry, da M10.1) + `docs/18 §3.5` |

## 3. Struttura del repo

```
CLAUDE.md  README.md  LICENSE  pom.xml (parent)  render.yaml
docs/                  specifiche (fonte di verità)
contracts/events/      JSON Schema degli eventi + examples/
seed/                  dati demo canonici (JSON), letti dai servizi
libs/lh-common/        starter condiviso (envelope, outbox, idempotenza, errori, actor, seed loader)
services/<nome>/       8 microservizi Spring Boot (un Dockerfile ciascuno)
services/experience-service   ex engagement, composizione versionata (Fase 2, M10.3)
services/assistant-service    agente regolamento (Fase 2, M14.1)
registry/              Element Registry: elements.yaml + JSON Schema (Fase 2, M10.1)
contracts/api/         OpenAPI generata e verificata dei servizi (Fase 2, M8.8)
cms/                   estensioni e snapshot Directus generato (Fase 2, M10.2)
widgets/               kit di widget incorporabili (Fase 2, M10.6)
e2e/                   Playwright, journey, invarianti, k6 (Fase 2, M9)
web/                   Next.js: /(hub), /backoffice, /portal, /api
deploy/                docker-compose.yml, script, note Kafka; hub/ (demo ospitata)
  deploy/image/        immagine unica a ruoli (Fase 2, M8.1)
  deploy/helm/         chart Helm loyaltyhub (Fase 2, M8.3)
  deploy/compose/      compose di riferimento (Fase 2, M8.3)
  deploy/idp/          realm Keycloak as code (Fase 2, M8.2)
scripts/               smoke.sh, check-seed.mjs, check-contracts.mjs, testbook.sh, check-adr-append-only.mjs, check-mermaid.mjs, setup-branch-protection.sh
.github/               workflows/ (ci.yml con il job guard), CODEOWNERS, pull_request_template.md, dependabot.yml
```

## 4. Comandi

```bash
# infrastruttura locale (Kafka KRaft + Postgres + Kafka UI)
docker compose -f deploy/docker-compose.yml up -d kafka postgres kafka-ui
# build + test backend
./mvnw -q verify
# un servizio in locale (profili: demo = seed, local = endpoint locali)
./mvnw -pl services/wallet-service -am spring-boot:run -Dspring-boot.run.profiles=demo,local
# tutto lo stack in container
docker compose -f deploy/docker-compose.yml --profile all up --build
# frontend
cd web && pnpm i && pnpm dev          # http://localhost:3000
pnpm lint && pnpm typecheck && pnpm test
# verifiche trasversali
node scripts/check-seed.mjs           # coerenza dei seed tra servizi
bash scripts/smoke.sh                 # E2E: azione -> punti entro 15 s
node scripts/check-mermaid.mjs docs   # diagrammi Mermaid validi con accTitle/accDescr (Fase 2)
# Fase 2, quando esistono
pnpm registry:build                   # tipi, snapshot Directus e documentazione dal Registry (M10.1)
pnpm --filter e2e test                # journey Playwright (M9)
lh doctor                             # verifica dell'installazione (M12.2)
```

**Flusso di lavoro da Fase 2 (regola 16, ADR-041).** Una fetta = un ramo `fase2/Mn.k-titolo-breve` creato da `main` aggiornato = una pull request verso `main` (`gh pr create` o l'API GitHub) con gli ID nel titolo, es. `feat(member): PII fuori dal bus [F2-…, ADR-032, docs/18 §3.4]`, e il modello `.github/pull_request_template.md` compilato. Mai push su `main`, mai force push, mai merge delle proprie PR. Dopo l'apertura, `docs/14` si aggiorna nello stesso ramo con il numero della PR. Due fette in parallelo non toccano lo stesso servizio, lo stesso `contracts/` o lo stesso file di `docs/`: la seconda PR si aggiorna su `main` dopo il merge della prima.

## 5. Convenzioni in pillole

- **Java 25, Spring Boot 4.1.x, Maven.** Spring Data JDBC + `JdbcClient`, Flyway, Spring for Apache Kafka. Niente JPA/Hibernate, niente Lombok (usa `record`).
- **Package:** `io.loyaltyhub.<servizio>.{api,domain,application,infra,messaging,demo}`.
- **DB:** un solo database, **uno schema per servizio**; un servizio non legge mai lo schema di un altro.
- **API:** REST JSON, prefisso `/v1`, errori RFC 9457, paginazione `{items,page}`. Vedi `docs/06`.
- **Eventi:** CloudEvents 1.0 JSON strutturato; `type` = `io.loyaltyhub.<famiglia>.<nome>`; chiave Kafka = `memberId`.
- **Frontend:** TypeScript strict, App Router, Tailwind v4 + shadcn/ui, TanStack Query. `backoffice/` e `portal/` non si importano mai a vicenda.
- **Lingua:** UI e documenti in italiano; identificatori, API ed eventi in inglese.
- **Commit:** Conventional Commits + ID spec, es. `feat(wallet): FIFO lot spending [F-WAL-04]`.
- **Test:** ogni regola di dominio ha unit test; ogni consumer ha un test d'integrazione (Testcontainers). Vedi `docs/06 §9`.

## 6. Contratto di esecuzione (agente)

### Fonti autorevoli e precedenza
| # | Fonte | Decide su |
|---|---|---|
| 1 | `docs/13-REGISTRO-DECISIONI.md` | vincoli architetturali (vince su tutto) |
| 2 | `docs/05` + `contracts/events/` | forma degli eventi |
| 3 | `docs/servizi/*.md` | API, tabelle, regole del singolo servizio |
| 4 | `docs/02`, `docs/03` | comportamento funzionale |
| 5 | `docs/08`, `docs/09` | comportamento delle schermate |
| 6 | `docs/10` + `seed/` | dati demo |

Se due fonti confliggono vince quella col numero più basso; apri comunque una voce in `docs/15-DOMANDE-APERTE.md`.

### Non toccare
| Cosa | Perché |
|---|---|
| Nomi dei 5 topic e dei `type` evento | contratto tra servizi; il Kafka gratuito ha un tetto rigido |
| ID dei dati in `seed/` (`MBR-…`, `CMP-…`, `RWD-…`) | referenziati da più servizi, dagli scenari e dalle specifiche UI |
| Confini dei servizi (chi possiede cosa) | è l'ordine che questo repo esiste per garantire |
| `docs/13` (ADR accettate) | si superano con una nuova ADR, non si riscrivono (il job `guard` lo verifica) |
| `registry/elements.yaml` senza `registry:build` e senza fetta che lo cita | è l'unica fonte dei tipi: CMS, TS e documentazione ne derivano (ADR-029) |

### Definizione di "fatto" (per ogni slice)
1. Criteri di accettazione della milestone verdi (`docs/12`).
2. `./mvnw verify` e `pnpm lint typecheck test` verdi; `scripts/check-seed.mjs` verde.
3. Schermate coinvolte: stati *loading / empty / error / degraded* implementati (`docs/07 §6`).
4. `docs/14-STATO-AVANZAMENTO.md` aggiornato (spunta gli ID completati).
5. Nessun `TODO` senza ID; ogni scostamento dalla spec marcato `// SPEC-GAP: <id domanda>`.

### Fermati e chiedi
| Condizione | Azione |
|---|---|
| Serve un nuovo servizio, topic, dipendenza infrastrutturale o libreria "pesante" | STOP → proponi ADR in `docs/15-DOMANDE-APERTE.md`, attendi Giuseppe |
| Una feature richiede autenticazione reale | STOP → fuori scope PoC |
| Specifica ambigua su una regola di business (punti, tier, vincite) | Scegli l'opzione più conservativa, marca `SPEC-GAP`, registra la domanda |
| Un limite del free tier impedisce un requisito | STOP → descrivi il limite e 2 alternative a costo zero |
| Devi cambiare un contratto evento o un ID seed | STOP → è una decisione, non un refactor |

Referente per ogni escalation: **Giuseppe** (owner del progetto).

## 7. Fase 2 (profilo enterprise)

Fonte: `docs/18-FASE-2.md §7`; dopo M8.0 le ADR 026–045 di `docs/13` vincono su tutto il resto. Valgono per ogni fetta da M8.0 in poi; le regole 1, 2, 3, 4, 7, 9 di §1 restano invariate.

- **5-bis.** I 5 topic restano i 5 topic. Si scala per partizioni e concorrenza (ADR-028); una suddivisione richiede ADR.
- **6-bis.** Identità reale: OIDC ovunque nel profilo `enterprise` (ADR-027). `X-LH-Actor` e `memberId` espliciti sopravvivono **solo** nel profilo `demo`. Non scrivere codice che gestisce password.
- **8-bis.** Ogni componente infrastrutturale è dichiarato nel chart e nel compose; niente dipendenze fuori da `LH_MODE`. La modalità `embedded` non è HA e lo dice.
- **10. PII mai sul bus.** Un campo `x-lh-pii: true` non entra in un evento pubblicato; il test di contratto lo impedisce (ADR-032).
- **11. Tipi nel codice, istanze nei dati.** Un nuovo blocco, meccanica o canale passa dal Registry e da `registry:build`; non esistono blocchi "speciali" fuori dal Registry (ADR-029).
- **12. Headless-first.** Nessun dato mostrato dal portale o dai widget che non sia ottenibile dalle API in `contracts/api/`.
- **13. Conformità verificata dal software.** Contrasto, blocchi obbligatori, un solo H1, testi alternativi, compatibilità dei contratti e delle API: validatori, non checklist.
- **14. Aggiornabile senza fermo.** Ogni migrazione è expand/contract; ogni fetta lascia funzionante la versione precedente dei consumer (ADR-038).
- **15. L'agente propone, non decide.** Solo `DRAFT`, con evidenze; `LEGAL` approva.
- **16. Solo pull request verso `main`.** Una fetta = un ramo = una PR con gli ID nel titolo; niente push diretti, niente force push; le ADR si aggiungono, non si modificano (ADR-041).
- **17. Documentare con un diagramma.** Ogni fetta che introduce o cambia un concetto, un flusso o un ciclo di vita aggiorna la pagina Mintlify corrispondente con un diagramma Mermaid conforme a §3.12; le specifiche in `site/` non si scrivono a mano (ADR-040).
- **18. Zero trust.** Ogni chiamata, anche tra moduli, porta un'identità verificata: token per l'HTTP, principal e firma per il bus, ruolo per il database. Nessun endpoint senza `@RequiresRole` o `@PublicEndpoint`; nel portale il membro viene solo dal token (ADR-042).
- **19. SQL solo parametrico.** Testo SQL costante o costruito dal builder comune con colonne da allowlist; mai input nel testo SQL (ADR-042).
- **20. Nessun segreto nel codice, nei log o nel browser.** Token solo lato server (BFF), segreti dal secret manager, log senza dati personali né credenziali.
- **21. Ogni scrittura di configurazione lascia una traccia.** Una modifica fatta da un operatore — nel backoffice, in Directus o in Keycloak — produce sempre una voce in `audit_entry` con l'attore reale dal token; `audit_entry` è sola-inserzione (ADR-043). Un nuovo tipo di scrittura di configurazione senza il bridge verso l'audit è un errore di fetta, non un dettaglio da rimandare.
- **22. Nessuno approva se stesso; il prodotto non parte insicuro.** Chi sottomette non approva; le operazioni sensibili hanno doppio controllo configurabile; il profilo `enterprise` rifiuta configurazioni insicure invece di avvisare; ogni funzione nuova dichiara in `docs/compliance/iso27001-annex-a.md` quali controlli tocca e quale evidenza produce (ADR-044).

- **23. I punteggi modulano, non negano.** Un attributo `kind=SCORE` viene da fuori, ha una validità e scaduto è assente; può ampliare un'offerta, mai escludere da un premio dovuto o comparire in un effetto negativo; non è mai visibile al membro (ADR-045). Un fornitore di premi esterno è una destinazione di rete dichiarata e un responsabile del trattamento nel registro.
*Definizione di fatto* integrata: (6) OpenAPI rigenerata e `check-contracts` verde; (7) `registry:build` senza drift quando si tocca un blocco; (8) axe verde sulla matrice `e2e-pr` per le schermate toccate; (9) messaggi in entrambe le lingue da M11 in poi; (10) pagina Mintlify aggiornata con diagramma e job `docs` verde; (11) PR con gli ID nel titolo e `docs/14` aggiornato con il numero della PR; (17-bis) chi tocca una migrazione aggiorna l'`erDiagram` della scheda servizio, chi aggiunge o cambia uno stato aggiorna il relativo `stateDiagram-v2`, chi aggiunge una fonte o un tipo ammesso in ingestion aggiorna il diagramma di mapping fonte→saldo (§3.12-bis); (21) una voce di audit verificata in `GET /v1/audit` per ogni scrittura di configurazione introdotta dalla fetta, con attore reale e nessun `UPDATE` possibile.

*Fermati e chiedi* integrato: nuovo endpoint `@PublicEndpoint`, nuova destinazione di rete in uscita, nuovo produttore per un `type` in `producers.yaml`, nuovo ruolo dell'immagine, nuova collezione Directus fuori dal Registry, nuovo campo `pii:true` in qualunque schema, nuovo provider LLM cloud, qualunque cosa che renda il portale dipendente da Directus a runtime, una nuova scrittura di configurazione che non produce una voce di audit, una nuova esportazione di dati personali, un'impostazione che in `enterprise` può restare insicura, un nuovo adattatore verso un fornitore di premi, un uso di `SCORE` fuori dalle condizioni di campagna e dai segmenti → STOP → ADR o Q in `docs/15`.
