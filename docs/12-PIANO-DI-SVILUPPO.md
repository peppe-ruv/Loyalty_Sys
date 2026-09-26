# 12 — Piano di sviluppo

Otto milestone, ciascuna **dimostrabile da sola**. Si lavora a **fette verticali**: mai "tutto il backend poi tutto il frontend". Una milestone è chiusa quando i suoi criteri sono verdi, la *Definizione di fatto* di `CLAUDE.md §6` è rispettata e `docs/14` è aggiornato.

## 1. Regole di lavoro per Claude Code
1. Una sessione = una **fetta** (tabella della milestone). All'inizio: leggere `CLAUDE.md`, `docs/14`, i documenti indicati dalla fetta. Alla fine: test verdi, `docs/14` aggiornato, commit con ID.
2. Ordine dentro una fetta: migrazione → dominio + unit test → casi d'uso → messaggistica + test d'integrazione → API → seed → tipi e hook frontend → schermata → stati di `docs/07 §6`.
3. Dubbio o conflitto tra specifiche → voce `Q-nn` in `docs/15`, scelta conservativa, marcatore `// SPEC-GAP: Q-nn`. Mai inventare confini, topic o tipi evento.
4. Niente anticipi: una schermata la cui milestone non è arrivata **non compare** nel menu.
5. Ogni nuovo `type` evento: schema + esempio in `contracts/` **prima** del codice.

## 2. Vista d'insieme

| M | Titolo | Risultato visibile | Servizi | Schermate |
|---|---|---|---|---|
| M0 | Fondamenta | repo che compila, servizio archetipo che parla con Kafka e DB, shell web con Demo Hub | `lh-common`, archetipo | HUB-01 (stato) |
| M1 | Core loop | un'azione simulata diventa punti sul portale — **anche online** | ingestion, member, campaign, wallet | BO-02, 03 (base), 05, 06, 09 (lettura), 26, 28, 30 (stato + reset) · PT-01, 02, 07, 14 |
| M2 | Visibilità | flusso live, tracciati, dashboard, audit, scenari | insight | BO-01, 22, 24, 25, 29 · rail eventi |
| M3 | Punti adulti | lotti, scadenze, pending, tier, edizioni, ponte interno | wallet, ingestion, campaign | BO-07, 08, 09 (ponte), 30 (job) · PT-08 |
| M4 | Premi | catalogo a fasce, richiesta premio (saga), coupon | reward, wallet | BO-10–13 · PT-03, 04, 13 |
| M5 | Gioco | instant win, obiettivi, badge, classifiche, referral, registrazione | gamification, member | BO-14–17 · PT-05, 06, 09, 10, 11 |
| M6 | Contenuti | CMS, pop-up, messaggi, tema, segmenti, attributi, tipi custom | engagement, member, ingestion | BO-04, 18–20 · PT-12, contenuti in PT-01/03/05/06 |
| M7 | Governance | approvazioni, webhook, DLQ riprocessa, non abbinati, anonimizzazione | tutti | BO-21, 23, 27 |

**Fase 2 (`docs/18`, profilo `enterprise`).** Minimo enterprise = M8–M12, poi M13–M15. Ogni fetta è un ramo `fase2/Mn.k-…` e una PR verso `main` (ADR-041).

| M | Titolo | Risultato visibile | Riferimento |
|---|---|---|---|
| M8 | Fondazioni enterprise | immagine unica a ruoli, OIDC/BFF, chart e compose, PII fuori dal bus, sicurezza, audit unificato, OpenAPI, documentazione Mintlify | `docs/18 §6 M8` |
| M9 | Qualità | `e2e/` con journey e invarianti, matrice device, carico k6, test di installazione | `docs/18 §6 M9` |
| M10 | Esperienza data-driven | Element Registry, Directus `cms`, `experience-service`, design system `brand`/`pa`, widget | `docs/18 §6 M10` |
| M11 | Multilingua | `/[locale]`, stringhe estratte, `LocalizedText`, inbox nella lingua del membro | `docs/18 §6 M11` |
| M12 | Distribuzione | appliance `embedded`, CLI `lh`, wizard, rilascio firmato, aggiornamento N−1 → N, pacchetto di conformità | `docs/18 §6 M12` |
| M13 | Composizione estesa | page builder, flag, program package, economia, punteggi, cataloghi esterni, missioni | `docs/18 §6 M13` |
| M14 | Agente regolamento | `assistant-service` self-hosted, BO-33 | `docs/18 §6 M14` |
| M15 | Esercizio | runbook, DR e game day, audit di accessibilità, penetration test | `docs/18 §6 M15` |

## 3. Milestone

### M0 — Fondamenta
| Fetta | Contenuto | Leggi |
|---|---|---|
| M0.1 | Monorepo: parent POM (Java 25, Boot 4.1.x, gestione versioni), wrapper, `.editorconfig`, `LICENSE`, `.gitignore`, `.dockerignore`, struttura cartelle di `CLAUDE.md §3` | `CLAUDE.md`, `docs/04 §6–7` |
| M0.2 | `libs/lh-common`: envelope CloudEvents, `OutboxWriter`/`OutboxRelay`, `processed_event`, `EventRouter`, retry + DLQ, errori RFC 9457, `X-LH-Actor` + `@RequiresRole`, `SeedLoader` + `SeedDates`, configurazione Kafka `PLAINTEXT/SSL_PEM/SASL_SSL` | `docs/05 §2`, `docs/06` |
| M0.3 | `contracts/events/`: envelope + schemi ed esempi degli eventi di M1; test di contratto | `docs/05` |
| M0.4 | `deploy/docker-compose.yml`, profilo `local` che crea i 5 topic | `docs/11 §9` |
| M0.5 | **Servizio archetipo** = `ingestion-service` ridotto: `POST /v1/events` → outbox → `lh.actions.v1`; un consumer di prova; Flyway; Actuator; Dockerfile | `docs/servizi/ingestion-service.md` |
| M0.6 | `web/`: Next.js, token, font, shell delle tre aree, proxy `/api/lh`, cookie persona, `/api/demo/status|wake`, HUB-01 con pannello stato | `docs/07` |
| M0.7 | CI (`backend`, `web`, `seed`, `contracts`), `scripts/check-seed.mjs` (scheletro), `seed/_schemas/` | `docs/11 §10`, `docs/10 §11` |

**Accettazione**
- *Dato* il compose attivo, *quando* invio un CloudEvent valido a `POST /v1/events`, *allora* ricevo `202` e il messaggio è sul topic con chiave = `subject` entro 2 s.
- *Dato* lo stesso `id` inviato due volte, *allora* la seconda risposta è `202` con `status=DUPLICATE` e sul topic c'è un solo messaggio.
- *Dato* un consumer che lancia sempre eccezione, *allora* dopo 3 tentativi il messaggio è su `lh.dlq.v1` con `errorCode` e il consumer prosegue.
- *Dato* Kafka irraggiungibile durante una scrittura, *allora* la riga resta in `outbox` e viene pubblicata al ritorno di Kafka (nessuna perdita).
- *Dato* il Demo Hub aperto con servizi spenti, *allora* ogni tessera mostra `DOWN/SLEEPING` senza errori in pagina.
- Immagine Docker dell'archetipo: parte con `-m 512m` e RSS ≤ 450 MB dopo 2 minuti.

**Verifica**: `./mvnw verify` · `pnpm lint typecheck test build` · `docker compose --profile all up` · CI verde.

**Prompt**
> Leggi `CLAUDE.md`, `docs/04`, `docs/05`, `docs/06`. Realizza la fetta **M0.2** (`libs/lh-common`) esattamente come in `docs/06 §1`. Scrivi prima i test (Testcontainers Kafka + Postgres) per outbox, idempotenza e DLQ; poi l'implementazione. Non creare nessun servizio in questa sessione. Alla fine aggiorna `docs/14`.

### M1 — Core loop (e primo deploy)
| Fetta | Contenuto |
|---|---|
| M1.1 | ingestion completo per M1: fonti, tipi azione con JSON Schema, validazione, risoluzione membro da `member_index`, esiti, seed |
| M1.2 | member: anagrafica, stati, ricerca, `member.registered/updated/status.changed`, seed 12 membri |
| M1.3 | campaign: modello, ciclo di vita (senza approvazione), condizioni (`data/member/context`), effetti `GRANT_POINTS` (`FIXED`, `PER_AMOUNT`) e `MULTIPLIER`, limiti per membro, calendario, pubblico per tier, `campaign.evaluated` + registro valutazioni, **simulazione** (`POST /campaigns/{id}/simulate`), elenco "come guadagnare" per il portale; seed completo (le campagne con effetti non ancora supportati restano caricate ma **non valutate**, con motivo `EFFECT_NOT_SUPPORTED_YET` nel log) |
| M1.4 | wallet: wallet per valuta, `points.grant` idempotente, ledger, saldo, API portale. `F-TIER-03` è **anticipata in forma minima**: il moltiplicatore si legge dalle tabelle `tier`/`member_tier` popolate dal seed; gestione dei livelli, salita e storico restano in M3 |
| M1.5 | BO-02, BO-03 (Panoramica, Movimenti, Azioni), BO-05, BO-06 (editor + simulazione), BO-09 (sola lettura: tipi e fonti), BO-26, BO-28, BO-30 (stato + reset orchestrato) |
| M1.6 | PT-01 (tessera, saldo, ultimi movimenti), PT-02, PT-07, PT-14 con schema "in elaborazione" a **polling** (l'SSE arriva in M2) |
| M1.7 | `POST /v1/demo/reset` nei 4 servizi, `scripts/smoke.sh`, `wake.sh` |
| M1.8 | **Deploy**: Aiven (manuale), Neon, Render (4 servizi), Vercel — `docs/11 §2` |

**Accettazione**
- *Dato* Marco (SILVER), *quando* dal simulatore invio `purchase.completed` di 130 € in un giorno feriale, *allora* entro 8 s (p95, servizi svegli) il ledger ha `EARN 162 PTS` e `EARN 130 STS` con `campaignCode=CMP-PURCHASE-BASE` e lo stesso `correlationId` dell'azione.
- *Stesso acquisto di sabato* → `campaignMultiplier=2`, 325 PTS (130 × 2 × 1,25 arrotondato per difetto).
- *Quarto acquisto nello stesso giorno* → `campaign.evaluated.skipped[]` con `reason=MEMBER_LIMIT_REACHED`, nessun movimento.
- *Membro `BLOCKED`* (Roberto) → ingestion `REJECTED (MEMBER_NOT_ACTIVE)`, niente sul topic; la riga compare in BO-26.
- *Simulazione* di `CMP-WEEKEND-X2` con un evento di martedì → `NO_MATCH`, con la condizione di calendario marcata ✗; nessun evento emesso.
- *Portale*: dopo l'invio da PT-14 compare "in arrivo…", poi il saldo sale con count-up; nessun aggiornamento ottimistico.
- *Servizio wallet fermo* → PT-01 mostra la sezione saldo *degraded*, il resto funziona; al riavvio l'arretrato viene elaborato e il saldo è corretto (RNF-06).
- *Reset* → i 12 membri tornano allo stato di `docs/10`; `check-seed` verde.
- **Online**: dal Demo Hub pubblico, "Accendi la demo" porta 6/10 verdi (4 servizi + Kafka + DB) e `smoke.sh` passa.

**Prompt**
> Leggi `CLAUDE.md`, `docs/14`, `docs/servizi/wallet-service.md`, `docs/03 §4`, `docs/05`. Realizza la fetta **M1.4**. Limiti: niente lotti, scadenze, tier-up né edizioni (sono M3) — ma le tabelle `points_lot` e `member_tier` si creano già ora secondo la scheda, così M3 non richiede migrazioni distruttive. Test d'integrazione obbligatori: accredito, idempotenza su `effect_id`, membro senza wallet.

### M2 — Visibilità
Fette: **M2.1** insight ingest + event store + retention · **M2.2** SSE + `lib/realtime` + rail eventi + BO-24 · **M2.3** tracciati + BO-25 + passaggio di `usePendingTrace` da polling a SSE · **M2.4** `metric_daily`, storico sintetico, BO-01 · **M2.5** audit end-to-end (`lh.audit.v1` da tutti i servizi esistenti) + BO-22 · **M2.6** statistiche campagna in BO-05 (F-CMP-10) · **M2.7** scenari (`seed/scenarios.json`, esecutore in ingestion) + BO-29 · **M2.8** deploy di insight (con CORS per l'SSE).

**Accettazione**
- Un acquisto produce ≥ 4 righe nel rail entro 3 s, colorate per topic, con lo stesso `correlationId`.
- BO-25 mostra l'albero azione → valutazione → 2 effetti → 2 fatti, con tempi e l'esito "+162 PTS, +130 STS" (i messaggi si aggiungono in M6).
- `SCN-WEEKEND-BURST` eseguito da BO-29: 12 azioni, avanzamento visibile, nessuna perdita (12 valutazioni nell'event store).
- `SCN-DUPLICATE` → BO-26 mostra `DUPLICATE`; un solo movimento.
- `SCN-POISON` → voce DLQ, tracciato `FAILED`.
- Modifica di una campagna da BO-06 → riga in BO-22 con diff prima/dopo e attore `MARKETING:luca.marketing`.
- BO-01 dopo un reset: nessun grafico vuoto.
- SSE interrotto → dopo 3 tentativi l'UI passa a polling e lo dichiara.

### M3 — Punti adulti
Fette: **M3.1** lotti, scadenza `ROLLING_MONTHS`, `pendingDays` e rilascio (il consumo FIFO arriva con la spesa, in M4) · **M3.2** job (scadenze, preavvisi, rilascio) con `asOf` + sezione *Job* di BO-30 · **M3.3** tier: salita immediata, `tier.upgraded`, moltiplicatore da tabella, BO-07, PT-08 (livello) · **M3.4** edizioni e chiusura con discesa morbida (`dryRun`), BO-08 · **M3.5** **ponte interno** in ingestion (`internal_mapping`, `lhhop`, `LOOP_GUARD`), scheda *Ponte* di BO-09, campagna `CMP-TIER-UP-BONUS` · **M3.6** rettifiche manuali (BO-03) · **M3.7** cumulabilità (priorità, gruppo esclusivo — F-CMP-07), modalità `LOOKUP` e `FROM_FIELD`, spazio `history.*`; la simulazione li copre · **M3.8** `POST /v1/transactions` (F-ING-07, P1) · **M3.9** passività e `/v1/liability`.

**Accettazione**
- `SCN-TIER-UP`: Giulia passa a GOLD; il fatto `tier.upgraded` rientra come azione (`lhhop=1`, `source=internal`) e `CMP-TIER-UP-BONUS` accredita 500 PTS; nel tracciato è **un solo albero**.
- Catena artificiale con `lhhop` > 3 → DLQ `LOOP_GUARD`.
- Job scadenze con `asOf` = +31 giorni → Chiara perde 1 900 PTS (`EXPIRE`), fatto `wallet.points.expired`, saldo aggiornato nel portale.
- Chiusura edizione in `dryRun` come da `wallet-service.md §7` (Stefano → SILVER).
- Due campagne nello stesso `exclusiveGroup` → scatta solo quella a priorità più alta; l'altra è in `skipped[]` con `reason=EXCLUSIVE_GROUP`.
- Campagna `LIVE`: i campi bloccati non sono modificabili né da UI né da API (`409`).

### M4 — Premi
Fette: **M4.1** reward: categorie, fasce, premi, stock, visibilità per tier, BO-10/11 · **M4.2** pool coupon, generazione con seme, BO-12 · **M4.3** saga richiesta premio (reward ↔ wallet), timeout, compensazione · **M4.4** evasione manuale, annullo con rimborso, BO-13 · **M4.5** PT-03, PT-04, PT-13 · **M4.6** ponte `reward.redemption.confirmed` → azione `reward.redeemed`.

**Accettazione**: i 7 punti di `reward-service.md §7` + spesa **FIFO** sui lotti come da `wallet-service.md §7` + E2E n. 2 di `docs/09 §4` + tracciato della saga leggibile in BO-25 (richiesta → spesa → conferma → coupon → messaggio) + wallet fermo durante la richiesta → `PENDING`, poi `CONFIRMED` al riavvio se entro 10 min, altrimenti `REJECTED (TIMEOUT)` con stock ripristinato.

### M5 — Gioco
Fette: **M5.1** concorsi, montepremi, generatore istanti con seme, BO-14 · **M5.2** giocata (claim atomico), crediti, effetto `GRANT_PLAYS`, PT-05/06 con ruota · **M5.3** consegna vincite via ponte + campagne di sistema, effetto `ISSUE_COUPON` (reward) · **M5.4** obiettivi (4 metriche), badge, effetto `AWARD_BADGE`, BO-15, PT-09 · **M5.5** classifiche, BO-16, PT-10 · **M5.6** referral, registrazione dal portale (`/portal/join`), BO-17, PT-11 · **M5.7** *pianta un istante*.

**Accettazione**: i 7 punti di `gamification-service.md §7` + `SCN-REFERRAL` + `SCN-ONBOARDING` (catena acquisto → obiettivo → badge → bonus in un solo tracciato) + E2E n. 3.

### M6 — Contenuti
Fette: **M6.0** engagement: regole di notifica, template, inbox, consumo dell'effetto `message.send` · **M6.1** contenuti + selezione per posizionamento + BO-18 con anteprima in cornice telefono · **M6.2** pop-up e frequenze · **M6.3** card vincita in PT-06 · **M6.4** PT-12, BO-19 completo, effetto `SEND_MESSAGE` · **M6.5** tema a runtime, BO-20 · **M6.6** segmenti dinamici, ricalcolo, pubblico di campagne/premi/contenuti, BO-04 · **M6.7** attributi personalizzati e tipi azione **custom** (BO-09) utilizzabili nel costruttore di condizioni.

**Accettazione**: i 5 punti di `engagement-service.md §7` + E2E n. 1 completo (notifica di salita di livello in PT-12) + un tipo azione custom creato da BO-09 è inviabile da BO-28, selezionabile in BO-06 e produce punti senza ridistribuire nulla + un contenuto riservato a `SEG-DIGITAL` compare a Marco dopo `SCN-DIGITAL` e ricalcolo.

### M7 — Governance
Fette: **M7.1** `LH_APPROVAL_ENABLED=true`, policy, BO-21, transizioni per ruolo · **M7.2** webhook con HMAC e ritenti, BO-23 · **M7.3** DLQ *riprocessa/scarta*, BO-27 · **M7.4** eventi non abbinati: abbina e reinvia (BO-26) · **M7.5** anonimizzazione membro (propagata via `member.updated`/`status.changed`: ogni servizio cancella i dati personali dal proprio snapshot) · **M7.6** versioni e duplica per campagne/premi/concorsi.

**Accettazione**
- `luca.marketing` non può portare un concorso a `LIVE`: solo *Invia in revisione*; `elena.legal` approva con commento; storico visibile; tutto in audit.
- Rifiuto senza commento → `422`.
- Webhook verso endpoint che fallisce → 3 ritenti, `GAVE_UP`, *Riprova* manuale funziona; firma verificata da uno script d'esempio in `deploy/webhook-receiver/`.
- Anonimizzazione di un membro di prova: nessun servizio espone più nome/e-mail (test che interroga tutte le API di gestione); i movimenti restano.

**Fase 2 — milestone M8–M15.**

Copia di `docs/18 §6` all'adozione (M8.0); in caso di differenze vale `docs/18`.

### M8 — Fondazioni enterprise
Fette: **M8.0** adozione e governance: registrazione ADR 026–045 in `docs/13`, aggiornamento `CLAUDE.md`, `docs/12`, `docs/14`, `docs/15`, `docs/01 §4`, `docs/README` (Appendice B); `.github/CODEOWNERS`, `.github/pull_request_template.md`, `.github/dependabot.yml`, `scripts/setup-branch-protection.sh`, job `guard`; al termine il proprietario applica il ruleset e da lì in poi si lavora solo per PR (§3.13) · **M8.1** immagine unica (`deploy/image/Dockerfile` multi-stage, entrypoint a ruoli, `LH_ROLE/LH_SERVICES/LH_MODE=external`, s6-overlay per `all`, base Wolfi, non root, healthcheck per ruolo; CI: build multi-arch su tag, `hub` di Fase 1 = `LH_ROLE=hub`) · **M8.2** identità (Keycloak `idp`, realm as code, BFF con sessione server-side, CSRF, back-channel logout, passkey e MFA operatori, Spring resource server, `ActorContext` dal token, `member.external_id = sub`, client credentials per fonti e job, token exchange per i widget, broker e LDAP provati con un IdP di test) · **M8.3** chart Helm (`deploy/helm/loyaltyhub`, Deployment per ruolo, Strimzi e CloudNativePG di default, `values` per gestiti, migrazioni Job, Ingress + gateway) e compose di riferimento (`deploy/compose/reference.yml`); partizioni e concorrenza configurabili · **M8.4** PII fuori dal bus (`x-lh-pii`, test di contratto, `member.*:2`, doppia lettura, campaign su `birthYear/province`, segnaposto risolti dal BFF, modulo `delivery` con SMTP/WEBHOOK, audit mascherato, cifratura contatti) · **M8.5** sicurezza di piattaforma (gateway con JWT/rate limit/CORS/header, mesh mTLS Linkerd, network policy, ACL Kafka, ruoli DB owner/app per servizio, External Secrets, Pod Security `restricted`, SBOM/Trivy/cosign/CodeQL/IaC/secret scanning in CI, `docs/security/threat-model.md`) · **M8.6** osservabilità (OTel in tutti i ruoli, values Prometheus/Loki/Tempo/Grafana, dashboard SLO) · **M8.7** ingresso batch e import file (BO-32) · **M8.8** OpenAPI generata e verificata (`contracts/api/`) · **M8.10** sicurezza applicativa (§3.10 punti 2–9: firma dei messaggi e `producers.yaml`, validazione in consumo, deny by default e `MemberPrincipal`, builder SQL e regole Semgrep, limiti di input, template senza logica, sanitizzazione, SSRF, `Idempotency-Key`, rate limit per membro, file caricati) · **M8.11** verifica di sicurezza (ArchUnit, Schemathesis, ZAP, testbook `TB-SEC`, `docs/security/asvs.md`, `SECURITY.md`, job `security` obbligatorio) · **M8.12** audit unificato (§3.14: bridge Directus e Keycloak verso `audit_entry`, `member_activity_entry` in member-service, BO-03 estesa, PT-18 «La mia attività», retention audit a 400 giorni e sola-inserzione) · **M8.13** governo di accessi e dati (§3.15 punti 3–4: niente auto-approvazione e quattro occhi configurabili, deprovisioning dall'IdP, BO-34, break-glass, `x-lh-class` e registro dei trattamenti, retention per categoria, `erasure_log` e crypto-shredding, esportazioni con permesso e motivo; catena di hash dell'audit in M8.12) · **M8.9** documentazione (§3.12): contenuti in `site/`, dismissione di GitBook e `docs_v2/`, `docs-sync`, pagine eventi e riferimento API da OpenAPI, catalogo minimo dei diagrammi per le pagine di Fase 1, job `docs` con `broken-links` e `check-mermaid`.

**Accettazione**
- `docker run` dell'immagine con `LH_MODE=external` verso Postgres e Kafka di compose: tutti i ruoli `UP`, migrazioni applicate, smoke di Fase 1 verde con login OIDC reale.
- `helm install` su un cluster kind in CI: pod di tutti i ruoli `Ready`, Strimzi e CNPG provisionati, smoke verde attraverso il gateway.
- Test di contratto: nessun campo `pii:true` in alcun evento pubblicato; `check-contracts` contro l'ultimo tag verde.
- `luca.marketing` via Keycloak non può portare un concorso a `LIVE` (M7.1 invariata sotto OIDC); un membro vede solo i propri dati (test negativo su `memberId` altrui → 403).
- Batch di 1000 eventi accettato con esito per elemento in < 10 s in locale; import file di 10 000 righe con rapporto.
- Pipeline di rilascio produce immagine firmata con SBOM; scansione senza CVE alte.
- Sicurezza: chiamata a `/v1/portal/*` con il token di un membro e l'id di un altro → dati del solo titolare (il parametro è ignorato o `400`); endpoint senza dichiarazione di ruolo → build rossa; messaggio pubblicato da un modulo non ammesso per quel `type` → DLQ `PRODUCER_NOT_ALLOWED` e allarme; payload di iniezione SQL dal fuzzing Schemathesis → nessun `5xx` né effetto; webhook verso `http://169.254.169.254` → rifiutato; secondo riscatto con la stessa `Idempotency-Key` → stesso esito, nessun doppio addebito; nessun token OAuth visibile al JavaScript del browser.
- Un push diretto su `main` viene rifiutato; una PR che modifica il testo di un'ADR esistente fallisce `guard`.
- Una pagina pubblicata in Directus da `luca.marketing` produce una voce in `GET /v1/audit` con `service=cms` e l'attore reale (dal claim OIDC, non "directus"); un cambio di ruolo in Keycloak produce una voce con `service=idp`; nessun `UPDATE` è possibile su `audit_entry` (solo `INSERT`, verificato a livello di privilegi database). Un membro autenticato su `PT-18` vede login, consensi, giocate e riscatti propri e nessun dato di altri membri; `CARE` vede la stessa cronologia da BO-03.
- Governo: un `ADMIN` che sottomette un concorso non può approvarlo (`422 SELF_APPROVAL_FORBIDDEN`); un operatore disabilitato nell'IdP non ha più accesso entro la scadenza dell'access token; un ripristino da backup precedente a un'anonimizzazione non restituisce i dati di quel membro; un'esportazione senza permesso `DATA_EXPORT` o senza motivo è rifiutata; `lh audit verify` segnala una voce alterata direttamente nel database.
- Il sito Mintlify pubblicato da `main` mostra Specifiche, Eventi e Riferimento API generati; ogni pagina del catalogo minimo ha il suo diagramma; `docs` verde.

### M9 — Qualità
Fette: **M9.1** harness `e2e/` (Playwright, client API tipizzato da OpenAPI, personas, macchina del tempo, invarianti; stack CI = immagine `LH_ROLE=all LH_MODE=external` + Postgres e Kafka come service container) · **M9.2** percorsi di `docs/09 §4` + permessi + 4 journey lunghe (anno di un membro, concorso completo, saga premi con annulli, edizione) + fuzz journey con seme, selettori per ruolo/`data-testid` · **M9.3** matrice device (`desktop-chromium`, `desktop-firefox`, `desktop-webkit`, `bo-narrow`, `iPad Pro 11`, `iPhone 15`, `Pixel 7`), screenshot di riferimento con maschere, axe + pa11y, percorsi da tastiera · **M9.4** carico k6 (ingresso azioni, giocate concorrenti, riscatti; profili 100k e 2M membri) con soglie SLO · **M9.5** test di installazione dei tagli disponibili e gate di sicurezza (ZAP baseline); job `e2e-pr` (smoke, 2 progetti) e `e2e-nightly` (matrice completa, report come artifact).

**Accettazione**: invarianti verdi dopo ogni ciclo di ogni journey (Σ lotti = saldo, Σ mesi liability = in circolazione, stock ≤ totale, ogni `contest.won` con tracciato completo senza DLQ, nessun doppione in inbox, una voce audit per scrittura BO); nessuna violazione axe di livello *serious/critical* nella matrice; k6 entro SLO sul profilo 100k; `e2e-pr` < 15 min.

### M10 — Esperienza data-driven (nucleo)
Fette: **M10.1** Element Registry (manifesto, schemi, `registry:build`, drift check, `docs/registry/`) · **M10.2** Directus nell'immagine (ruolo `cms`, versione bloccata, snapshot generato, SSO, ruoli, `ref_*` via webhook, estensioni compilate, DB `cms`) · **M10.3** `experience-service` (rinomina con migrazione dello schema `engagement → experience` e doppia lettura dei consumer group; `composition_version`, notify-and-pull, validatore, rollback, `block_reference`, `GET /v1/portal/pages`; scheda `docs/servizi/experience-service.md`) · **M10.4** design system (token a tre livelli, profili `brand`/`pa`, tema dal CMS, blocchi strutturali, validatore di conformità) e renderer del portale sul set chiuso di pagine e blocchi · **M10.5** BO-31 e «Usato in» (BO-14/06/11) · **M10.6** widget kit.

**Accettazione**: una seconda ruota su un secondo concorso creata in Directus da `luca.marketing` senza rilascio e visibile nel portale entro la pubblicazione; con Directus spento il portale serve la composizione attiva; rollback in un clic; il profilo `pa` senza footer istituzionale non si pubblica (`422`); ogni blocco del set chiuso passa axe su tutta la matrice; `<lh-game>` incorporato in `widgets/example.html` gioca con un token reale; drift check verde.

### M11 — Multilingua
Fette: **M11.1** `next-intl` con `/[locale]`, redirect, lint, formati · **M11.2** estrazione stringhe backoffice · **M11.3** estrazione stringhe portale e widget, frasi generate come messaggi ICU · **M11.4** `MessageSource` e `LhException` a chiavi, `Accept-Language` · **M11.5** `LocalizedText` sulle entità elencate in §3.8, `member.locale`, inbox nella lingua del membro, seed EN, `check-seed` per lingua · **M11.6** E2E in matrice locale × device × profilo; README EN.

**Accettazione**: nessuna stringa letterale in `app/` e `components/` (lint verde); `/en/portal` completo senza fallback mancanti (report `i18n:coverage` = 100 %); un membro con `locale=en` riceve l'inbox in inglese; errori `422` localizzati; il sito Mintlify ha la versione inglese delle sezioni Introduzione, Concetti, Guide, Operazioni.

### M12 — Distribuzione (appliance)
Fette: **M12.1** modalità `embedded` (Postgres in-process con 3 database, bus in-process, asset su volume `/var/lib/lh`) · **M12.2** CLI `lh` (init, doctor, migrate, config validate, backup, restore, a11y-report) · **M12.3** wizard di primo avvio (admin, programma, lingue, profilo, package opzionale; segreti generati e stampati una volta) · **M12.4** pipeline di rilascio (semver, changelog, note di sicurezza, chart pubblicato in OCI, immagini per tag) e guida di installazione/aggiornamento · **M12.5** test di aggiornamento N−1 → N con dati e verifica invarianti · **M12.6** pacchetto di conformità (§3.15: `docs/compliance/iso27001-annex-a.md` e mappe collegate, rifiuto all'avvio e `lh doctor --security`, `lh data mask`, `lh decommission`, `lh forensics export`, export OCSF, prova di ripristino mensile, BO-35, VEX e SLSA nel rilascio, processo di segnalazione CRA in `SECURITY.md`, politica LTS, rapporto licenze).

**Accettazione**: `docker run -p 8080:8080 -v lh-data:/var/lib/lh …:<ver>` → in ≤ 3 min wizard raggiungibile, programma «Club Aurora» importabile, smoke verde; `lh backup` + `lh restore` su installazione pulita = stessi dati (test in CI); aggiornamento da versione precedente senza fermo del portale su Helm (expand/contract verificato). Conformità: `LH_PROFILE=enterprise` con un segreto di default non si avvia; ogni controllo dell'Annex A ha responsabilità ed evidenza dichiarate; il rilascio v1.0 ha SBOM, VEX, provenienza SLSA verificabile e PR tutte approvate da una persona diversa dall'autore.

### M13 — Composizione estesa
Fette: **M13.1** page builder completo (pagine libere, navigazione data-driven, tutti i blocchi del Registry, anteprima per membro da BO-31) · **M13.2** feature flag e interruttori consumati a caldo · **M13.3** program package export/import con versione del Registry e trasformazioni · **M13.4** economia del programma (§3.16 punto 1: `unit_cost` e storico, `cost_at_entry`, `/v1/kpi/economics`, `campaign.budget` con soglie e fatti, `/v1/liability/forecast`, BO-36) · **M13.5** punteggi esterni (§3.16 punto 2: `kind=SCORE`, batch e import attributi, validità, vincoli nel validatore) · **M13.6** cataloghi esterni (§3.16 punto 3: `fulfilment=EXTERNAL`, `reward_provider`, adattatori, saga con riserva, sincronizzazione, BO-37) · **M13.7** missioni e serie (§3.16 punto 4: `mission`, estensioni `STREAK`, blocchi `mission_card` e `streak_widget`, BO-38, PT-19).

**Accettazione (M13.4–M13.7)**: una campagna con `budget.maxPoints=10 000` e `warnAt=[80]` genera un solo `campaign.budget.threshold` all'80 % e va in `PAUSED` all'esaurimento con voce audit; `/v1/liability/forecast` su dati sintetici di 12 mesi restituisce una stima con `confidence` e la soglia genera l'allarme; un punteggio caricato con `validity_days=30` è visibile in BO-03 e, dopo 31 giorni, la condizione `nexists` diventa vera; una campagna con effetto negativo che usa uno `SCORE` è rifiutata dal validatore; un riscatto `EXTERNAL` con fornitore che rifiuta la riserva non spende punti; con `confirm` che fallisce oltre le riprove il membro è rimborsato e la voce audit lo mostra; un fornitore con tre errori passa a `DEGRADED` e i suoi premi escono dal portale con stato `degraded`; una missione a finestra relativa scade in base al `time` dell'azione di avvio anche se il server è avviato dopo; una serie mensile con `grace_units=1` sopravvive a un mese saltato; `mission.completed` produce punti solo tramite una campagna che lo ascolta.

### M14 — Agente regolamento
Fette: **M14.1** `assistant-service`, contratto `regulation-draft.schema.json`, `contest.regulation`, blocco `regulation`, golden set · **M14.2** pipeline (antivirus, PDFBox, per articolo, schema, fusione, quadratura) contro endpoint OpenAI-compatibile · **M14.3** BO-33 e applicazione in DRAFT via API esistenti · **M14.4** record/replay in CI, eval notturna, impronta firmata degli istanti, verbale ed export ritenuta.

**Accettazione**: sul golden set accuratezza per campo ≥ 90 % sui campi obbligatori del concorso; nessuna scrittura `LIVE` possibile dall'agente; con endpoint assente BO-33 spiega come attivarlo.

### M15 — Esercizio
Fette: **M15.1** runbook e dashboard SLO · **M15.2** prova di ripristino DR e game day (pod, broker, AZ) · **M15.3** audit di accessibilità con tecnologie assistive e dichiarazione · **M15.4** penetration test e hardening finale.

## 4. Test E2E e fumo
- `scripts/smoke.sh <base>`: sveglia → `SCN-SMOKE` (`app.login.daily` per Marco, `docs/10 §8`) → attende +5 PTS sul saldo (da M2: `wallet.points.earned` sul tracciato) ≤ 15 s a servizi svegli → esce ≠ 0 se fallisce. Lo scenario usa un `id` evento nuovo a ogni esecuzione; se il limite giornaliero di `CMP-APP-DAILY` è già scattato, lo script esegue prima il reset di campaign e wallet **solo in locale/CI**, mentre in demo verifica `campaign.evaluated` (scattata o `MEMBER_LIMIT_REACHED`) come prova che la pipeline è viva.
- E2E: da Fase 2 vivono in `e2e/` (M9, ADR-034): Playwright su stack reale con journey, invarianti, matrice device × lingua × profilo e carico k6; `e2e-pr` (smoke) e `e2e-nightly` (completa). I 3 percorsi di `docs/09 §4` e "cambio persona → permessi" ne fanno parte (M9.2).

## 5. Rischi di piano
| Rischio | Segnale | Risposta |
|---|---|---|
| Avvio JVM troppo lento su 0,1 CPU | > 180 s a servizio | cache AOT/CDS (`docs/11 §5`); ridurre auto-configurazioni; ultima risorsa: accorpare insight+engagement in un solo *deployable* mantenendo i moduli separati (nuova ADR) |
| 750 ore/mese insufficienti | demo spesso accesa | secondo workspace o `LH_JOBS_ENABLED=false` + spegnimento dei servizi non necessari alla demo del giorno |
| Kafka gratuito ritirato | avviso del fornitore | piano B di `docs/11 §3` |
| Minuti di build Render esauriti | build in coda | piano B immagini GHCR |
| Deriva delle specifiche | `SPEC-GAP` che si accumulano | revisione di `docs/15` a ogni chiusura di milestone |
| Onere di manutenzione dell'immagine (CVE di Directus, Keycloak, Node, JRE) | > 1 rilascio di sicurezza al mese richiesto | Treno di rilascio mensile + patch fuori ciclo; rivalutare in M12 il bundling di `cms`/`idp` (immagini ufficiali bloccate come alternativa, ADR) |
| Perimetro troppo ampio per la squadra | fette che restano `[~]` oltre due settimane | Tenere il minimo enterprise (M8–M12); M13–M15 solo dopo |
| Cambi alle API delle estensioni Directus | build delle estensioni rotta all'aggiornamento | Estensioni minime (interfaccia `ref_*`, pannello); logica nel servizio, non nel CMS |
| Modelli self-hosted deboli sul testo giuridico | accuratezza < 90 % sul golden set | Estrazione per articolo, esempi few-shot dal golden set, revisione umana comunque obbligatoria |
| Stack di frontiera (Java 25, Boot 4.1) | librerie terze in ritardo | Come Fase 1: Q in `docs/15`, alternative documentate |
| Deriva delle traduzioni e dei seed | `check-seed` per lingua rosso | Blocco in CI, EN generato assistito e rivisto |
| Directus senza licenza in uso aziendale | installatore sopra soglia | Avviso nel wizard e in `lh doctor`; responsabilità dichiarata |
