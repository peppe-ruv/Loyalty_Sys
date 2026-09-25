# TB-RWD — Premi e coupon (testbook funzionale)

Dominio **TB-RWD** del testbook (`docs/16`, §6): catalogo premi a fasce, visibilità ed eleggibilità, richiesta premio in saga col wallet, evasione, coupon e pool. Servizio: `reward-service` (+ wallet simulato dai suoi fatti). Storie: `docs/17` E05 (US-E05-01…17).

- **Oracolo** = specifica: `docs/servizi/reward-service.md` (qui «reward §n»), `docs/03 §3.6` e `§5`, `docs/02` F-RWD-01…08 e F-CPN-01…03, `docs/05` e `contracts/`, `docs/06 §2–§3 e §7`, `docs/08 §2` e BO-10…13, `docs/09` PT-03/04/13, `docs/10 §5`, le scelte di `docs/15` citate col loro `Q-nn`. Mai «quello che il codice restituisce oggi».
- **Scelta da decidere (Q-R*)** = la specifica tace: la domanda è registrata in `docs/15` (id provvisori `Q-273`…`Q-288`, da rinumerare) e la riga asserisce la scelta di oggi, marcata **Q-Rn** nell'atteso e nei riferimenti (commento `// TESTBOOK: scelta da decidere, vedi Q-Rn` nel test). Elenco in §19.
- **Divergenza** = il codice non rispetta la specifica: il test asserisce la specifica e fallisce; registro in §18.

## 0. Esecuzione

| Classe | Tipo | Righe | CSV (`services/reward-service/src/test/resources/testbook/rwd/`) |
|---|---|---|---|
| `TestbookRwdLifecycleTest` | unit (surefire) | LCY-001…099 | `lifecycle.csv` |
| `TestbookRwdRulesTest` | unit | STK-001…012 | `stock-state.csv` |
| `TestbookRwdEligibilityIT` | integrazione (failsafe) | VIS, ORD, SNP, STK-020/021, AUD-010 | `visibility.csv`, `order.csv`, `malformed.csv` |
| `TestbookRwdCatalogIT` | integrazione | CAT, EDT, BND, LCY-101…123, STK-022/023, AUD-001…006 | `reward-create.csv`, `category.csv`, `reward-edit.csv`, `stock-recalc.csv`, `bands.csv`, `lifecycle-api.csv` |
| `TestbookRwdSagaIT` | integrazione | SAG, FUL, TMO, ROL-001…021, AUD-007/008 | `saga.csv`, `timeout.csv`, `roles-redemption.csv` |
| `TestbookRwdCouponIT` | integrazione | CPN, EFF, ROL-030…049, AUD-009 | `coupon-lifecycle.csv`, `coupon-expiry.csv`, `pool-create.csv`, `pool-generate.csv`, `pool-import.csv`, `roles-coupon.csv` |

- Ogni riga CSV è **un** caso JUnit: `DynamicTest` con nome `[<ID>] <descrizione>` e sorgente la riga del CSV (`TestbookRwdCsv`); le righe di scenario (senza CSV) sono `DynamicTest` con lo stesso formato di nome. Un ID = un caso eseguito.
- Le quattro classi `*IT` condividono **un solo contesto Spring** (`TestbookRwdBase`: stessa configurazione ⇒ contesto in cache), Postgres embedded e Kafka embedded come gli altri IT del modulo. Profilo `demo`; il timeout schedulato della saga è spento (lo si prova col job demo e `asOf`).
- **Orologio**: il bean `Clock` dell'applicazione è sostituito da un orologio fermo e spostabile (`MutableClock`); ogni caso lo imposta (base: giovedì 24/9/2026 10:00 Roma). Niente orologio di parete.
- **Dati**: ogni caso crea membri (snapshot locale), premi, pool e codici con identificativi nuovi; nessuna dipendenza dallo stato mutabile del seed (si usano solo fasce F1…F5 e categorie, mai modificate). Le fasce provate da BND sono scale nuove sopra quelle del seed.
- **Wallet simulato**: i fatti `wallet.points.spent` / `wallet.spend.rejected` e i fatti di member sono pubblicati su `lh.facts.v1`; l'attesa è sulla riga `processed_event` (stessa transazione dell'handler), non su pause fisse. I fatti emessi da reward si leggono dall'**outbox** (unica via di produzione, docs/06 §5, scritta nella stessa transazione del cambio di stato); le verifiche «nello stesso tracciato» e la DLQ si leggono da Kafka.
- Comando: `./mvnw -q -pl services/reward-service -am verify -Dtest='Testbook*Test' -Dit.test='Testbook*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`; rapporto: `TESTBOOK_WEB=0 bash scripts/testbook.sh`.

## 1. Inventario delle regole

| Regola | Testo (sintesi) | Rif. spec | Aree |
|---|---|---|---|
| R-01 | Visibile al membro se `LIVE`, dentro la validità, tier e segmento ammessi; tier non ammesso ⇒ visibile con `lockedByTier`; fuori segmento ⇒ escluso; il dettaglio segue il catalogo | docs/03 §5, reward §3 portale, F-RWD-04, PT-03/04 | VIS, SNP |
| R-02 | `stockState` AVAILABLE / LOW (< 10 %) / SOLD_OUT; illimitato = disponibile; `perMemberLimitReached` | reward §3, BO-10, PT-03 | VIS, STK |
| R-03 | Validazioni immediate della richiesta (422, nessun evento): premio non visibile/inesistente `REWARD_NOT_AVAILABLE`, `REWARD_SOLD_OUT`, `MEMBER_LIMIT_REACHED`, `MEMBER_NOT_ACTIVE`, `TIER_NOT_ELIGIBLE`, `SHIPPING_REQUIRED` (solo PHYSICAL); saldo **non** controllato | docs/03 §5, reward §3 | VIS, ORD, AUD-010 |
| R-04 | Richiesta valida ⇒ 202 `{redemptionId, status: PENDING, correlationId}`, stock prenotato atomicamente (`UPDATE … WHERE stock_remaining > 0`), fatto `reward.redemption.requested` con costo = soglia della fascia | reward §3, §5, docs/03 §5 | VIS, STK-020/021, FUL-022, BND-041 |
| R-05 | Costo del premio = `band.pointsThreshold`; la richiesta fissa `points_cost` | docs/03 §5, reward §2, F-RWD-02 | BND-040/041 |
| R-06 | Fasce: soglie uniche e crescenti (422 `BAND_THRESHOLD_DUPLICATE`); non eliminabile se ha premi (409 `BAND_IN_USE`) | reward §3, BO-11 | BND |
| R-07 | Saga: `wallet.points.spent` ⇒ CONFIRMED + fatto; poi AUTO_COUPON ⇒ coupon ISSUED + FULFILLED; INSTANT ⇒ FULFILLED; MANUAL ⇒ resta CONFIRMED | docs/03 §5, reward §5 | SAG, FUL |
| R-08 | Pool esaurito all'emissione ⇒ CONFIRMED con `needsAttention`, nessun `fulfilled`; `retry-fulfilment` dopo nuovi codici | docs/03 §5, reward §5, BO-13 | FUL, SAG |
| R-09 | `wallet.spend.rejected` ⇒ REJECTED col motivo, stock ripristinato | docs/03 §5, reward §5 | SAG, FUL-023 |
| R-10 | Timeout: PENDING da **più** di 10 min ⇒ REJECTED (TIMEOUT), stock ripristinato; spesa arrivata dopo ⇒ `cancelled` con `refund=true` | docs/03 §5, reward §5, Q-55 | TMO, SAG |
| R-11 | Annullo del membro solo da PENDING ⇒ CANCELLED, stock ripristinato (nessun rimborso) | docs/03 §5, reward §3 portale, PT-13 | SAG, FUL-024/025 |
| R-12 | Annullo con rimborso da CONFIRMED (prima dell'evasione), motivo, `CARE`/`ADMIN`: stock +1, coupon VOID, `cancelled refund=true` | F-RWD-07, reward §3, §5, §7 | SAG, FUL-014, ROL |
| R-13 | Evasione manuale solo CONFIRMED + `MANUAL`, nota (tracking facoltativo), `CARE`/`ADMIN` | F-RWD-06, reward §3, BO-13 | SAG, FUL-009…011, ROL |
| R-14 | Eventi per richieste sconosciute ⇒ ignorati (WARN), non DLQ; rielaborazione ⇒ nessun secondo coupon; doppio invio = stesso stato | reward §5, §7, docs/06 §9 | FUL-015…018 |
| R-15 | Coupon: `AVAILABLE → ISSUED → USED` oppure `EXPIRED`/`VOID`; uso: 409 `COUPON_ALREADY_USED`, 410 `COUPON_EXPIRED`, 404; scadenza = oggi + `validity_days`; job: ISSUED scaduti ⇒ EXPIRED | docs/03 §5, reward §3, §5, F-CPN-02/03 | CPN, FUL-002 |
| R-16 | Pool: codici `prefisso-XXXX-XXXX` in `A-Z2-9`, generazione `count ≤ 5000` con seme fisso, import con riepilogo `{imported, skipped[]}`, filtri dei codici | reward §3, §6, docs/03 §5, docs/10 §5, F-CPN-01, BO-12 | CPN |
| R-17 | Effetto `coupon.issue`: idempotente su `effect_id`, usa il pool del premio indicato, pool vuoto ⇒ DLQ `COUPON_POOL_EMPTY` non ritentabile | reward §5, F-CPN-02 | EFF |
| R-18 | Ciclo di vita comune con approvazione LEGAL sempre per i premi; REJECT ⇒ DRAFT con commento; transizioni vietate 409; storico e fatto `reward.status.changed` | docs/03 §3.6, docs/06 §7, F-RWD-08, F-APR-01/02, Q-53 | LCY |
| R-19 | Modifica: ammessa in DRAFT/PAUSED (REJECTED = ritorno in DRAFT); in LIVE solo `stock_total`, `valid_to`, `image_url` | reward §3, docs/08 §3.2 | EDT |
| R-20 | Versione: il `PUT` porta la `version`, 409 `VERSION_CONFLICT` se superata; lo stock residuo si ricalcola sulla riga corrente | docs/12 M7.6, Q-112 | EDT-040…053 |
| R-21 | Duplica: copia in DRAFT con codice `-COPY` | reward §3, Q-113 | EDT-060…063 |
| R-22 | Creazione premio: tipi `PHYSICAL, COUPON, DIGITAL, DONATION, EXPERIENCE`, evasioni `AUTO_COUPON, MANUAL, INSTANT`, fascia, categoria, stock `null` = illimitato, limite per membro, validità | reward §2, F-RWD-01/03 | CAT |
| R-23 | Ruoli: `object.edit` (ADMIN, MARKETING) su premi, fasce, categorie, pool; `object.approve` LEGAL/ADMIN; `redemption.handle` (ADMIN, CARE); `coupon.void` (ADMIN, CARE, Q-52); cassa: ogni ruolo tranne ANALYST (Q-52); `/v1/demo/**` ADMIN; `X-LH-Actor` assente ⇒ ANALYST | docs/06 §3, docs/08 §2, Q-52 | ROL, CAT, EDT, BND, LCY, CPN |
| R-24 | Snapshot del membro dai fatti di member e tier (stato, livello, segmenti) | reward §2, §4 | SNP |
| R-25 | Audit delle scritture (catalogo, fasce, pool, evasioni, annulli; ogni scrittura da backoffice) con l'attore; override ADMIN marcato | reward §4, docs/06 §3, docs/08 §2 | AUD |
| R-26 | Statistiche: premi e richieste per stato, stock sotto soglia (< 10 %) | reward §3 stats, BO-10 | STK-022/023 |
| R-27 | Portale: `memberId` esplicito; richiesta di un altro membro ⇒ 404; elenchi senza `memberId` ⇒ 400; il `correlationId` della richiesta HTTP diventa quello della saga | reward §3 portale, §5, docs/07 §3, CLAUDE.md §1.6 | FUL-024…028 |
| R-28 | Errori RFC 9457: 400 per richieste malformate (JSON assente o errato) | docs/06 §2 | ORD-016…019, FUL-011/013 |

## 2. Rami del codice → regola

Percorsi relativi a `services/reward-service/src/main/java/io/loyaltyhub/reward/` (salvo `lh-common`).

| Ramo (file:riga) | Esito | Regola | Righe |
|---|---|---|---|
| `application/RedemptionService.java:109` | 400 memberId/rewardCode mancanti | R-28 | ORD-016…018 |
| `application/RedemptionService.java:113`, `:115` | 422 MEMBER_NOT_ACTIVE (sconosciuto, non ACTIVE) | R-03 | VIS-031…034, ORD |
| `application/RedemptionService.java:118-120` | 422 REWARD_NOT_AVAILABLE (inesistente, non LIVE, fuori validità, fuori segmento) | R-01, R-03 | VIS, ORD |
| `application/RedemptionService.java:121-124` | 422 TIER_NOT_ELIGIBLE | R-03 | VIS, ORD |
| `application/RedemptionService.java:125-127` | 422 SHIPPING_REQUIRED (PHYSICAL senza spedizione o `{}`) | R-03 | VIS-039…042 |
| `application/RedemptionService.java:128-131` + `infra/RedemptionRepository.java:37` | 422 MEMBER_LIMIT_REACHED (attive PENDING/CONFIRMED/FULFILLED ≥ limite) | R-03 | VIS-055…058 |
| `application/RedemptionService.java:132-134` + `infra/RewardRepository.java:111` | 422 REWARD_SOLD_OUT, prenotazione atomica | R-03, R-04 | VIS, STK-020/021 |
| `application/RedemptionService.java:135-136` | fascia inesistente ⇒ `IllegalStateException` (500) | **ramo senza specifica** (difensivo: la fascia è validata alla creazione) | — |
| `application/RedemptionService.java:149-154` | PENDING + `requested` (correlationId = id del fatto) | R-04, R-27 | FUL-022, FUL-028 |
| `application/RedemptionService.java:163-166` | spesa per richiesta sconosciuta ⇒ ignorata | R-14 | FUL-017 |
| `application/RedemptionService.java:171-183` | PENDING ⇒ CONFIRMED + `confirmed` + evasione | R-07 | SAG-001 |
| `application/RedemptionService.java:184-190` | REJECTED/CANCELLED ⇒ `cancelled (LATE_SPEND, refund=true)` | R-10 | SAG-029, SAG-036, SAG-043 |
| `application/RedemptionService.java:191` | CONFIRMED/FULFILLED ⇒ nulla | R-14 | SAG-008, SAG-015, SAG-022, FUL-015 |
| `application/RedemptionService.java:199-203` | rifiuto per richiesta sconosciuta ⇒ ignorato | R-14 | FUL-018 |
| `application/RedemptionService.java:205-207` | rifiuto su richiesta non PENDING ⇒ ignorato | R-09 | SAG-009, -016, -023, -030, -037, -044 |
| `application/RedemptionService.java:208` | motivo assente ⇒ `REJECTED` | **ramo senza specifica** (il contratto del fatto rende `reason` obbligatorio) | — |
| `application/RedemptionService.java:217-226` + `infra/RedemptionRepository.java:140` | PENDING con `requested_at < asOf − 10 min` ⇒ REJECTED (TIMEOUT) | R-10 | TMO, SAG-003 |
| `application/RedemptionService.java:221` | già non PENDING al lock ⇒ saltata | R-10 | SAG-010, -017, -024, -031, -038, -045 |
| `application/RedemptionService.java:232-234` | annullo membro: 404 (altro membro) | R-27 | FUL-024 |
| `application/RedemptionService.java:233` | annullo membro senza `memberId` ⇒ ammesso | **ramo senza specifica** | FUL-025 (Q-283) |
| `application/RedemptionService.java:235-238` | 409 REDEMPTION_NOT_CANCELLABLE se non PENDING | R-11 | SAG-011, -018, -025, -032, -039, -046 |
| `application/RedemptionService.java:240-244` | CANCELLED (MEMBER), stock +1, `refund=false` | R-11 | SAG-004 |
| `application/RedemptionService.java:256-261` | fulfil: 404 · 409 REDEMPTION_NOT_FULFILLABLE | R-13 | FUL-019, SAG-006, -020, -027, -034, -041, -048 |
| `application/RedemptionService.java:257` | premio non più trovato ⇒ trattato come MANUAL | **ramo senza specifica** | — |
| `application/RedemptionService.java:262-264` | 422 NOTE_REQUIRED | R-13 | FUL-010 |
| `application/RedemptionService.java:265-270` | FULFILLED con nota (+ tracking) + audit | R-13, R-25 | SAG-013, FUL-009, AUD-007 |
| `application/RedemptionService.java:279-283` | cancel: 404 · 409 REDEMPTION_NOT_CANCELLABLE | R-12 | FUL-020, SAG-005, -026, -033, -040, -047 |
| `application/RedemptionService.java:284-286` | 422 REASON_REQUIRED | R-12 | FUL-012 |
| `application/RedemptionService.java:289-299` | CANCELLED, stock +1, coupon VOID, needsAttention spento, `refund=true`, audit | R-12, R-25 | SAG-012, SAG-019, FUL-014, AUD-008 |
| `application/RedemptionService.java:306-309` | retry: 404 · 409 REDEMPTION_NOT_RETRYABLE | R-08 | FUL-021, SAG-007, -014, -028, -035, -042, -049 |
| `application/RedemptionService.java:311-315` | pool ancora vuoto ⇒ 409 COUPON_POOL_EMPTY | R-08 | FUL-005 |
| `application/RedemptionService.java:328-329` | premio non trovato all'evasione ⇒ MANUAL | **ramo senza specifica** | — |
| `application/RedemptionService.java:333-336` | coupon già legato alla richiesta ⇒ riusato (idempotenza) | R-14 | FUL-015 |
| `application/RedemptionService.java:337-343` | nessun codice ⇒ needsAttention | R-08 | FUL-003, FUL-004 |
| `application/RedemptionService.java:334` | premio AUTO_COUPON senza pool ⇒ needsAttention | **ramo senza specifica** | FUL-006 (Q-281) |
| `application/RedemptionService.java:344`, `:346`, `:347` | AUTO_COUPON evaso · INSTANT evaso · MANUAL in coda | R-07 | FUL-001, FUL-007, FUL-008 |
| `application/PortalCatalogService.java:67-69` | catalogo: LIVE ∧ validità ∧ segmento | R-01 | VIS |
| `application/PortalCatalogService.java:83-85` | dettaglio 404 se non visibile | R-01 | VIS |
| `application/PortalCatalogService.java:93-102` | stockState | R-02 | STK-001…012, VIS |
| `application/PortalCatalogService.java:104-109` | lockedByTier (anche con membro sconosciuto) | R-01 | VIS, SNP-004/005 |
| `application/PortalCatalogService.java:112-117` | segmento: vuoto = tutti; membro sconosciuto ⇒ escluso | R-01 | VIS |
| `application/PortalCatalogService.java:119-122` | perMemberLimitReached | R-02 | VIS |
| `domain/Reward.java:43` | validità `[validFrom, validTo)` | R-01 | VIS-043…053 |
| `application/CatalogAdminService.java:79-81` | 422 CATEGORY_INVALID | R-22 | CAT-041/042 |
| `application/CatalogAdminService.java:94-96` | 422 BAND_INVALID (codice, nome, soglia ≤ 0) | R-06 (soglia 0: **ramo senza specifica**) | BND-012…015 |
| `application/CatalogAdminService.java:98-100` | 422 soglia duplicata | R-06 | BND-002, -004, -008, -011 |
| `application/CatalogAdminService.java:103-108` | 422 soglie non crescenti con l'ordine | R-06 | BND-003, -005, -009, -020 |
| `application/CatalogAdminService.java:119-122` | delete: 404 · 409 BAND_IN_USE | R-06 | BND-022…025 |
| `application/CatalogAdminService.java:131-132` | premio per id o codice, 404 | R-19 | EDT-036, LCY-116 |
| `application/CatalogAdminService.java:137-142` | 400 codice · 409 CODE_TAKEN | R-22 | CAT-027…029 |
| `application/CatalogAdminService.java:162-164` | 409 CODE_IMMUTABLE | **ramo senza specifica** | EDT-029 (Q-281) |
| `application/CatalogAdminService.java:166-176` | modifica per stato; LIVE: campi bloccati | R-19 | EDT-001…036 |
| `application/CatalogAdminService.java:178-181` | 409 VERSION_CONFLICT; senza `version` vale quella corrente | R-20 (senza version: **ramo senza specifica**) | EDT-048, EDT-049 |
| `application/CatalogAdminService.java:189-204` | duplica `-COPY`, poi `-COPY2`… | R-21 (`-COPYn`: **ramo senza specifica**) | EDT-060…063 |
| `application/CatalogAdminService.java:211-230` | transizione, storico, fatto, audit (override) | R-18, R-25 | LCY-101…123, AUD-003 |
| `application/CatalogAdminService.java:259-273` | 422 REWARD_INVALID (nome, tipo, evasione, fascia, categoria, AUTO_COUPON non COUPON, stock < 0, limite < 1, validTo ≤ validFrom) | R-22 (limite < 1: **ramo senza specifica**) | CAT-001…026 |
| `application/CatalogAdminService.java:275-282` + `infra/RewardRepository.java:84-100` | stock residuo = residuo corrente + Δtotale, mai < 0; da illimitato = nuovo totale; `stockTotal` assente = invariato | R-20 (da illimitato e assente: **rami senza specifica**) | EDT-040…052 |
| `infra/RewardRepository.java:118-124` | ripristino `least(residuo + 1, totale)` | R-09…R-12 | SAG, EDT-053 (Q-280) |
| `lh-common …/approval/GovernedTransitions.java:24-30` | azione sconosciuta ⇒ 422 INVALID_ACTION | R-18 (codice: **ramo senza specifica**) | LCY-099 |
| `lh-common …/approval/GovernedTransitions.java:34-42` | APPROVE/REJECT: ruolo della policy o ADMIN; altre: ADMIN, MARKETING | R-18, R-23 | LCY-059…094 |
| `lh-common …/approval/GovernedTransitions.java:44-46` | approvazione spenta: SUBMIT da DRAFT ⇒ LIVE | **ramo senza specifica** (docs/06 §7: solo «DRAFT → LIVE diretto») | LCY-095 (Q-282) |
| `lh-common …/approval/ApprovalStateMachine.java:21-61` | macchina a stati, 409 INVALID_TRANSITION / APPROVAL_REQUIRED, 422 REJECT_COMMENT_REQUIRED | R-18 | LCY-001…058 |
| `application/CouponService.java:102-112` | pool: 400 · 422 COUPON_PREFIX_INVALID · 422 COUPON_VALIDITY_INVALID · 409 CODE_TAKEN | R-16 (prefisso e validità: **rami senza specifica**) | CPN-060…075 |
| `application/CouponService.java:136-150` | 422 COUPON_COUNT_INVALID fuori 1…5000; generazione col seme | R-16 | CPN-080…090 |
| `application/CouponService.java:161-181` | import: 422 vuoto · 422 > 5000 · normalizzazione · duplicati/esistenti in `skipped` | R-16 (vuoto, > 5000, normalizzazione: **rami senza specifica**) | CPN-092…099 |
| `application/CouponService.java:204` | verifica: ISSUED oltre la scadenza mostrato EXPIRED | R-15 | CPN-009, CPN-032 |
| `application/CouponService.java:214-224` | uso: 404 · 409 USED/VOID/AVAILABLE · 410 EXPIRED (anche ISSUED scaduto, persistito) | R-15 (VOID/AVAILABLE: codici **senza specifica**, 409 da docs/06 §2) | CPN-001…025 |
| `application/CouponService.java:226-238` | USED + `coupon.used` + audit | R-15, R-25 | CPN-004, CPN-030, AUD-009 |
| `application/CouponService.java:244-252` | annullo: 404 · 409 USED · 409 VOID · altrimenti VOID (anche AVAILABLE/EXPIRED) | R-15 (da AVAILABLE/EXPIRED: **rami senza specifica**) | CPN-002…020 |
| `application/CouponService.java:266-271` | emissione: pool vuoto ⇒ vuoto; scadenza = adesso + N × 24 h | R-15, R-17 | FUL-002 (Q-277), EFF |
| `infra/CouponRepository.java:175` | job: ISSUED con `expires_at ≤ asOf` ⇒ EXPIRED | R-15 | CPN-040…050 |
| `api/RewardJobsController.java:69-77` | `asOf` assente = adesso; con `T` istante; data pura = fine giorno Roma a 23:59:59.999999 (`LAST_INSTANT`, riga 41) | R-15, R-10 (data pura: **ramo senza specifica**, Q-284) | CPN-044…050, TMO-006/007 |
| `messaging/CouponIssueHandler.java:42-57` | DLQ INVALID_EFFECT · idempotenza effectId (default id evento) · DLQ REWARD_NOT_FOUND · DLQ COUPON_POOL_MISSING · DLQ COUPON_POOL_EMPTY | R-17 (primi tre e default: **rami senza specifica**) | EFF-001…009 |
| `messaging/MemberSnapshotHandler.java:40-69` | snapshot: stato, tier, segmenti, profilo | R-24 | SNP-001…007 |
| `api/*Controller.java` `@RequiresRole` | guardie per ruolo | R-23 | ROL, CAT-030…035, EDT-032…035, BND-016…019, LCY |
| `api/PortalRedemptionsController.java:39`, `:47`, `:56`; `api/PortalCouponsController.java:36` | portale: 400 corpo/memberId · 404 altro membro | R-27, R-28 | ORD-019, FUL-026/027, CPN-031 |
| `api/RewardStatsController.java:45` | stock sotto il 10 % (esauriti compresi) | R-26 | STK-022 |
| `lh-common …/web/GlobalExceptionHandler.java:66-67` | ogni eccezione non applicativa (anche corpo JSON assente) ⇒ 500 | R-28 | ORD-019, FUL-011, FUL-013 (**divergenza**) |

## 3. VIS — Visibilità ed eleggibilità

**Regole**: R-01, R-02, R-03, R-04. Una riga verifica insieme il catalogo del portale (presenza, lucchetto, `stockState`, `perMemberLimitReached`), il dettaglio (200/404) e la richiesta (202 con stock −1, oppure 422 con stock invariato e nessuna richiesta creata).

| Ingresso | Classi valide | Classi non valide / limite |
|---|---|---|
| stato premio | LIVE | DRAFT, IN_REVIEW, APPROVED, PAUSED, ENDED, ARCHIVED; codice inesistente |
| finestra di validità | dentro `[from, to)`, senza estremi | prima, dopo; from = adesso, from ± 1 ms, to = adesso, to ± 1 ms; mezzanotte di Roma 30/9, cambio d'ora 25/10 e 29/3, 29/2/2028 |
| tier ammessi | nessuno (tutti), tier del membro incluso | tier del membro escluso |
| segmenti ammessi | nessuno, membro in uno dei segmenti | membro fuori, membro senza segmenti |
| stato membro | ACTIVE | INACTIVE, BLOCKED, ANONYMIZED, sconosciuto |
| stock | illimitato, 100, 1 | 0; 9 su 100 (LOW) |
| limite per membro | assente, non raggiunto (limite − 1) | raggiunto (= limite); solo richieste chiuse (non contano) |
| tipo / spedizione | DIGITAL, EXPERIENCE senza spedizione, PHYSICAL con indirizzo | PHYSICAL senza spedizione, spedizione `{}` |

**Strategia**: prodotto 2 × 3 × 3 × 3 × 2 × 4 × 2 = 864 > 64 ⇒ **tutte le coppie** sui 7 fattori (stato LIVE/PAUSED, finestra IN/BEFORE/AFTER, tier NONE/IN/OUT, segmento NONE/IN/OUT, membro ACTIVE/BLOCKED, stock NULL/0/1/100, limite NO/REACHED) = **15 righe** (VIS-001…015, generate e verificate con un algoritmo all-pairs deterministico); **più** ogni classe da sola sopra una base valida (VIS-016…042, 27 righe); **più** ogni valore limite da solo (VIS-043…059, 17 righe). Riduzione: 864 → 59. Quando più condizioni sono vere l'atteso è «422 con `code` nell'insieme delle condizioni violate» (la precedenza è trattata in §4).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-VIS-001 | pairwise LIVE/IN/NONE/NONE/ACTIVE/NULL/NO: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock illimitato | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-002 | pairwise LIVE/BEFORE/IN/IN/BLOCKED/0/REACHED: stato LIVE; validità [+1d, +2d); tier ammessi GOLD/PLATINUM, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-A; membro BLOCKED; stock 0; limite 1, già attive 1 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE, MEMBER_LIMIT_REACHED, REWARD_SOLD_OUT}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-003 | pairwise PAUSED/AFTER/OUT/OUT/ACTIVE/1/REACHED: stato PAUSED; validità [-2d, -1d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti SEG-TB-A, membro in SEG-TB-B; membro ACTIVE; stock 1; limite 1, già attive 1 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE, MEMBER_LIMIT_REACHED}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-004 | pairwise PAUSED/IN/IN/OUT/BLOCKED/100/NO: stato PAUSED; validità [-1d, +1d); tier ammessi GOLD/PLATINUM, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-B; membro BLOCKED; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-005 | pairwise LIVE/BEFORE/OUT/NONE/BLOCKED/1/NO: stato LIVE; validità [+1d, +2d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti tutti, membro in nessuno; membro BLOCKED; stock 1 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-006 | pairwise PAUSED/BEFORE/NONE/IN/ACTIVE/100/REACHED: stato PAUSED; validità [+1d, +2d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-A; membro ACTIVE; stock 100; limite 1, già attive 1 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {REWARD_NOT_AVAILABLE, MEMBER_LIMIT_REACHED}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-007 | pairwise LIVE/AFTER/NONE/OUT/BLOCKED/0/NO: stato LIVE; validità [-2d, -1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-B; membro BLOCKED; stock 0 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE, REWARD_SOLD_OUT}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-008 | pairwise PAUSED/AFTER/IN/NONE/ACTIVE/NULL/REACHED: stato PAUSED; validità [-2d, -1d); tier ammessi GOLD/PLATINUM, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock illimitato; limite 1, già attive 1 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {REWARD_NOT_AVAILABLE, MEMBER_LIMIT_REACHED}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-009 | pairwise PAUSED/IN/OUT/IN/ACTIVE/0/NO: stato PAUSED; validità [-1d, +1d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti SEG-TB-A, membro in SEG-TB-A; membro ACTIVE; stock 0 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE, REWARD_SOLD_OUT}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-010 | pairwise LIVE/BEFORE/OUT/OUT/BLOCKED/NULL/NO: stato LIVE; validità [+1d, +2d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti SEG-TB-A, membro in SEG-TB-B; membro BLOCKED; stock illimitato | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-011 | pairwise LIVE/IN/NONE/IN/ACTIVE/1/REACHED: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-A; membro ACTIVE; stock 1; limite 1, già attive 1 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached true; richiesta 422 MEMBER_LIMIT_REACHED, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-012 | pairwise LIVE/AFTER/OUT/NONE/ACTIVE/100/NO: stato LIVE; validità [-2d, -1d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 con code ∈ {REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE}, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-013 | pairwise LIVE/AFTER/NONE/IN/ACTIVE/NULL/NO: stato LIVE; validità [-2d, -1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-A; membro ACTIVE; stock illimitato | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-014 | pairwise LIVE/IN/NONE/NONE/ACTIVE/0/NO: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 0 | nel catalogo, senza lucchetto, stockState SOLD_OUT, perMemberLimitReached false; richiesta 422 REWARD_SOLD_OUT, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-015 | pairwise LIVE/IN/IN/NONE/ACTIVE/1/NO: stato LIVE; validità [-1d, +1d); tier ammessi GOLD/PLATINUM, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 1 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 0 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-016 | base valida: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-017 | stato DRAFT: stato DRAFT; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-018 | stato IN_REVIEW: stato IN_REVIEW; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-019 | stato APPROVED: stato APPROVED; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-020 | stato PAUSED: stato PAUSED; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-021 | stato ENDED: stato ENDED; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-022 | stato ARCHIVED: stato ARCHIVED; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-023 | validità non ancora iniziata: stato LIVE; validità [+1d, +2d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-024 | validità terminata: stato LIVE; validità [-2d, -1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-025 | validità senza estremi: stato LIVE; validità [—, —); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-026 | tier non ammesso: stato LIVE; validità [-1d, +1d); tier ammessi GOLD/PLATINUM, membro SILVER; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, lucchetto GOLD/PLATINUM, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 TIER_NOT_ELIGIBLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-027 | tier ammesso: stato LIVE; validità [-1d, +1d); tier ammessi GOLD/PLATINUM, membro PLATINUM; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-028 | fuori segmento: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in SEG-TB-B; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-029 | membro senza segmenti, premio segmentato: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-030 | nel segmento (uno di due ammessi): stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti SEG-TB-A\|SEG-TB-C, membro in SEG-TB-C; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-031 | membro INACTIVE: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro INACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 MEMBER_NOT_ACTIVE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-032 | membro BLOCKED: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro BLOCKED; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 MEMBER_NOT_ACTIVE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-033 | membro ANONYMIZED: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ANONYMIZED; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 MEMBER_NOT_ACTIVE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-034 | membro sconosciuto: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro sconosciuto (nessuno snapshot); stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 MEMBER_NOT_ACTIVE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-035 | stock esaurito: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 0 | nel catalogo, senza lucchetto, stockState SOLD_OUT, perMemberLimitReached false; richiesta 422 REWARD_SOLD_OUT, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-036 | stock illimitato: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock illimitato | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-037 | limite per membro raggiunto: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; limite 1, già attive 1 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached true; richiesta 422 MEMBER_LIMIT_REACHED, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-038 | premio inesistente: codice premio inesistente, membro ACTIVE | dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-039 | premio fisico senza spedizione: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; tipo PHYSICAL senza spedizione | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 SHIPPING_REQUIRED, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-040 | premio fisico con spedizione: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; tipo PHYSICAL con spedizione | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-041 | premio fisico con spedizione vuota {}: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; tipo PHYSICAL senza spedizione | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 422 SHIPPING_REQUIRED, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-042 | premio esperienza senza spedizione: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; tipo EXPERIENCE senza spedizione | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-043 | inizio validità = adesso: stato LIVE; validità [0, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | **Q-275** (scelta di oggi, da decidere) — nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 · Q-275 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-044 | inizio validità = adesso + 1 ms: stato LIVE; validità [+1ms, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-045 | inizio validità = adesso − 1 ms: stato LIVE; validità [-1ms, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-046 | fine validità = adesso: stato LIVE; validità [-1d, 0); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | **Q-275** (scelta di oggi, da decidere) — escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 · Q-275 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-047 | fine validità = adesso + 1 ms: stato LIVE; validità [-1d, +1ms); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-048 | fine validità = adesso − 1 ms: stato LIVE; validità [-1d, -1ms); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-049 | fine alla mezzanotte di Roma, adesso 23:59:59: stato LIVE; validità [2026-09-01T00:00:00Z, 2026-09-30T22:00:00Z) con adesso = 2026-09-30T21:59:59Z; tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-050 | fine alla mezzanotte di Roma, adesso 00:00:01 del giorno dopo: stato LIVE; validità [2026-09-01T00:00:00Z, 2026-09-30T22:00:00Z) con adesso = 2026-09-30T22:00:01Z; tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-051 | cambio dell'ora di ottobre, adesso 23:59:59 del 25 (CET): stato LIVE; validità [2026-10-01T00:00:00Z, 2026-10-25T23:00:00Z) con adesso = 2026-10-25T22:59:59Z; tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-052 | cambio dell'ora di marzo, inizio 29/3 00:00 Roma, adesso 23:59:59 del 28: stato LIVE; validità [2026-03-28T23:00:00Z, 2026-04-30T22:00:00Z) con adesso = 2026-03-28T22:59:59Z; tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | escluso dal catalogo, dettaglio 404; richiesta 422 REWARD_NOT_AVAILABLE, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-053 | 29 febbraio: validità del solo giorno bisestile, adesso a metà giornata: stato LIVE; validità [2028-02-28T23:00:00Z, 2028-02-29T23:00:00Z) con adesso = 2028-02-29T11:00:00Z; tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-054 | ultimo pezzo (stock 1): stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 1 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 0 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-055 | limite 2, una richiesta attiva (limite − 1): stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; limite 2, già attive 1 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-056 | limite 2, due richieste attive (= limite): stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; limite 2, già attive 2 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached true; richiesta 422 MEMBER_LIMIT_REACHED, stock invariato, nessuna richiesta creata | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-057 | limite 1, nessuna richiesta: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; limite 1, già attive 0 | nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-058 | limite 1, solo richieste respinte/annullate: stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100; limite 1, già attive 0, chiuse 2 | **Q-279** (scelta di oggi, da decidere) — nel catalogo, senza lucchetto, stockState AVAILABLE, perMemberLimitReached false; richiesta 202 PENDING, stock → 99 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 · Q-279 | TestbookRwdEligibilityIT#visibility |
| TB-RWD-VIS-059 | stock basso (9 su 100): stato LIVE; validità [-1d, +1d); tier ammessi tutti, membro GOLD; segmenti tutti, membro in nessuno; membro ACTIVE; stock 100 | nel catalogo, senza lucchetto, stockState LOW, perMemberLimitReached false; richiesta 202 PENDING, stock → 8 | reward §3 (catalogo, 422), docs/03 §5, F-RWD-03/04, PT-03/04 | TestbookRwdEligibilityIT#visibility |

## 4. ORD — Precedenza dei controlli della richiesta

**Regola**: R-03. La specifica elenca i codici ma **non** la loro precedenza quando più condizioni sono vere; `docs/17` US-E05-05 riporta l'ordine del codice (MEMBER_NOT_ACTIVE → REWARD_NOT_AVAILABLE → TIER_NOT_ELIGIBLE → SHIPPING_REQUIRED → MEMBER_LIMIT_REACHED → REWARD_SOLD_OUT). **Strategia**: una riga per **coppia** di condizioni vere (C(6,2) = 15); ogni riga verifica l'insieme ammesso (specifica) e il codice di oggi (Q-273). Più le richieste malformate (400).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-ORD-001 | membro BLOCKED + premio PAUSED | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_NOT_AVAILABLE}; oggi MEMBER_NOT_ACTIVE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-002 | membro BLOCKED + tier non ammesso | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_NOT_ACTIVE, TIER_NOT_ELIGIBLE}; oggi MEMBER_NOT_ACTIVE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-003 | membro BLOCKED + fisico senza spedizione | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_NOT_ACTIVE, SHIPPING_REQUIRED}; oggi MEMBER_NOT_ACTIVE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-004 | membro BLOCKED + limite raggiunto | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_NOT_ACTIVE, MEMBER_LIMIT_REACHED}; oggi MEMBER_NOT_ACTIVE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-005 | membro BLOCKED + esaurito | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_NOT_ACTIVE, REWARD_SOLD_OUT}; oggi MEMBER_NOT_ACTIVE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-006 | premio PAUSED + tier non ammesso | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {REWARD_NOT_AVAILABLE, TIER_NOT_ELIGIBLE}; oggi REWARD_NOT_AVAILABLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-007 | premio PAUSED + fisico senza spedizione | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {REWARD_NOT_AVAILABLE, SHIPPING_REQUIRED}; oggi REWARD_NOT_AVAILABLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-008 | premio PAUSED + limite raggiunto | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {REWARD_NOT_AVAILABLE, MEMBER_LIMIT_REACHED}; oggi REWARD_NOT_AVAILABLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-009 | premio PAUSED + esaurito | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {REWARD_NOT_AVAILABLE, REWARD_SOLD_OUT}; oggi REWARD_NOT_AVAILABLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-010 | tier non ammesso + fisico senza spedizione | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {TIER_NOT_ELIGIBLE, SHIPPING_REQUIRED}; oggi TIER_NOT_ELIGIBLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-011 | tier non ammesso + limite raggiunto | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {TIER_NOT_ELIGIBLE, MEMBER_LIMIT_REACHED}; oggi TIER_NOT_ELIGIBLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-012 | tier non ammesso + esaurito | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {TIER_NOT_ELIGIBLE, REWARD_SOLD_OUT}; oggi TIER_NOT_ELIGIBLE (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-013 | fisico senza spedizione + limite raggiunto | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {SHIPPING_REQUIRED, MEMBER_LIMIT_REACHED}; oggi SHIPPING_REQUIRED (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-014 | fisico senza spedizione + esaurito | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {SHIPPING_REQUIRED, REWARD_SOLD_OUT}; oggi SHIPPING_REQUIRED (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-015 | limite raggiunto + esaurito | **Q-273** (scelta di oggi, da decidere) — 422 con code ∈ {MEMBER_LIMIT_REACHED, REWARD_SOLD_OUT}; oggi MEMBER_LIMIT_REACHED (primo nell'ordine dei controlli); stock invariato | reward §3 (errori 422), docs/03 §5; ordine in docs/17 US-E05-05 · Q-273 | TestbookRwdEligibilityIT#order |
| TB-RWD-ORD-016 | memberId assente in POST /v1/portal/redemptions | 400 bad-request, nessuna richiesta creata | docs/06 §2 (400), reward §3 | TestbookRwdEligibilityIT#malformed |
| TB-RWD-ORD-017 | rewardCode assente in POST /v1/portal/redemptions | 400 bad-request, nessuna richiesta creata | docs/06 §2 (400), reward §3 | TestbookRwdEligibilityIT#malformed |
| TB-RWD-ORD-018 | memberId e rewardCode solo spazi in POST /v1/portal/redemptions | 400 bad-request, nessuna richiesta creata | docs/06 §2 (400), reward §3 | TestbookRwdEligibilityIT#malformed |
| TB-RWD-ORD-019 | corpo assente in POST /v1/portal/redemptions | 400 bad-request, nessuna richiesta creata | docs/06 §2 (400), reward §3 | TestbookRwdEligibilityIT#malformed |

## 5. SNP — Snapshot del membro dai fatti

**Regola**: R-24 (reward §2 snapshot, §4 fatti consumati). **Strategia**: un caso per tipo di fatto che cambia l'eleggibilità (registrazione, stato in e out, salita e discesa di livello, ingresso e uscita da segmento).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-SNP-001 | membro ignoto → fatto member.registered (status ACTIVE) → richiesta | prima 422 MEMBER_NOT_ACTIVE, dopo il fatto 202 PENDING | reward §2 (snapshot da fatti), §4 | TestbookRwdEligibilityIT |
| TB-RWD-SNP-002 | membro ACTIVE → member.status.changed newStatus BLOCKED | catalogo invariato (premio visibile); richiesta 422 MEMBER_NOT_ACTIVE | docs/03 §2, reward §4 | TestbookRwdEligibilityIT |
| TB-RWD-SNP-003 | membro BLOCKED → member.status.changed newStatus ACTIVE | richiesta 202 | docs/03 §2, reward §4 | TestbookRwdEligibilityIT |
| TB-RWD-SNP-004 | SILVER, premio GOLD/PLATINUM → tier.upgraded newTier GOLD | lucchetto tolto, richiesta 202 | reward §4, §7 (lockedByTier) | TestbookRwdEligibilityIT |
| TB-RWD-SNP-005 | GOLD, premio GOLD → tier.downgraded newTier SILVER | lockedByTier {GOLD}, richiesta 422 TIER_NOT_ELIGIBLE | reward §4 | TestbookRwdEligibilityIT |
| TB-RWD-SNP-006 | premio per SEG-X, membro fuori → member.segment.entered SEG-X | premio compare nel catalogo, richiesta 202 | F-RWD-04 (P1 segmenti), reward §4 | TestbookRwdEligibilityIT |
| TB-RWD-SNP-007 | membro in SEG-X → member.segment.left SEG-X | premio escluso, richiesta 422 REWARD_NOT_AVAILABLE | F-RWD-04, reward §3 (esclude fuori segmento) | TestbookRwdEligibilityIT |

## 6. STK — Stock

**Regole**: R-02, R-04, R-26. Domini: totale {illimitato, 0, 1, 5, 10, 11, 100}, residuo {−1, 0, 1, 9, 10, 11, 100}; confine del 10 % provato a 10/100 e 1/10 (esattamente 10 % ⇒ non LOW), 9/100 e 1/11 (sotto). **Strategia**: valori limite da soli (unit, logica pura), concorrenza e sequenza sull'ultimo pezzo, statistiche.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-STK-001 | stock illimitato | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-002 | stock pieno 100/100 | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-003 | stock 11/100 (11 %) | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-004 | stock 10/100 (10 % esatto) | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-005 | stock 9/100 (sotto il 10 %) | stockState LOW | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-006 | stock 1/100 | stockState LOW | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-007 | stock 0/100 | stockState SOLD_OUT | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-008 | stock 1/10 (10 % esatto) | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-009 | stock 1/11 (9,1 %) | stockState LOW | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-010 | stock 1/1 (ultimo pezzo) | stockState AVAILABLE | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-011 | stock 0/0 (creato esaurito) | stockState SOLD_OUT | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-012 | stock residuo negativo −1/5 (dato incoerente) | **Q-280** (scelta di oggi, da decidere) — stockState SOLD_OUT | reward §3 (stockState AVAILABLE/LOW/SOLD_OUT), §3 stats (< 10 %), BO-10 note · Q-280 | TestbookRwdRulesTest#stockState |
| TB-RWD-STK-020 | stock 1, due richieste concorrenti di due membri | una 202 PENDING e una 422 REWARD_SOLD_OUT; residuo 0 | reward §5, §7 (accettazione 3) | TestbookRwdEligibilityIT |
| TB-RWD-STK-021 | stock 1, due richieste in sequenza | prima 202, seconda 422 REWARD_SOLD_OUT; catalogo SOLD_OUT | reward §5, PT-03 | TestbookRwdEligibilityIT |
| TB-RWD-STK-022 | GET /v1/rewards/stats con premi 9/100, 0/100, 10/100 e illimitato | lowStock contiene 9/100 e 0/100, non 10/100 né l'illimitato | reward §3 (stats: stock sotto soglia < 10 %) | TestbookRwdCatalogIT |
| TB-RWD-STK-023 | GET /v1/rewards/stats dopo la creazione di un premio DRAFT | rewardsByStatus.DRAFT cresce di 1; redemptionsByStatus presente | reward §3 (stats) | TestbookRwdCatalogIT |

## 7. CAT — Creazione premio, categorie, ruoli

**Regole**: R-22, R-23. Domini: tipo (5 valori + sconosciuto + minuscolo), evasione (3 + sconosciuto), coppie tipo/evasione (AUTO_COUPON solo COUPON), nome (valorizzato, vuoto, assente), fascia (esistente, assente, inesistente), categoria (esistente, assente, inesistente), stock (assente, −1, 0, 1), limite (assente, −1, 0, 1), validità (validTo = validFrom, +1 ms, < validFrom), codice (assente, vuoto, duplicato), ruolo (7). **Strategia**: base valida + ogni valore da solo (guasto singolo), ogni valore di enumerato da solo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-CAT-001 | POST /v1/rewards — valido minimo (DIGITAL, INSTANT, F1) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-002 | POST /v1/rewards — tipo PHYSICAL con evasione MANUAL (type=PHYSICAL;fulfilment=MANUAL) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-003 | POST /v1/rewards — tipo COUPON con AUTO_COUPON e pool (type=COUPON;fulfilment=AUTO_COUPON;couponPoolId=<pool>) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-004 | POST /v1/rewards — tipo DONATION con INSTANT (type=DONATION;fulfilment=INSTANT) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-005 | POST /v1/rewards — tipo EXPERIENCE con MANUAL (type=EXPERIENCE;fulfilment=MANUAL) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-006 | POST /v1/rewards — tipo sconosciuto VOUCHER (type=VOUCHER) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-007 | POST /v1/rewards — tipo minuscolo con spazi « coupon » (type= coupon ;fulfilment=AUTO_COUPON;couponPoolId=<pool>) | **Q-281** (scelta di oggi, da decidere) — 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit · Q-281 | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-008 | POST /v1/rewards — evasione sconosciuta AUTO (fulfilment=AUTO) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-009 | POST /v1/rewards — AUTO_COUPON su premio PHYSICAL (type=PHYSICAL;fulfilment=AUTO_COUPON) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-010 | POST /v1/rewards — AUTO_COUPON senza pool (type=COUPON;fulfilment=AUTO_COUPON) | **Q-281** (scelta di oggi, da decidere) — 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit · Q-281 | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-011 | POST /v1/rewards — nome solo spazi (name=<blank>) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-012 | POST /v1/rewards — nome assente (name=<null>) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-013 | POST /v1/rewards — fascia assente (band=<null>) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-014 | POST /v1/rewards — fascia inesistente F9 (band=F9) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-015 | POST /v1/rewards — categoria inesistente (category=NOPE) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-016 | POST /v1/rewards — categoria assente (category=<null>) | **Q-281** (scelta di oggi, da decidere) — 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit · Q-281 | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-017 | POST /v1/rewards — stockTotal −1 (stockTotal=-1) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-018 | POST /v1/rewards — stockTotal 0 (stockTotal=0) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-019 | POST /v1/rewards — stockTotal 1 (stockTotal=1) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-020 | POST /v1/rewards — stockTotal assente (illimitato) (stockTotal=<null>) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-021 | POST /v1/rewards — perMemberLimit 0 (perMemberLimit=0) | **Q-281** (scelta di oggi, da decidere) — 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit · Q-281 | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-022 | POST /v1/rewards — perMemberLimit 1 (perMemberLimit=1) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-023 | POST /v1/rewards — perMemberLimit −1 (perMemberLimit=-1) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-024 | POST /v1/rewards — validTo = validFrom (validFrom=2026-10-01T00:00:00Z;validTo=2026-10-01T00:00:00Z) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-025 | POST /v1/rewards — validTo = validFrom + 1 ms (validFrom=2026-10-01T00:00:00Z;validTo=2026-10-01T00:00:00.001Z) | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-026 | POST /v1/rewards — validTo < validFrom (validFrom=2026-10-01T00:00:00Z;validTo=2026-09-30T00:00:00Z) | 422 REWARD_INVALID | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-027 | POST /v1/rewards — codice assente (code=<null>) | 400 bad-request | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-028 | POST /v1/rewards — codice solo spazi (code=<blank>) | 400 bad-request | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-029 | POST /v1/rewards — codice già esistente (code=<dup>) | 409 CODE_TAKEN | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-030 | POST /v1/rewards — ruolo ADMIN | 201, stato DRAFT, version 0, stockRemaining = stockTotal | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-031 | POST /v1/rewards — ruolo LEGAL | 403 forbidden-role | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-032 | POST /v1/rewards — ruolo CARE | 403 forbidden-role | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-033 | POST /v1/rewards — ruolo ANALYST | 403 forbidden-role | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-034 | POST /v1/rewards — intestazione X-LH-Actor assente | 403 forbidden-role | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-035 | POST /v1/rewards — intestazione X-LH-Actor non valida | 403 forbidden-role | reward §2, §3; F-RWD-01/03/08; docs/06 §2–3; docs/08 §2 object.edit | TestbookRwdCatalogIT#create |
| TB-RWD-CAT-040 | categoria: categoria valida (ruolo MARKETING) | 201 creata | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |
| TB-RWD-CAT-041 | categoria: codice solo spazi (ruolo MARKETING) | 422 CATEGORY_INVALID | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |
| TB-RWD-CAT-042 | categoria: nome assente (ruolo MARKETING) | 422 CATEGORY_INVALID | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |
| TB-RWD-CAT-043 | categoria: modifica (PUT) di una categoria esistente (ruolo ADMIN) | 200, nome aggiornato | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |
| TB-RWD-CAT-044 | categoria: ruolo CARE (ruolo CARE) | 403 | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |
| TB-RWD-CAT-045 | categoria: ruolo ANALYST (ruolo ANALYST) | 403 | F-RWD-01, reward §3 (reward-categories), docs/08 §2 | TestbookRwdCatalogIT#category |

## 8. EDT — Modifica, campi bloccati in LIVE, versione e stock, duplica

**Regole**: R-19, R-20, R-21. Nota di precedenza: `docs/03 §3.6` indica come campi «sicuri» degli oggetti governati nome, descrizione, fine, priorità, immagine; `reward §3` (fonte n. 3, prevale su docs/03, n. 4) restringe per i premi a `stock_total`, `valid_to`, `image_url`: le righe seguono reward §3 (conflitto segnalato in §19). **Strategia**: tabella completa stato (7) × tipo di modifica (campo bloccato / campo sicuro) = 14 righe (EDT-001…014); in LIVE ogni campo da solo, più il reinvio dello stesso valore (EDT-015…028); ruoli e casi limite. Stock e versione: residuo atteso = max(0, nuovo totale − prenotazioni in corso), valori limite a totale = prenotate, sotto, zero; lettura della versione prima e dopo una prenotazione (M7.6).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-EDT-001 | PUT /v1/rewards/{id} — cambio nome in DRAFT (name=Nome nuovo) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-002 | PUT /v1/rewards/{id} — cambio stock totale in DRAFT (stockTotal=20) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-003 | PUT /v1/rewards/{id} — cambio nome in IN_REVIEW (name=Nome nuovo) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-004 | PUT /v1/rewards/{id} — cambio stock totale in IN_REVIEW (stockTotal=20) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-005 | PUT /v1/rewards/{id} — cambio nome in APPROVED (name=Nome nuovo) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-006 | PUT /v1/rewards/{id} — cambio stock totale in APPROVED (stockTotal=20) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-007 | PUT /v1/rewards/{id} — cambio nome in LIVE (name=Nome nuovo) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-008 | PUT /v1/rewards/{id} — cambio stock totale in LIVE (stockTotal=20) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-009 | PUT /v1/rewards/{id} — cambio nome in PAUSED (name=Nome nuovo) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-010 | PUT /v1/rewards/{id} — cambio stock totale in PAUSED (stockTotal=20) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-011 | PUT /v1/rewards/{id} — cambio nome in ENDED (name=Nome nuovo) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-012 | PUT /v1/rewards/{id} — cambio stock totale in ENDED (stockTotal=20) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-013 | PUT /v1/rewards/{id} — cambio nome in ARCHIVED (name=Nome nuovo) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-014 | PUT /v1/rewards/{id} — cambio stock totale in ARCHIVED (stockTotal=20) | 409 conflict (modifica non ammessa nello stato) | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-015 | PUT /v1/rewards/{id} — LIVE: cambio description (description=Nuova descrizione) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-016 | PUT /v1/rewards/{id} — LIVE: cambio terms (terms=Nuovi termini) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-017 | PUT /v1/rewards/{id} — LIVE: cambio type (type=EXPERIENCE) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-018 | PUT /v1/rewards/{id} — LIVE: cambio category (category=CASA) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-019 | PUT /v1/rewards/{id} — LIVE: cambio band (band=F2) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-020 | PUT /v1/rewards/{id} — LIVE: cambio fulfilment (fulfilment=MANUAL) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-021 | PUT /v1/rewards/{id} — LIVE: cambio couponPoolId (couponPoolId=<pool>) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-022 | PUT /v1/rewards/{id} — LIVE: cambio perMemberLimit (perMemberLimit=3) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-023 | PUT /v1/rewards/{id} — LIVE: cambio eligibleTiers (eligibleTiers=GOLD) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-024 | PUT /v1/rewards/{id} — LIVE: cambio eligibleSegments (eligibleSegments=SEG-X) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-025 | PUT /v1/rewards/{id} — LIVE: cambio validFrom (validFrom=-2d) | 409 REWARD_LIVE_LOCKED | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-026 | PUT /v1/rewards/{id} — LIVE: cambio validTo (validTo=+60d) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-027 | PUT /v1/rewards/{id} — LIVE: cambio imageUrl (imageUrl=/demo/nuova.png) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-028 | PUT /v1/rewards/{id} — LIVE: nome reinviato uguale (name=Premio testbook) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-029 | PUT /v1/rewards/{id} — cambio del codice (code=<other>) | **Q-281** (scelta di oggi, da decidere) — 409 CODE_IMMUTABLE | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 · Q-281 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-030 | PUT /v1/rewards/{id} — PAUSED: cambio fascia, tipo, evasione (band=F2;type=EXPERIENCE;fulfilment=MANUAL) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-031 | PUT /v1/rewards/{id} — DRAFT: stock negativo (stockTotal=-1) | 422 REWARD_INVALID | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-032 | PUT /v1/rewards/{id} — ruolo ADMIN (name=Nome nuovo) | 200, modifica salvata, version +1 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-033 | PUT /v1/rewards/{id} — ruolo LEGAL (name=Nome nuovo) | 403 forbidden-role | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-034 | PUT /v1/rewards/{id} — ruolo CARE (name=Nome nuovo) | 403 forbidden-role | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-035 | PUT /v1/rewards/{id} — ruolo ANALYST (name=Nome nuovo) | 403 forbidden-role | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-036 | PUT /v1/rewards/{id} — premio inesistente (name=Nome nuovo) | 404 | reward §3 (modifica ammessa in DRAFT/REJECTED/PAUSED; in LIVE solo stock_total, valid_to, image_url), docs/06 §2, docs/08 §3.2 | TestbookRwdCatalogIT#edit |
| TB-RWD-EDT-040 | premio LIVE: totale 10, nessuna prenotazione, nuovo totale 15 | 200; stockTotal 15, stockRemaining 15 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-041 | premio LIVE: totale 10, 3 prenotate, nuovo totale 15 | 200; stockTotal 15, stockRemaining 12 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-042 | premio LIVE: totale 10, 3 prenotate, stesso totale | 200; stockTotal 10, stockRemaining 7 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-043 | premio LIVE: totale 10, 3 prenotate, nuovo totale 5 | 200; stockTotal 5, stockRemaining 2 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-044 | premio LIVE: totale 10, 3 prenotate, nuovo totale 3 (= prenotate) | 200; stockTotal 3, stockRemaining 0 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-045 | premio LIVE: totale 10, 3 prenotate, nuovo totale 2 (< prenotate) | 200; stockTotal 2, stockRemaining 0 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-046 | premio LIVE: totale 10, 3 prenotate, nuovo totale 0 | 200; stockTotal 0, stockRemaining 0 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-047 | premio LIVE: totale 10, prenotazione arrivata dopo la lettura della versione, nuovo totale 20 | 200; stockTotal 20, stockRemaining 19 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-048 | premio LIVE: versione superata da un'altra modifica | 409; stockTotal 10, stockRemaining 10 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-049 | premio LIVE: PUT senza version (ultima scrittura vince) | **Q-280** (scelta di oggi, da decidere) — 200; stockTotal 12, stockRemaining 12 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) · Q-280 | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-050 | premio LIVE: totale 10, 3 prenotate, stockTotal omesso | 200; stockTotal 10, stockRemaining 7 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-051 | premio LIVE: illimitato con 2 richieste in corso, nuovo totale 10 | **Q-280** (scelta di oggi, da decidere) — 200; stockTotal 10, stockRemaining 10 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) · Q-280 | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-052 | premio LIVE: nuovo totale −1 | 422; stockTotal 10, stockRemaining 10 | reward §3 (LIVE: stock_total modificabile), §5 (stock prenotato); docs/12 M7.6; Q-112 (409 VERSION_CONFLICT) | TestbookRwdCatalogIT#stockRecalculation |
| TB-RWD-EDT-053 | totale 5, 3 prenotate → totale 2 (residuo 0) → una richiesta respinta dal wallet | **Q-280** (scelta di oggi, da decidere) — residuo 1 (ripristino +1 fino al totale) | docs/03 §5 (REJECTED ripristina lo stock) · Q-280 | TestbookRwdCatalogIT |
| TB-RWD-EDT-060 | POST /v1/rewards/{id}/duplicate su un DRAFT | 201, copia con codice <code>-COPY in DRAFT, stessi campi, version 0 | reward §3 (duplicate), F-CMP-13, docs/12 M7.6 | TestbookRwdCatalogIT |
| TB-RWD-EDT-061 | seconda duplica dello stesso premio | **Q-281** (scelta di oggi, da decidere) — 201, codice <code>-COPY2 | reward §3, Q-113 · Q-281 | TestbookRwdCatalogIT |
| TB-RWD-EDT-062 | duplica di un LIVE con 3 prenotazioni | copia DRAFT con stockRemaining = stockTotal (pieno) | reward §3 (copia in DRAFT) | TestbookRwdCatalogIT |
| TB-RWD-EDT-063 | duplica con ruolo CARE | 403 | docs/08 §2 object.edit | TestbookRwdCatalogIT |

## 9. BND — Fasce

**Regole**: R-05, R-06, R-23. Ogni riga crea una scala nuova L1 < L2 < L3 (ordini e soglie sopra tutte le fasce esistenti) e prova la soglia della fascia centrale o di una fascia nuova ai valori limite L1−1, L1, L1+1, L3−1, L3, L3+1, invariata, 0, −1, e una fascia nuova sotto L1. **Strategia**: valori limite da soli; eliminazione per contenuto (premio DRAFT, solo ARCHIVED, vuota, inesistente); ruoli.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-BND-001 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia in cima, soglia L3+1 | 201 creata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-002 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia in cima, soglia = L3 | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-003 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia in cima, soglia L3−1 (non crescente) | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-004 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L1 | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-005 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L1−1 | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-006 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L1+1 | 200 salvata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-007 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L3−1 | 200 salvata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-008 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L3 | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-009 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale portata a L3+1 | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-010 | scala L1 < L2 < L3 (sortOrder crescente): fascia centrale risalvata con la stessa soglia | 200 salvata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-011 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia con la soglia di F1 (500) | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-012 | scala L1 < L2 < L3 (sortOrder crescente): soglia 0 | **Q-281** (scelta di oggi, da decidere) — 422 BAND_INVALID | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 · Q-281 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-013 | scala L1 < L2 < L3 (sortOrder crescente): soglia −1 | 422 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-014 | scala L1 < L2 < L3 (sortOrder crescente): codice solo spazi | 422 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-015 | scala L1 < L2 < L3 (sortOrder crescente): nome assente | 422 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-016 | scala L1 < L2 < L3 (sortOrder crescente): ruolo LEGAL | 403 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-017 | scala L1 < L2 < L3 (sortOrder crescente): ruolo CARE | 403 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-018 | scala L1 < L2 < L3 (sortOrder crescente): intestazione assente (ANALYST) | 403 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-019 | scala L1 < L2 < L3 (sortOrder crescente): ruolo ADMIN | 201 creata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-020 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia sotto L1 con soglia L1+5 (ordine inverso) | 422 BAND_THRESHOLD_DUPLICATE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-021 | scala L1 < L2 < L3 (sortOrder crescente): nuova fascia sotto L1 con soglia L1−1 | 201 creata | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-022 | scala L1 < L2 < L3 (sortOrder crescente): elimina fascia con un premio DRAFT | 409 BAND_IN_USE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-023 | scala L1 < L2 < L3 (sortOrder crescente): elimina fascia con un solo premio ARCHIVED | 409 BAND_IN_USE | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-024 | scala L1 < L2 < L3 (sortOrder crescente): elimina fascia vuota | 204, non più in elenco | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-025 | scala L1 < L2 < L3 (sortOrder crescente): elimina fascia inesistente | 404 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-026 | scala L1 < L2 < L3 (sortOrder crescente): elimina fascia vuota con ruolo CARE | 403 | reward §3 (soglie uniche e crescenti, 422 BAND_THRESHOLD_DUPLICATE, 409 BAND_IN_USE), F-RWD-02, BO-11, docs/08 §2 | TestbookRwdCatalogIT#bands |
| TB-RWD-BND-040 | premio LIVE nella fascia L2; soglia di L2 cambiata | catalogo portale: pointsCost = nuova soglia (una fascia = un prezzo) | docs/03 §5, F-RWD-02, US-E05-02 | TestbookRwdCatalogIT |
| TB-RWD-BND-041 | richiesta prima e dopo il cambio di soglia | la prima conserva pointsCost vecchio, la seconda ha il nuovo; il fatto requested porta il costo | docs/03 §5, reward §2 (redemption.points_cost), §5 | TestbookRwdCatalogIT |
| TB-RWD-BND-042 | catalogo portale con la scala L1, L2, L3 | fasce in ordine di soglia crescente, pointsThreshold esposto | PT-03 (fasce in ordine di soglia), reward §3 portale | TestbookRwdCatalogIT |

## 10. LCY — Ciclo di vita e approvazione

**Regola**: R-18, R-23. Policy `REWARD`: approvazione **sempre**, ruolo LEGAL (docs/06 §7). **Strategia**: macchina a stati completa 7 stati × 8 azioni = 56 righe (ruolo autorizzato per l'azione, commento presente), più REJECT senza commento e con soli spazi; matrice dei ruoli: per ogni azione, dallo stato in cui è valida, gli altri 4 ruoli (32 righe) più intestazione assente e non valida su SUBMIT e APPROVE (4); approvazione spenta (3); enumerato: minuscolo e sconosciuto. Il cablaggio via API (controller, storico, fatto, coda BO-21) è provato da LCY-101…123.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-LCY-001 | DRAFT × SUBMIT (MARKETING, approvazione LEGAL attiva) | → IN_REVIEW | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-002 | DRAFT × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-003 | DRAFT × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-004 | DRAFT × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 APPROVAL_REQUIRED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-005 | DRAFT × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-006 | DRAFT × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-007 | DRAFT × END (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-008 | DRAFT × ARCHIVE (MARKETING, approvazione LEGAL attiva) | → ARCHIVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-009 | IN_REVIEW × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-010 | IN_REVIEW × APPROVE (LEGAL, approvazione LEGAL attiva) | → APPROVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-011 | IN_REVIEW × REJECT (LEGAL, approvazione LEGAL attiva) | → DRAFT | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-012 | IN_REVIEW × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-013 | IN_REVIEW × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-014 | IN_REVIEW × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-015 | IN_REVIEW × END (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-016 | IN_REVIEW × ARCHIVE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-017 | APPROVED × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-018 | APPROVED × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-019 | APPROVED × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-020 | APPROVED × PUBLISH (MARKETING, approvazione LEGAL attiva) | → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-021 | APPROVED × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-022 | APPROVED × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-023 | APPROVED × END (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-024 | APPROVED × ARCHIVE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-025 | LIVE × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-026 | LIVE × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-027 | LIVE × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-028 | LIVE × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-029 | LIVE × PAUSE (MARKETING, approvazione LEGAL attiva) | → PAUSED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-030 | LIVE × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-031 | LIVE × END (MARKETING, approvazione LEGAL attiva) | → ENDED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-032 | LIVE × ARCHIVE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-033 | PAUSED × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-034 | PAUSED × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-035 | PAUSED × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-036 | PAUSED × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-037 | PAUSED × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-038 | PAUSED × RESUME (MARKETING, approvazione LEGAL attiva) | → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-039 | PAUSED × END (MARKETING, approvazione LEGAL attiva) | → ENDED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-040 | PAUSED × ARCHIVE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-041 | ENDED × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-042 | ENDED × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-043 | ENDED × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-044 | ENDED × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-045 | ENDED × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-046 | ENDED × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-047 | ENDED × END (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-048 | ENDED × ARCHIVE (MARKETING, approvazione LEGAL attiva) | → ARCHIVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-049 | ARCHIVED × SUBMIT (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-050 | ARCHIVED × APPROVE (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-051 | ARCHIVED × REJECT (LEGAL, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-052 | ARCHIVED × PUBLISH (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-053 | ARCHIVED × PAUSE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-054 | ARCHIVED × RESUME (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-055 | ARCHIVED × END (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-056 | ARCHIVED × ARCHIVE (MARKETING, approvazione LEGAL attiva) | 409 INVALID_TRANSITION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-057 | IN_REVIEW × REJECT senza commento | 422 REJECT_COMMENT_REQUIRED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-058 | IN_REVIEW × REJECT con commento di soli spazi | 422 REJECT_COMMENT_REQUIRED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-059 | SUBMIT da DRAFT con ruolo ADMIN | → IN_REVIEW | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-060 | SUBMIT da DRAFT con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-061 | SUBMIT da DRAFT con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-062 | SUBMIT da DRAFT con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-063 | APPROVE da IN_REVIEW con ruolo ADMIN (override) | → APPROVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-064 | APPROVE da IN_REVIEW con ruolo MARKETING | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-065 | APPROVE da IN_REVIEW con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-066 | APPROVE da IN_REVIEW con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-067 | REJECT da IN_REVIEW con ruolo ADMIN (override) | → DRAFT | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-068 | REJECT da IN_REVIEW con ruolo MARKETING | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-069 | REJECT da IN_REVIEW con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-070 | REJECT da IN_REVIEW con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-071 | PUBLISH da APPROVED con ruolo ADMIN | → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-072 | PUBLISH da APPROVED con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-073 | PUBLISH da APPROVED con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-074 | PUBLISH da APPROVED con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-075 | PAUSE da LIVE con ruolo ADMIN | → PAUSED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-076 | PAUSE da LIVE con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-077 | PAUSE da LIVE con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-078 | PAUSE da LIVE con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-079 | RESUME da PAUSED con ruolo ADMIN | → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-080 | RESUME da PAUSED con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-081 | RESUME da PAUSED con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-082 | RESUME da PAUSED con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-083 | END da LIVE con ruolo ADMIN | → ENDED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-084 | END da LIVE con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-085 | END da LIVE con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-086 | END da LIVE con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-087 | ARCHIVE da ENDED con ruolo ADMIN | → ARCHIVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-088 | ARCHIVE da ENDED con ruolo LEGAL | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-089 | ARCHIVE da ENDED con ruolo CARE | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-090 | ARCHIVE da ENDED con ruolo ANALYST | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-091 | SUBMIT senza intestazione X-LH-Actor (ANALYST:anonymous) | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-092 | SUBMIT con intestazione non valida (→ ANALYST) | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-093 | APPROVE senza intestazione X-LH-Actor (ANALYST:anonymous) | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-094 | APPROVE con intestazione non valida (→ ANALYST) | 403 FORBIDDEN_ROLE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-095 | approvazione spenta: SUBMIT da DRAFT pubblica | **Q-282** (scelta di oggi, da decidere) — → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 · Q-282 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-096 | approvazione spenta: PUBLISH da DRAFT | → LIVE | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-097 | approvazione spenta: APPROVE da IN_REVIEW resta possibile | → APPROVED | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-098 | azione in minuscolo | **Q-282** (scelta di oggi, da decidere) — → IN_REVIEW | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 · Q-282 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-099 | azione sconosciuta | **Q-282** (scelta di oggi, da decidere) — 422 INVALID_ACTION | docs/03 §3.6, docs/06 §3 e §7 (REWARD: LEGAL sempre), docs/08 §2–3.3, F-RWD-08, F-APR-01/02 · Q-282 | TestbookRwdLifecycleTest#transition |
| TB-RWD-LCY-101 | POST /v1/rewards/{id}/transitions: DRAFT × SUBMIT, ruolo MARKETING | 200, stato IN_REVIEW | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-102 | POST /v1/rewards/{id}/transitions: IN_REVIEW × APPROVE, ruolo LEGAL | 200, stato APPROVED | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-103 | POST /v1/rewards/{id}/transitions: IN_REVIEW × REJECT, ruolo LEGAL, commento | 200, stato DRAFT | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-104 | POST /v1/rewards/{id}/transitions: IN_REVIEW × REJECT, ruolo LEGAL | 422 REJECT_COMMENT_REQUIRED, stato IN_REVIEW | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-105 | POST /v1/rewards/{id}/transitions: IN_REVIEW × APPROVE, ruolo MARKETING | 403 FORBIDDEN_ROLE, stato IN_REVIEW | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-106 | POST /v1/rewards/{id}/transitions: DRAFT × SUBMIT, ruolo CARE | 403 FORBIDDEN_ROLE, stato DRAFT | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-107 | POST /v1/rewards/{id}/transitions: DRAFT × SUBMIT, ruolo - | 403 FORBIDDEN_ROLE, stato DRAFT | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-108 | POST /v1/rewards/{id}/transitions: DRAFT × PUBLISH, ruolo MARKETING | 409 APPROVAL_REQUIRED, stato DRAFT | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-109 | POST /v1/rewards/{id}/transitions: APPROVED × PUBLISH, ruolo MARKETING | 200, stato LIVE | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-110 | POST /v1/rewards/{id}/transitions: IN_REVIEW × APPROVE, ruolo ADMIN | 200, stato APPROVED | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-111 | POST /v1/rewards/{id}/transitions: LIVE × PAUSE, ruolo MARKETING | 200, stato PAUSED | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-112 | POST /v1/rewards/{id}/transitions: PAUSED × RESUME, ruolo MARKETING | 200, stato LIVE | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-113 | POST /v1/rewards/{id}/transitions: LIVE × END, ruolo MARKETING | 200, stato ENDED | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-114 | POST /v1/rewards/{id}/transitions: ENDED × ARCHIVE, ruolo MARKETING | 200, stato ARCHIVED | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-115 | POST /v1/rewards/{id}/transitions: LIVE × SUBMIT, ruolo MARKETING | 409 INVALID_TRANSITION, stato LIVE | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-116 | POST /v1/rewards/{id}/transitions: UNKNOWN × SUBMIT, ruolo MARKETING | 404 | docs/03 §3.6, docs/06 §7, docs/08 §2 | TestbookRwdCatalogIT#lifecycleApi |
| TB-RWD-LCY-120 | SUBMIT (MARKETING) poi REJECT (LEGAL, commento) | storico: due voci con azione, attore e commento; stato DRAFT | docs/03 §3.6 (storico chi, quando, commento) | TestbookRwdCatalogIT |
| TB-RWD-LCY-121 | PUBLISH di un premio APPROVED | fatto reward.status.changed {rewardCode, previousStatus APPROVED, newStatus LIVE} | reward §4, Q-53 | TestbookRwdCatalogIT |
| TB-RWD-LCY-122 | premio IN_REVIEW → GET /v1/approvals?status=IN_REVIEW | voce {entityType REWARD, code, requiredRole LEGAL} | docs/06 §7 (formato comune), BO-21 | TestbookRwdCatalogIT |
| TB-RWD-LCY-123 | REJECT → DRAFT, poi modifica del nome e nuovo SUBMIT | PUT 200 in DRAFT, SUBMIT 200 → IN_REVIEW | docs/03 §3.6, reward §3 (modifica in DRAFT) | TestbookRwdCatalogIT |

## 11. SAG — Saga della richiesta: stato × evento

**Regole**: R-07…R-13. Stati di partenza (7): PENDING; CONFIRMED (MANUAL); CONFIRMED con needsAttention; FULFILLED; REJECTED; CANCELLED dal membro; CANCELLED con rimborso. Eventi (7): `wallet.points.spent`, `wallet.spend.rejected`, job di timeout, annullo del membro, annullo CARE, evasione CARE, retry-fulfilment. **Strategia**: tabella completa 7 × 7 = 49 righe (≤ 64), comprese le transizioni vietate col loro esito; ogni riga verifica risposta, stato finale, fatti nuovi (outbox) e stock.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-SAG-001 | PENDING × wallet.points.spent | stato CONFIRMED; fatto reward.redemption.confirmed; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-002 | PENDING × wallet.spend.rejected | stato REJECTED; fatto reward.redemption.rejected reason INSUFFICIENT_BALANCE; stock +1 | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-003 | PENDING × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato REJECTED; fatto reward.redemption.rejected reason TIMEOUT; stock +1 | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-004 | PENDING × annullo del membro (portale) | HTTP 200, stato CANCELLED; fatto reward.redemption.cancelled refund=false; stock +1 | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-005 | PENDING × annullo con rimborso (CARE, motivo) | HTTP 409, stato PENDING; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-006 | PENDING × evasione manuale (CARE, nota) | HTTP 409, stato PENDING; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-007 | PENDING × retry-fulfilment (CARE) | HTTP 409, stato PENDING; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-008 | CONFIRMED (evasione MANUAL) × wallet.points.spent | stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-009 | CONFIRMED (evasione MANUAL) × wallet.spend.rejected | stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-010 | CONFIRMED (evasione MANUAL) × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-011 | CONFIRMED (evasione MANUAL) × annullo del membro (portale) | HTTP 409, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-012 | CONFIRMED (evasione MANUAL) × annullo con rimborso (CARE, motivo) | HTTP 200, stato CANCELLED; fatto reward.redemption.cancelled refund=true; stock +1 | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-013 | CONFIRMED (evasione MANUAL) × evasione manuale (CARE, nota) | HTTP 200, stato FULFILLED; fatto reward.redemption.fulfilled; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-014 | CONFIRMED (evasione MANUAL) × retry-fulfilment (CARE) | HTTP 409, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-015 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × wallet.points.spent | stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-016 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × wallet.spend.rejected | stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-017 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-018 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × annullo del membro (portale) | HTTP 409, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-019 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × annullo con rimborso (CARE, motivo) | HTTP 200, stato CANCELLED; fatto reward.redemption.cancelled refund=true; stock +1 | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-020 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × evasione manuale (CARE, nota) | HTTP 409, stato CONFIRMED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-021 | CONFIRMED con needsAttention (AUTO_COUPON, pool vuoto) × retry-fulfilment (CARE) | HTTP 200, stato FULFILLED; fatto reward.redemption.fulfilled; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-022 | FULFILLED (INSTANT) × wallet.points.spent | stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-023 | FULFILLED (INSTANT) × wallet.spend.rejected | stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-024 | FULFILLED (INSTANT) × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-025 | FULFILLED (INSTANT) × annullo del membro (portale) | HTTP 409, stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-026 | FULFILLED (INSTANT) × annullo con rimborso (CARE, motivo) | HTTP 409, stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-027 | FULFILLED (INSTANT) × evasione manuale (CARE, nota) | HTTP 409, stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-028 | FULFILLED (INSTANT) × retry-fulfilment (CARE) | HTTP 409, stato FULFILLED; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-029 | REJECTED (INSUFFICIENT_BALANCE) × wallet.points.spent | stato REJECTED; fatto reward.redemption.cancelled refund=true; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-030 | REJECTED (INSUFFICIENT_BALANCE) × wallet.spend.rejected | stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-031 | REJECTED (INSUFFICIENT_BALANCE) × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-032 | REJECTED (INSUFFICIENT_BALANCE) × annullo del membro (portale) | HTTP 409, stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-033 | REJECTED (INSUFFICIENT_BALANCE) × annullo con rimborso (CARE, motivo) | HTTP 409, stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-034 | REJECTED (INSUFFICIENT_BALANCE) × evasione manuale (CARE, nota) | HTTP 409, stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-035 | REJECTED (INSUFFICIENT_BALANCE) × retry-fulfilment (CARE) | HTTP 409, stato REJECTED; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-036 | CANCELLED dal membro × wallet.points.spent | stato CANCELLED; fatto reward.redemption.cancelled refund=true; stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-037 | CANCELLED dal membro × wallet.spend.rejected | stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-038 | CANCELLED dal membro × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-039 | CANCELLED dal membro × annullo del membro (portale) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-040 | CANCELLED dal membro × annullo con rimborso (CARE, motivo) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-041 | CANCELLED dal membro × evasione manuale (CARE, nota) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-042 | CANCELLED dal membro × retry-fulfilment (CARE) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-043 | CANCELLED con rimborso (CARE) × wallet.points.spent | stato CANCELLED; fatto non verificato (la specifica tace); stock invariato | docs/03 §5, reward §5 (conferma, compensazione dopo timeout), §7 (rielaborazione) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-044 | CANCELLED con rimborso (CARE) × wallet.spend.rejected | stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (wallet.spend.rejected) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-045 | CANCELLED con rimborso (CARE) × job timeout, asOf = richiesta + 10 min + 1 s | HTTP 200, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5, reward §5 (timeout 10 min), Q-55 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-046 | CANCELLED con rimborso (CARE) × annullo del membro (portale) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | docs/03 §5 (PENDING → CANCELLED), reward §3 (solo PENDING), PT-13 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-047 | CANCELLED con rimborso (CARE) × annullo con rimborso (CARE, motivo) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-07, reward §3 (CONFIRMED → CANCELLED refund), §5 | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-048 | CANCELLED con rimborso (CARE) × evasione manuale (CARE, nota) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | F-RWD-06, reward §3 (solo CONFIRMED e MANUAL) | TestbookRwdSagaIT#stateByEvent |
| TB-RWD-SAG-049 | CANCELLED con rimborso (CARE) × retry-fulfilment (CARE) | HTTP 409, stato CANCELLED; nessun nuovo fatto reward.redemption.*; stock invariato | reward §5 (retry-fulfilment dopo una nuova generazione), docs/03 §5 (needsAttention) | TestbookRwdSagaIT#stateByEvent |

## 12. FUL — Evasione, pool esaurito, errori dell'operatore, portale

**Regole**: R-07, R-08, R-12…R-15, R-27, R-28. Evasione × pool (disponibile, vuoto, un solo codice, assente) e idempotenza della spesa; errori dell'operatore per campo (nota/motivo vuoti o corpo assente); portale per identità.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-FUL-001 | AUTO_COUPON con pool disponibile → wallet.points.spent | FULFILLED con couponCode del pool; coupon ISSUED al membro, origin REDEMPTION, redemptionId; fatti confirmed, coupon.issued, fulfilled nello stesso tracciato (lhcorrelationid = correlationId); cronologia PENDING → CONFIRMED → FULFILLED | reward §5, docs/03 §5, F-CPN-02, US-E05-06 | TestbookRwdSagaIT |
| TB-RWD-FUL-002 | coupon emesso alle 10:00 del 20/10 (Roma CEST) da un pool di validità 10 giorni | **Q-277** (scelta di oggi, da decidere) — expiresAt = emissione + 10 × 24 h (2026-10-30T10:00Z) | reward §5 («scadenza = oggi + validity_days») · Q-277 | TestbookRwdSagaIT |
| TB-RWD-FUL-003 | AUTO_COUPON con pool vuoto → wallet.points.spent | resta CONFIRMED con needsAttention=true, nessun couponCode, nessun fatto fulfilled; compare in ?needsAttention=true | docs/03 §5, reward §5, BO-13 «Da verificare» | TestbookRwdSagaIT |
| TB-RWD-FUL-004 | pool con 1 codice, due richieste confermate | la prima FULFILLED, la seconda CONFIRMED con needsAttention | docs/03 §5 (pool esaurito in fase di emissione) | TestbookRwdSagaIT |
| TB-RWD-FUL-005 | retry-fulfilment con pool ancora vuoto | 409, resta CONFIRMED con needsAttention | reward §5 (retry dopo una nuova generazione) | TestbookRwdSagaIT |
| TB-RWD-FUL-006 | AUTO_COUPON su premio senza pool → wallet.points.spent | **Q-281** (scelta di oggi, da decidere) — resta CONFIRMED con needsAttention | nessuna (ramo senza specifica) · Q-281 | TestbookRwdSagaIT |
| TB-RWD-FUL-007 | INSTANT → wallet.points.spent | FULFILLED subito, senza couponCode; fatto fulfilled senza couponCode | reward §5 (INSTANT) | TestbookRwdSagaIT |
| TB-RWD-FUL-008 | MANUAL → wallet.points.spent | resta CONFIRMED; compare in ?status=CONFIRMED&fulfilment=MANUAL («Da evadere») | reward §5, BO-13 | TestbookRwdSagaIT |
| TB-RWD-FUL-009 | evasione manuale con nota e tracking (CARE) | FULFILLED, fulfilmentNote «nota · tracking X», fatto fulfilled con note, cronologia con l'attore CARE | F-RWD-06, reward §3 ({note, tracking?}) | TestbookRwdSagaIT |
| TB-RWD-FUL-010 | evasione manuale con nota di soli spazi | 422 NOTE_REQUIRED, resta CONFIRMED | reward §3 ({note, tracking?}: nota obbligatoria) | TestbookRwdSagaIT |
| TB-RWD-FUL-011 | evasione manuale senza corpo | 422 NOTE_REQUIRED, resta CONFIRMED | reward §3 | TestbookRwdSagaIT |
| TB-RWD-FUL-012 | annullo CARE con motivo di soli spazi | 422 REASON_REQUIRED, resta CONFIRMED | reward §3 ({reason}) | TestbookRwdSagaIT |
| TB-RWD-FUL-013 | annullo CARE senza corpo | 422 REASON_REQUIRED, resta CONFIRMED | reward §3 | TestbookRwdSagaIT |
| TB-RWD-FUL-014 | annullo CARE di una CONFIRMED con coupon ISSUED collegato | CANCELLED, coupon VOID, stock +1, fatto cancelled refund=true | reward §5 (eventuale coupon VOID), §7 (accettazione 5) | TestbookRwdSagaIT |
| TB-RWD-FUL-015 | wallet.points.spent rielaborato (nuovo id) su AUTO_COUPON già FULFILLED | un solo coupon per la richiesta, stato FULFILLED | reward §7 (nessun secondo coupon) | TestbookRwdSagaIT |
| TB-RWD-FUL-016 | stesso wallet.points.spent riconsegnato (stesso id) su PENDING | una sola conferma, un solo fatto confirmed | docs/06 §9 (doppio invio = stesso stato), RNF-03 | TestbookRwdSagaIT |
| TB-RWD-FUL-017 | wallet.points.spent per una richiesta sconosciuta | ignorato: evento elaborato, nessuna voce DLQ | reward §5 (eventi per richieste sconosciute ignorati, non DLQ) | TestbookRwdSagaIT |
| TB-RWD-FUL-018 | wallet.spend.rejected per una richiesta sconosciuta | ignorato: evento elaborato, nessuna voce DLQ | reward §5 | TestbookRwdSagaIT |
| TB-RWD-FUL-019 | fulfil su id inesistente | 404 | docs/06 §2 | TestbookRwdSagaIT |
| TB-RWD-FUL-020 | cancel (CARE) su id inesistente | 404 | docs/06 §2 | TestbookRwdSagaIT |
| TB-RWD-FUL-021 | retry-fulfilment su id inesistente | 404 | docs/06 §2 | TestbookRwdSagaIT |
| TB-RWD-FUL-022 | POST /v1/portal/redemptions valida | 202 {redemptionId, status PENDING, correlationId}; fatto requested con id = correlationId, pointsCost = soglia della fascia, currency PTS | reward §3 (202), §5 (correlationId della saga), docs/03 §5 (costo = soglia) | TestbookRwdSagaIT |
| TB-RWD-FUL-023 | wallet.spend.rejected con reason MEMBER_NOT_ACTIVE | REJECTED con rejectReason MEMBER_NOT_ACTIVE, stock +1 | reward §5 (REJECTED con motivo) | TestbookRwdSagaIT |
| TB-RWD-FUL-024 | annullo dal portale con memberId di un altro membro | 404, resta PENDING | reward §3 portale, US-E05-09 | TestbookRwdSagaIT |
| TB-RWD-FUL-025 | annullo dal portale senza memberId | **Q-283** (scelta di oggi, da decidere) — 200 CANCELLED | reward §3 portale (identità esplicita) · Q-283 | TestbookRwdSagaIT |
| TB-RWD-FUL-026 | GET /v1/portal/redemptions/{id} con memberId di un altro membro | 404 | reward §3 portale | TestbookRwdSagaIT |
| TB-RWD-FUL-027 | GET /v1/portal/redemptions senza memberId | 400 | reward §3 portale (?memberId=) | TestbookRwdSagaIT |
| TB-RWD-FUL-028 | POST /v1/portal/redemptions con intestazione X-Correlation-Id: C (come la aggiunge il proxy, docs/07 §3) | **Q-274** (scelta di oggi, da decidere) — oggi correlationId = id del fatto requested (nuova radice), l'intestazione è ignorata | reward §5 («il correlationId della richiesta HTTP diventa quello di tutta la saga»), docs/07 §3 · Q-274 | TestbookRwdSagaIT |
| TB-RWD-FUL-029 | GET /v1/redemptions con filtri rewardCode, from, to | solo le richieste del premio; from incluso, to escluso su requestedAt | reward §3 (filtri status, memberId, rewardCode, needsAttention, from, to), BO-13 | TestbookRwdSagaIT |
| TB-RWD-FUL-030 | richiesta di un premio PHYSICAL con indirizzo | la richiesta conserva l'indirizzo (shipping) e lo mostra nel dettaglio | reward §2 (redemption.shipping), PT-04 (form spedizione), BO-13 (foglio laterale) | TestbookRwdSagaIT |

## 13. TMO — Confini del timeout

**Regola**: R-10 («PENDING **da più di** 10 minuti»). Valori limite di `asOf` rispetto all'istante della richiesta: −1 min, +9 min, +10 min − 1 ms, +10 min, +10 min + 1 ms, +12 min; `asOf` assente con orologio a +10 e +11 min.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-TMO-001 | richiesta PENDING, asOf = richiesta + 9 min | PENDING, stock invariato | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-002 | richiesta PENDING, asOf = richiesta + 10 min − 1 ms | PENDING, stock invariato | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-003 | richiesta PENDING, asOf = richiesta + 10 min esatti | PENDING, stock invariato | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-004 | richiesta PENDING, asOf = richiesta + 10 min + 1 ms | REJECTED (TIMEOUT), stock ripristinato, fatto rejected | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-005 | richiesta PENDING, asOf = richiesta + 12 min | REJECTED (TIMEOUT), stock ripristinato, fatto rejected | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-006 | richiesta PENDING, asOf assente, orologio a richiesta + 11 min | REJECTED (TIMEOUT), stock ripristinato, fatto rejected | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-007 | richiesta PENDING, asOf assente, orologio a richiesta + 10 min | PENDING, stock invariato | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |
| TB-RWD-TMO-008 | richiesta PENDING, asOf precedente alla richiesta (− 1 min) | PENDING, stock invariato | docs/03 §5 (timeout 10 min), reward §5 («da più di 10 min»), Q-55, US-E05-08 | TestbookRwdSagaIT#timeout |

## 14. ROL — Ruoli sulle azioni manuali

**Regola**: R-23. **Strategia**: tabella completa azione × ruolo (7 valori: 5 ruoli, intestazione assente, intestazione non valida) per evasione, annullo con rimborso, retry-fulfilment, uso alla cassa e annullo dei codici (35 righe); job demo con ADMIN, MARKETING e senza intestazione (6). Ogni 403 verifica anche lo stato invariato.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-ROL-001 | evasione manuale, X-LH-Actor ADMIN | 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-002 | evasione manuale, X-LH-Actor MARKETING | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-003 | evasione manuale, X-LH-Actor LEGAL | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-004 | evasione manuale, X-LH-Actor CARE | 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-005 | evasione manuale, X-LH-Actor ANALYST | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-006 | evasione manuale, X-LH-Actor assente | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-007 | evasione manuale, X-LH-Actor non valida (SUPERUSER:mallory) | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-008 | annullo con rimborso, X-LH-Actor ADMIN | 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-009 | annullo con rimborso, X-LH-Actor MARKETING | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-010 | annullo con rimborso, X-LH-Actor LEGAL | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-011 | annullo con rimborso, X-LH-Actor CARE | 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-012 | annullo con rimborso, X-LH-Actor ANALYST | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-013 | annullo con rimborso, X-LH-Actor assente | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-014 | annullo con rimborso, X-LH-Actor non valida (SUPERUSER:mallory) | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-015 | retry-fulfilment, X-LH-Actor ADMIN | **Q-283** (scelta di oggi, da decidere) — 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 · Q-283 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-016 | retry-fulfilment, X-LH-Actor MARKETING | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-017 | retry-fulfilment, X-LH-Actor LEGAL | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-018 | retry-fulfilment, X-LH-Actor CARE | **Q-283** (scelta di oggi, da decidere) — 200 e transizione eseguita | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 · Q-283 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-019 | retry-fulfilment, X-LH-Actor ANALYST | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-020 | retry-fulfilment, X-LH-Actor assente | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-021 | retry-fulfilment, X-LH-Actor non valida (SUPERUSER:mallory) | 403 forbidden-role, richiesta invariata | docs/08 §2 redemption.handle (ADMIN, CARE ●), docs/06 §3, reward §3 | TestbookRwdSagaIT#roles |
| TB-RWD-ROL-030 | uso alla cassa di un coupon ISSUED, X-LH-Actor ADMIN | 200 | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-031 | uso alla cassa di un coupon ISSUED, X-LH-Actor MARKETING | 200 | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-032 | uso alla cassa di un coupon ISSUED, X-LH-Actor LEGAL | 200 | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-033 | uso alla cassa di un coupon ISSUED, X-LH-Actor CARE | 200 | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-034 | uso alla cassa di un coupon ISSUED, X-LH-Actor ANALYST | 403, coupon ancora ISSUED | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-035 | uso alla cassa di un coupon ISSUED, X-LH-Actor assente | 403, coupon ancora ISSUED | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-036 | uso alla cassa di un coupon ISSUED, X-LH-Actor non valida | 403, coupon ancora ISSUED | Q-52 (cassa: ogni ruolo operativo, mai ANALYST), F-CPN-03 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-037 | annullo del codice di un coupon ISSUED, X-LH-Actor ADMIN | 200 | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-038 | annullo del codice di un coupon ISSUED, X-LH-Actor MARKETING | 403, coupon ancora ISSUED | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-039 | annullo del codice di un coupon ISSUED, X-LH-Actor LEGAL | 403, coupon ancora ISSUED | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-040 | annullo del codice di un coupon ISSUED, X-LH-Actor CARE | 200 | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-041 | annullo del codice di un coupon ISSUED, X-LH-Actor ANALYST | 403, coupon ancora ISSUED | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-042 | annullo del codice di un coupon ISSUED, X-LH-Actor assente | 403, coupon ancora ISSUED | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-043 | annullo del codice di un coupon ISSUED, X-LH-Actor non valida | 403, coupon ancora ISSUED | docs/08 §2 coupon.void (ADMIN, CARE), Q-52 | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-044 | POST /v1/demo/jobs/… (job timeout-redemptions), ruolo ADMIN | 200 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-045 | POST /v1/demo/jobs/… (job timeout-redemptions), ruolo MARKETING | 403 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-046 | POST /v1/demo/jobs/… (job timeout-redemptions), ruolo - | 403 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-047 | POST /v1/demo/jobs/… (job expire-coupons), ruolo ADMIN | 200 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-048 | POST /v1/demo/jobs/… (job expire-coupons), ruolo MARKETING | 403 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |
| TB-RWD-ROL-049 | POST /v1/demo/jobs/… (job expire-coupons), ruolo - | 403 | docs/06 §3 (/v1/demo/** ⇒ ADMIN), docs/08 §2 demo.admin | TestbookRwdCouponIT#roles |

## 15. CPN — Coupon: ciclo di vita, tempo, scadenza, pool

**Regole**: R-15, R-16. Ciclo: stato (AVAILABLE, ISSUED valido, ISSUED oltre la scadenza prima del job, EXPIRED, USED, VOID, inesistente) × azione (uso, annulla, verifica) = 21 righe (tabella completa); tempo: uso a scadenza −1 ms, = scadenza, +1 ms. Job: `asOf` ai confini della scadenza, mezzanotte di Roma, date pure nei giorni del cambio d'ora e il 29/2. Pool: prefisso, validità, conteggi di generazione ai limiti 0/1/5000/5001, formato e seme, import con duplicati, esistenti, non validi, vuoto, oltre 5000.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-CPN-001 | AVAILABLE (mai emesso) × uso alla cassa | 409; stato dopo AVAILABLE | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-002 | AVAILABLE (mai emesso) × annulla | **Q-278** (scelta di oggi, da decidere) — 200; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 · Q-278 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-003 | AVAILABLE (mai emesso) × verifica | 200; stato dopo AVAILABLE | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-004 | ISSUED valido × uso alla cassa | 200; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-005 | ISSUED valido × annulla | 200; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-006 | ISSUED valido × verifica | 200; stato dopo ISSUED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-007 | ISSUED oltre la scadenza, job non ancora eseguito × uso alla cassa | 410 COUPON_EXPIRED; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-008 | ISSUED oltre la scadenza, job non ancora eseguito × annulla | **Q-278** (scelta di oggi, da decidere) — 200; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 · Q-278 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-009 | ISSUED oltre la scadenza, job non ancora eseguito × verifica | 200; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-010 | EXPIRED (job eseguito) × uso alla cassa | 410 COUPON_EXPIRED; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-011 | EXPIRED (job eseguito) × annulla | **Q-278** (scelta di oggi, da decidere) — 200; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 · Q-278 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-012 | EXPIRED (job eseguito) × verifica | 200; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-013 | USED × uso alla cassa | 409 COUPON_ALREADY_USED; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-014 | USED × annulla | 409; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-015 | USED × verifica | 200; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-016 | VOID × uso alla cassa | 409; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-017 | VOID × annulla | 409; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-018 | VOID × verifica | 200; stato dopo VOID | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-019 | codice inesistente × uso alla cassa | 404 | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-020 | codice inesistente × annulla | 404 | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-021 | codice inesistente × verifica | 404 | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-022 | uso 1 ms prima della scadenza | 200; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-023 | uso all'istante della scadenza | **Q-276** (scelta di oggi, da decidere) — 410 COUPON_EXPIRED; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 · Q-276 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-024 | uso 1 ms dopo la scadenza | 410 COUPON_EXPIRED; stato dopo EXPIRED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-025 | uso con codice scritto in minuscolo | **Q-284** (scelta di oggi, da decidere) — 200; stato dopo USED | docs/03 §5 (AVAILABLE → ISSUED → USED oppure EXPIRED/VOID), reward §3 (409 COUPON_ALREADY_USED, 410 COUPON_EXPIRED, 404), F-CPN-03, BO-12, PT-13 · Q-284 | TestbookRwdCouponIT#lifecycle |
| TB-RWD-CPN-030 | uso alla cassa di un coupon ISSUED | fatto coupon.used {couponCode, rewardCode} con l'attore della cassa | reward §4 (coupon.used), F-CPN-03 | TestbookRwdCouponIT |
| TB-RWD-CPN-031 | GET /v1/portal/coupons senza memberId | 400 | reward §3 portale (?memberId=) | TestbookRwdCouponIT |
| TB-RWD-CPN-032 | GET /v1/portal/coupons del membro con un coupon attivo e uno oltre la scadenza | {code, rewardName, status, issuedAt, expiresAt, origin}; il secondo con status EXPIRED | reward §3 portale, PT-13 (attivo/usato/scaduto) | TestbookRwdCouponIT |
| TB-RWD-CPN-040 | job expire-coupons: asOf 1 ms prima della scadenza (expiresAt 2026-10-10T10:00:00Z, asOf 2026-10-10T09:59:59.999Z) | stato ISSUED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-041 | job expire-coupons: asOf all'istante della scadenza (expiresAt 2026-10-10T10:00:00Z, asOf 2026-10-10T10:00:00Z) | **Q-276** (scelta di oggi, da decidere) — stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 · Q-276 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-042 | job expire-coupons: asOf 1 ms dopo la scadenza (expiresAt 2026-10-10T10:00:00Z, asOf 2026-10-10T10:00:00.001Z) | stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-043 | job expire-coupons: scadenza alla mezzanotte di Roma (25/10 00:00 CEST), asOf 23:59:59.999 (expiresAt 2026-10-24T22:00:00Z, asOf 2026-10-24T21:59:59.999Z) | stato ISSUED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-044 | job expire-coupons: asOf data «2026-10-25» (giorno del cambio d'ora), scadenza 23:59:59 CET (expiresAt 2026-10-25T22:59:59Z, asOf 2026-10-25) | **Q-284** (scelta di oggi, da decidere) — stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 · Q-284 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-045 | job expire-coupons: asOf data «2026-10-25», scadenza 26/10 00:00 CET (expiresAt 2026-10-25T23:00:00Z, asOf 2026-10-25) | stato ISSUED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-046 | job expire-coupons: asOf data «2026-03-29» (cambio d'ora di marzo), scadenza 23:59:59 CEST (expiresAt 2026-03-29T21:59:59Z, asOf 2026-03-29) | **Q-284** (scelta di oggi, da decidere) — stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 · Q-284 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-047 | job expire-coupons: asOf data «2026-03-29», scadenza 30/3 00:00 CEST (expiresAt 2026-03-29T22:00:00Z, asOf 2026-03-29) | stato ISSUED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-048 | job expire-coupons: asOf data «2028-02-29» (bisestile), scadenza a metà giornata (expiresAt 2028-02-29T12:00:00Z, asOf 2028-02-29) | **Q-284** (scelta di oggi, da decidere) — stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 · Q-284 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-049 | job expire-coupons: coupon USED con scadenza passata (expiresAt 2026-10-10T10:00:00Z, asOf 2026-10-11T00:00:00Z) | stato USED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-050 | job expire-coupons: asOf assente, orologio dopo la scadenza (expiresAt 2026-10-10T10:00:00Z, asOf CLOCK+1d) | stato EXPIRED | reward §5 (job: ISSUED scaduti → EXPIRED), §3 demo (?asOf=), docs/08 BO-30, US-E05-17 | TestbookRwdCouponIT#expiry |
| TB-RWD-CPN-060 | POST /v1/coupon-pools — pool valido | 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-061 | POST /v1/coupon-pools — prefisso e codice in minuscolo | **Q-284** (scelta di oggi, da decidere) — 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-062 | POST /v1/coupon-pools — prefisso «caf!» | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_PREFIX_INVALID | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-063 | POST /v1/coupon-pools — prefisso di 1 carattere | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_PREFIX_INVALID | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-064 | POST /v1/coupon-pools — prefisso di 2 caratteri | **Q-284** (scelta di oggi, da decidere) — 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-065 | POST /v1/coupon-pools — prefisso di 10 caratteri | **Q-284** (scelta di oggi, da decidere) — 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-066 | POST /v1/coupon-pools — prefisso di 11 caratteri | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_PREFIX_INVALID | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-067 | POST /v1/coupon-pools — validità 0 giorni | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_VALIDITY_INVALID | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-068 | POST /v1/coupon-pools — validità 1 giorno | 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-069 | POST /v1/coupon-pools — validità 3650 giorni | **Q-284** (scelta di oggi, da decidere) — 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-070 | POST /v1/coupon-pools — validità 3651 giorni | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_VALIDITY_INVALID | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-071 | POST /v1/coupon-pools — validità assente | **Q-284** (scelta di oggi, da decidere) — 201, conteggi per stato a zero | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) · Q-284 | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-072 | POST /v1/coupon-pools — codice già esistente | 409 CODE_TAKEN | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-073 | POST /v1/coupon-pools — nome assente | 400 | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-074 | POST /v1/coupon-pools — ruolo CARE | 403 FORBIDDEN_ROLE | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-075 | POST /v1/coupon-pools — ruolo ANALYST | 403 FORBIDDEN_ROLE | F-CPN-01, reward §2 (coupon_pool), §3, docs/08 §2 object.edit (pool coupon) | TestbookRwdCouponIT#createPool |
| TB-RWD-CPN-080 | POST /v1/coupon-pools/{id}/generate — genera 1 codice | 200; codici AVAILABLE nel pool: 1 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-081 | POST /v1/coupon-pools/{id}/generate — genera 5000 codici (massimo) | 200; codici AVAILABLE nel pool: 5000 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-082 | POST /v1/coupon-pools/{id}/generate — genera 5001 codici | 422 COUPON_COUNT_INVALID; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-083 | POST /v1/coupon-pools/{id}/generate — genera 0 codici | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_COUNT_INVALID; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 · Q-284 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-084 | POST /v1/coupon-pools/{id}/generate — genera −1 codici | **Q-284** (scelta di oggi, da decidere) — 422 COUPON_COUNT_INVALID; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 · Q-284 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-085 | POST /v1/coupon-pools/{id}/generate — count assente | 400; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-086 | POST /v1/coupon-pools/{id}/generate — pool inesistente | 404; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-087 | POST /v1/coupon-pools/{id}/generate — ruolo CARE | 403 FORBIDDEN_ROLE; codici AVAILABLE nel pool: 0 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-088 | POST /v1/coupon-pools/{id}/generate — due generazioni successive 3 + 2 | 200; codici AVAILABLE nel pool: 5 | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-089 | POST /v1/coupon-pools/{id}/generate — formato di 100 codici generati | 200; codici AVAILABLE nel pool: 100; tutti nel formato PREFISSO-XXXX-XXXX con alfabeto A-Z2-9, distinti | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-090 | POST /v1/coupon-pools/{id}/generate — seme fisso: stessi codici per stesso pool e stesso stato di partenza | 200; codici AVAILABLE nel pool: 5; sequenza = quella del generatore col seme del pool | reward §3 ({count ≤ 5000} → prefix-XXXX-XXXX), docs/03 §5 (A-Z2-9), reward §6 e docs/10 §5 (seme fisso), F-CPN-01 | TestbookRwdCouponIT#generate |
| TB-RWD-CPN-092 | POST /v1/coupon-pools/{id}/import — 3 codici nuovi | 200; imported 3, skipped 0; importati visibili come AVAILABLE | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-093 | POST /v1/coupon-pools/{id}/import — 6 codici con 3 duplicati nell'elenco | 200; imported 3, skipped 3; importati visibili come AVAILABLE | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-094 | POST /v1/coupon-pools/{id}/import — codice già presente in un altro pool | 200; imported 1, skipped 1; importati visibili come AVAILABLE | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-095 | POST /v1/coupon-pools/{id}/import — codice troppo corto «ab» | **Q-284** (scelta di oggi, da decidere) — 200; imported 1, skipped 1; importati visibili come AVAILABLE | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 · Q-284 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-096 | POST /v1/coupon-pools/{id}/import — codice minuscolo con spazi «  abcd-efgh » | **Q-284** (scelta di oggi, da decidere) — 200; imported 1, skipped 0; importati visibili come AVAILABLE | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 · Q-284 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-097 | POST /v1/coupon-pools/{id}/import — elenco vuoto | **Q-284** (scelta di oggi, da decidere) — 422 | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 · Q-284 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-098 | POST /v1/coupon-pools/{id}/import — 5001 codici | **Q-284** (scelta di oggi, da decidere) — 422 | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 · Q-284 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-099 | POST /v1/coupon-pools/{id}/import — ruolo CARE | 403 | reward §3 (import {codes[]}; duplicati → {imported, skipped[]}), F-CPN-01, BO-12 | TestbookRwdCouponIT#importCodes |
| TB-RWD-CPN-100 | GET /v1/coupon-pools/{id}/coupons con filtri status e memberId | solo i codici del filtro; paginazione {items, page} | reward §3, BO-12, docs/06 §2 (paginazione) | TestbookRwdCouponIT |
| TB-RWD-CPN-101 | GET /v1/coupon-pools/{id} dopo emissione, uso e annullo | conteggi per stato AVAILABLE/ISSUED/USED/EXPIRED/VOID coerenti, totale = somma | BO-12 (totali per stato), F-CPN-01 | TestbookRwdCouponIT |

## 16. EFF — Effetto `coupon.issue`

**Regola**: R-17. Un caso per ramo: valido, stesso `effectId`, stesso evento, pool vuoto, premio inesistente, premio senza pool, effetto senza membro, `effectId` assente, premio non LIVE.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-EFF-001 | coupon.issue valido (membro, premio con pool) | coupon ISSUED al membro del subject, origin CAMPAIGN, effectId salvato; fatto coupon.issued figlio dell'effetto | reward §4–5, F-CPN-02, EVT-EFF-03 | TestbookRwdCouponIT |
| TB-RWD-EFF-002 | stesso effectId in un nuovo messaggio | nessun secondo coupon | reward §5 (idempotenza su effect_id) | TestbookRwdCouponIT |
| TB-RWD-EFF-003 | stesso evento riconsegnato (stesso id) | nessun secondo coupon | docs/06 §9, RNF-03 | TestbookRwdCouponIT |
| TB-RWD-EFF-004 | pool del premio vuoto | una sola voce DLQ con lh-error-code COUPON_POOL_EMPTY (non ritentabile), nessun coupon | reward §5 (pool vuoto → DLQ COUPON_POOL_EMPTY) | TestbookRwdCouponIT |
| TB-RWD-EFF-005 | premio inesistente | **Q-285** (scelta di oggi, da decidere) — voce DLQ con lh-error-code REWARD_NOT_FOUND | nessuna (ramo senza specifica) · Q-285 | TestbookRwdCouponIT |
| TB-RWD-EFF-006 | premio senza pool | **Q-285** (scelta di oggi, da decidere) — voce DLQ con lh-error-code COUPON_POOL_MISSING | nessuna (ramo senza specifica) · Q-285 | TestbookRwdCouponIT |
| TB-RWD-EFF-007 | effetto senza subject member: | **Q-285** (scelta di oggi, da decidere) — voce DLQ con lh-error-code INVALID_EFFECT | nessuna (ramo senza specifica) · Q-285 | TestbookRwdCouponIT |
| TB-RWD-EFF-008 | effectId assente | **Q-285** (scelta di oggi, da decidere) — coupon emesso con effectId = id dell'evento | nessuna (ramo senza specifica) · Q-285 | TestbookRwdCouponIT |
| TB-RWD-EFF-009 | premio DRAFT con pool | coupon emesso (lo stato del premio non conta per l'effetto) | reward §5 («usa il pool del premio indicato») | TestbookRwdCouponIT |

## 17. AUD — Audit e assenza di eventi

**Regole**: R-25, R-03 («nessun evento» sugli errori immediati). Le voci di audit si leggono dall'outbox (`lh.audit.v1`).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-RWD-AUD-001 | creazione di un premio (MARKETING) | voce di audit REWARD/CREATE con l'attore MARKETING | reward §4 (audit: catalogo), docs/06 §3 | TestbookRwdCatalogIT |
| TB-RWD-AUD-002 | modifica di un premio | voce REWARD/UPDATE con prima/dopo | reward §4, docs/06 §3 | TestbookRwdCatalogIT |
| TB-RWD-AUD-003 | transizione SUBMIT e APPROVE di ADMIN (override) | voci REWARD/TRANSITION; quella dell'ADMIN marcata «override» | docs/03 §3.6 (storico e audit), docs/08 §2 (override marcato in audit) | TestbookRwdCatalogIT |
| TB-RWD-AUD-004 | fascia creata, modificata, eliminata | voci REWARD_BAND CREATE, UPDATE, DELETE | reward §4 (audit: fasce) | TestbookRwdCatalogIT |
| TB-RWD-AUD-005 | pool creato e codici generati | voci COUPON_POOL CREATE e UPDATE | reward §4 (audit: pool) | TestbookRwdCatalogIT |
| TB-RWD-AUD-006 | categoria creata | voce REWARD_CATEGORY CREATE | reward §4 (audit: catalogo), docs/06 §3 | TestbookRwdCatalogIT |
| TB-RWD-AUD-007 | evasione manuale (CARE) | voce REDEMPTION/UPDATE con attore CARE | reward §4 (audit: evasioni) | TestbookRwdSagaIT |
| TB-RWD-AUD-008 | annullo con rimborso (CARE) | voce REDEMPTION/UPDATE con attore CARE | reward §4 (audit: annulli) | TestbookRwdSagaIT |
| TB-RWD-AUD-009 | uso alla cassa e annullo di un codice | voci COUPON/UPDATE con l'attore | docs/06 §3 (ogni scrittura da backoffice pubblica un audit) | TestbookRwdCouponIT |
| TB-RWD-AUD-010 | richiesta respinta con 422 | nessun fatto sul membro (errore immediato, nessun evento) | docs/03 §5 (validazioni: errore immediato, nessun evento) | TestbookRwdEligibilityIT |

## 18. Registro delle divergenze

| N. | Riga | Specifica | Comportamento osservato | Causa (file:riga) | Esito |
|---|---|---|---|---|---|
| D-1 | TB-RWD-ORD-019 | docs/06 §2: 400 `bad-request` per «JSON malformato» | `POST /v1/portal/redemptions` senza corpo ⇒ 500 `INTERNAL_ERROR` | `libs/lh-common/src/main/java/io/loyaltyhub/common/web/GlobalExceptionHandler.java:66-67` (`@ExceptionHandler(Exception.class)` intercetta anche `HttpMessageNotReadableException`) | aperta |
| D-2 | TB-RWD-FUL-011 | reward §3 `{note, tracking?}` + docs/06 §2 (400/422, mai 500) | `POST /v1/redemptions/{id}/fulfil` senza corpo ⇒ 500 | stessa causa (`@RequestBody` obbligatorio in `api/RedemptionsController.java:57-58`, eccezione resa 500 da `GlobalExceptionHandler.java:66`) | aperta |
| D-3 | TB-RWD-FUL-013 | reward §3 `{reason}` + docs/06 §2 | `POST /v1/redemptions/{id}/cancel` senza corpo ⇒ 500 | stessa causa (`api/RedemptionsController.java:63-64`) | aperta |

## 19. Ambiguità

Righe che asseriscono la scelta di oggi in attesa di decisione (domanda in `docs/15`):

- **TB-RWD-VIS-043** (Q-275) — estremo iniziale: la specifica dice solo «dentro validità»; oggi incluso
- **TB-RWD-VIS-046** (Q-275) — estremo finale: oggi escluso (validità semiaperta)
- **TB-RWD-VIS-058** (Q-279) — le richieste REJECTED/CANCELLED non contano per il limite (ripristinano lo stock, docs/03 §5)
- **TB-RWD-ORD-001** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-002** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-003** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-004** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-005** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-006** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-007** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-008** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-009** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-010** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-011** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-012** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-013** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-014** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-ORD-015** (Q-273) — la specifica elenca i codici ma non la precedenza quando più condizioni sono vere; il test verifica l'insieme ammesso (specifica) e il codice di oggi
- **TB-RWD-STK-012** (Q-280) — dato incoerente non previsto dalla specifica
- **TB-RWD-CAT-007** (Q-281) — normalizzazione del tipo non prevista dalla specifica
- **TB-RWD-CAT-010** (Q-281) — BO-10 parla di «AUTO con pool coupon» ma nessuna fonte vieta il pool assente
- **TB-RWD-CAT-016** (Q-281) — la categoria facoltativa non è prevista (reward §2 elenca category_code senza null)
- **TB-RWD-CAT-021** (Q-281) — limite 0 non trattato dalla specifica
- **TB-RWD-EDT-029** (Q-281) — l'immutabilità del codice non è scritta (reward §2: code UQ)
- **TB-RWD-EDT-049** (Q-280) — il PUT senza version non è trattato da Q-112
- **TB-RWD-EDT-051** (Q-280) — passaggio da illimitato a limitato: le richieste in corso non sono sottratte
- **TB-RWD-EDT-053** (Q-280) — la specifica dice «ripristina lo stock» ma non come comporlo con un totale ridotto sotto le prenotazioni
- **TB-RWD-EDT-061** (Q-281) — la specifica fissa solo «-COPY»; la collisione non è trattata
- **TB-RWD-BND-012** (Q-281) — soglia nulla (premio gratuito) non trattata dalla specifica
- **TB-RWD-LCY-095** (Q-282) — SUBMIT che pubblica direttamente con approvazione spenta è una scelta di lh-common (docs/06 §7 dice solo «DRAFT → LIVE diretto»)
- **TB-RWD-LCY-098** (Q-282) — maiuscole/minuscole dell'azione non trattate
- **TB-RWD-LCY-099** (Q-282) — azione fuori enumerato: 422 (non 400) non fissato dalla specifica
- **TB-RWD-FUL-002** (Q-277) — «oggi + validity_days» non dice se si contano giorni di calendario di Roma (qui il cambio dell'ora sposterebbe l'ora locale) o istanti; oggi + N × 24 h
- **TB-RWD-FUL-006** (Q-281) — premio AUTO_COUPON senza pool: nessuna fonte ne descrive l'evasione
- **TB-RWD-FUL-025** (Q-283) — annullo senza memberId: l'identità esplicita (CLAUDE.md §1.6) non dice cosa fare se manca
- **TB-RWD-FUL-028** (Q-274) — «correlationId della richiesta HTTP»: l'intestazione X-Correlation-Id del proxy o il correlationId restituito dal 202? Se è l'intestazione, la regola non è implementata
- **TB-RWD-ROL-015** (Q-283) — retry-fulfilment non è elencato in docs/08 §2: si applica redemption.handle
- **TB-RWD-ROL-018** (Q-283) — retry-fulfilment non è elencato in docs/08 §2: si applica redemption.handle
- **TB-RWD-CPN-002** (Q-278) — docs/03 §5 dà VOID come esito del ciclo senza dire da quali stati: annullare un codice mai emesso o già scaduto non è trattato
- **TB-RWD-CPN-008** (Q-278) — docs/03 §5 dà VOID come esito del ciclo senza dire da quali stati: annullare un codice mai emesso o già scaduto non è trattato
- **TB-RWD-CPN-011** (Q-278) — docs/03 §5 dà VOID come esito del ciclo senza dire da quali stati: annullare un codice mai emesso o già scaduto non è trattato
- **TB-RWD-CPN-023** (Q-276) — istante della scadenza: la specifica non dice se è ancora valido (docs/03 §4.2 per i lotti usa expiresAt ≤ asOf ⇒ scaduto)
- **TB-RWD-CPN-025** (Q-284) — normalizzazione del codice alla cassa non prevista
- **TB-RWD-CPN-041** (Q-276) — istante della scadenza: come docs/03 §4.2 (expiresAt ≤ asOf) per i lotti
- **TB-RWD-CPN-044** (Q-284) — asOf come data pura = fine del giorno a Roma (23:59:59.999999): convenzione di BO-30 non scritta
- **TB-RWD-CPN-046** (Q-284) — asOf come data pura = fine del giorno a Roma
- **TB-RWD-CPN-048** (Q-284) — asOf come data pura = fine del giorno a Roma
- **TB-RWD-CPN-061** (Q-284) — normalizzazione in maiuscolo non prevista
- **TB-RWD-CPN-062** (Q-284) — regole del prefisso (2–10 A-Z0-9) non scritte
- **TB-RWD-CPN-063** (Q-284) — regole del prefisso non scritte
- **TB-RWD-CPN-064** (Q-284) — regole del prefisso non scritte
- **TB-RWD-CPN-065** (Q-284) — regole del prefisso non scritte
- **TB-RWD-CPN-066** (Q-284) — regole del prefisso non scritte
- **TB-RWD-CPN-067** (Q-284) — limiti di validity_days non scritti
- **TB-RWD-CPN-069** (Q-284) — limiti di validity_days non scritti
- **TB-RWD-CPN-070** (Q-284) — limiti di validity_days non scritti
- **TB-RWD-CPN-071** (Q-284) — valore predefinito 90 giorni non scritto
- **TB-RWD-CPN-083** (Q-284) — codice d'errore di count fuori intervallo non scritto (la specifica fissa solo ≤ 5000)
- **TB-RWD-CPN-084** (Q-284) — codice d'errore di count fuori intervallo non scritto (la specifica fissa solo ≤ 5000)
- **TB-RWD-CPN-095** (Q-284) — validità del formato dei codici importati non scritta
- **TB-RWD-CPN-096** (Q-284) — normalizzazione dei codici importati non scritta
- **TB-RWD-CPN-097** (Q-284) — codice d'errore COUPON_IMPORT_EMPTY non scritto
- **TB-RWD-CPN-098** (Q-284) — il tetto di 5000 è scritto solo per generate
- **TB-RWD-EFF-005** (Q-285) — premio sconosciuto nell'effetto non trattato
- **TB-RWD-EFF-006** (Q-285) — premio senza pool nell'effetto non trattato
- **TB-RWD-EFF-007** (Q-285) — effetto senza membro non trattato
- **TB-RWD-EFF-008** (Q-285) — effectId assente: idempotenza ricadrebbe sull'id dell'evento

Conflitti tra fonti (registrati in `docs/15`, regola di precedenza applicata):
- **Q-286 — Campi modificabili in LIVE**: docs/03 §3.6 (nome, descrizione, fine, priorità, immagine) vs reward §3 (`stock_total`, `valid_to`, `image_url`). Applicata reward §3 (fonte n. 3): nome e descrizione bloccati (EDT-007, EDT-015).
- **Q-287 — Stato REJECTED del premio**: reward §3 ammette la modifica in `REJECTED`, stato che la macchina comune (docs/03 §3.6) non ha più (REJECT ⇒ DRAFT). Righe scritte sul ritorno in DRAFT (LCY-003, LCY-123).
- **Q-288 — `?status=` di `GET /v1/approvals`**: docs/06 §7 lo cita, l'endpoint lo ignora e restituisce sempre la coda IN_REVIEW (LCY-122 usa il parametro, esito conforme).

## 20. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 28 (R-01…R-28) |
| Rami del codice mappati | 78 voci della tabella §2 (ognuna raggruppa i rami di un punto di decisione) |
| Righe di testbook | 567 (VIS 59 · ORD 19 · SNP 7 · STK 16 · CAT 41 · EDT 54 · BND 29 · LCY 119 · SAG 49 · FUL 30 · TMO 8 · ROL 41 · CPN 76 · EFF 9 · AUD 10) |
| Combinazioni ridotte | VIS: 864 combinazioni → 15 all-pairs + 27 classi da sole + 17 valori limite = 59; ORD: 2^6 combinazioni di condizioni vere → 15 coppie; LCY: 7 × 8 × 5 ruoli × 2 policy = 560 → 99 (tabella stato × azione col ruolo autorizzato + matrice ruoli dallo stato valido + policy spenta); ROL: tabella completa per azione (nessuna riduzione); SAG: 7 × 7 completa; CPN ciclo: 7 × 3 completa |
| Rami senza specifica | 20 voci di §2 (marcate **ramo senza specifica**; le righe che li provano citano la domanda Q-R* di docs/15) |
| Regole non implementate | 1 possibile: R-27 «il correlationId della richiesta HTTP diventa quello della saga» se si intende l'intestazione `X-Correlation-Id` del proxy (TB-RWD-FUL-028, Q-274) |
| Righe su scelte da decidere | 67 (§19), 13 domande Q-273…Q-285 + 3 conflitti tra fonti Q-286…Q-288 in docs/15 |
| Divergenze aperte | 3 (§18: TB-RWD-ORD-019, TB-RWD-FUL-011, TB-RWD-FUL-013 — stessa causa in lh-common); CPN-045/047 chiuse col fix di fine giornata |

