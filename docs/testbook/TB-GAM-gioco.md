# TB-GAM — Gioco: testbook funzionale

Dominio **Gioco** del testbook (`docs/16` §7): concorsi instant win (giocata, crediti, istanti, premi, consegna, istante piantato, ciclo di vita con approvazione LEGAL, fine concorso), obiettivi, badge, classifiche, referral lato gioco. Servizio `gamification-service` (porta 8086, schema `gamification`).

- **Fonti (oracolo)**, in ordine di precedenza (`CLAUDE.md` §6): contratti `contracts/events/fact/{contest.*,achievement.*,badge.awarded}` · `docs/servizi/gamification-service.md` (qui «gamification §n») · `docs/02` F-IW-01…08, F-ACH-01…03, F-LDB-01, F-REF-01…02 · `docs/03` §2 (membro), §3.3 (condizioni), §3.6 (ciclo di vita), §6 (instant win), §8 (obiettivi, badge, classifiche, referral) · `docs/06` §2, §3, §7 · `docs/08` §2, §3.3, BO-14…17 · `docs/09` PT-05, PT-06, PT-09, PT-10 · `docs/10` §6 · scelte registrate in `docs/15`: Q-56, Q-59, Q-60, Q-62, Q-112, Q-113, Q-159, Q-167, Q-261, Q-282. `docs/17` (storie e foresta delle decisioni) è stato usato solo come elenco dei rami, mai come oracolo.
- **Esecuzione** (`docs/16` §1bis): ogni riga è un caso JUnit il cui nome inizia con `[ID]`. Classi unit (surefire): `TestbookGamInstantGeneratorTest`, `TestbookGamAchievementRulesTest` (package `domain`), `TestbookGamNicknameTest` (package `messaging`), `TestbookGamApprovalPolicyTest`. Classi d'integrazione (failsafe): `TestbookGamPlayIT`, `TestbookGamPrizeIT`, `TestbookGamLifecycleIT`, `TestbookGamContestIT`, `TestbookGamAchievementIT`, `TestbookGamLeaderboardIT`, `TestbookGamEffectIT`, tutte figlie di `TestbookGamBase` (un solo contesto Spring condiviso: EmbeddedKafka + Postgres Zonky, profilo `demo`). Dati guidati da CSV in `services/gamification-service/src/test/resources/testbook/gam/`.
- **Tempo**: l'orologio dell'applicazione nei test è fisso per ogni caso (`TestbookGamBase.TestbookClock`); adesso di riferimento `T0 = 2026-03-10T10:00:00Z` (11:00 a Roma) salvo dove la riga indica altri istanti. Ogni caso usa concorsi, codici e membri propri (mai lo stato mutabile dei seed); la sola lettura di un seed è la copia di `IW-AUTUNNO` in TB-GAM-INS-013 (criterio di gamification §7).
- **Legenda**: **AMBIGUO** = la specifica tace e `docs/15` non registra una scelta: il test asserisce il comportamento attuale con il commento `// TESTBOOK: ambiguo, vedi <ID>`. **DIVERGENZA** = il codice non rispetta la specifica: il test asserisce la specifica e fallisce (registro in §21).
- **Tempo di esecuzione misurato**: vedi §21.

## 1. Inventario delle regole e mappa dei rami

### 1.1 Regole della specifica

| Regola | Testo (sintesi) | Fonti | Aree |
|---|---|---|---|
| R01 | Solo i membri `ACTIVE` giocano e accumulano; stato preso dallo snapshot locale | docs/03 §2, gamification §3 (`MEMBER_NOT_ACTIVE`), §4 | PLY, SNP, PRG |
| R02 | Si gioca solo su un concorso `LIVE` con adesso in [`startAt`, `endAt`) | gamification §3 (`CONTEST_NOT_LIVE`), docs/03 §6, F-IW-01 | PLY, PTL |
| R03 | Disponibili = Σ `play_grant.count` − giocate da credito + gratuita giornaliera se non usata oggi (Europe/Rome) | docs/03 §6, F-IW-05 | CRD |
| R04 | Tetto `maxPlaysPerMemberPerDay` sulle giocate del giorno di Roma → `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3 | CRD |
| R05 | Nessuna giocata disponibile → `NO_PLAYS_AVAILABLE` | gamification §3, §7 | CRD |
| R06 | Ordine dei crediti: prima la gratuita giornaliera, poi i crediti | gamification §5 | CRD, PTL |
| R07 | Claim: il primo istante `OPEN` con `instant_at ≤ now`, in ordine di `instant_at`, atomico → `WIN`, altrimenti `LOSE` | docs/03 §6, F-IW-04 | CLM, PLY |
| R08 | `maxWinsPerMember` (default illimitato), verificato prima del claim | docs/03 §6 | PLY, CLM |
| R09 | Una vincita decrementa `quantity_remaining` | docs/03 §6 | CLM, PLT |
| R10 | Risposta sincrona `{playId, outcome, prize?, playsAvailable, correlationId}`; fatti `contest.played` (+ `contest.won`) nello stesso tracciato | gamification §3, §5, contratti | CLM, PRZ |
| R11 | Montepremi `POINTS` (punti) / `COUPON` (premio) / `PHYSICAL`; `contest.won` porta `points`/`rewardCode`; `PHYSICAL` → consegna `PENDING` | F-IW-02, F-IW-06, docs/03 §6, gamification §2, BO-14 | PRZ |
| R12 | Consegna manuale `{status, note}` solo per vincite `PHYSICAL`, stati `PENDING/DELIVERED`, ruoli CARE/ADMIN, audit | gamification §2, §3, §4, docs/08 §2 `delivery.handle` | PRZ |
| R13 | Portale: concorsi `LIVE` con giocate disponibili e premi, **mai** quantità residue né istanti; storico giocate | gamification §3, PT-05, PT-06 | PTL |
| R14 | Istante piantato (demo, ADMIN): `OPEN` a `now − 1 s` (Q-62: prima del più vecchio aperto), preso dall'ultimo `OPEN` dello stesso premio, montepremi invariato, audit | gamification §3, §4, F-IW-08, BO-14, Q-62 | PLT |
| R15 | Ciclo di vita comune; policy `CONTEST`: approvazione sempre, ruolo LEGAL; transizioni non valide 409 | docs/03 §3.6, docs/06 §2, §7 | LFC, APR |
| R16 | Ruoli: `object.edit` ADMIN/MARKETING; `object.approve` LEGAL o ADMIN (override marcato in audit); ANALYST e intestazione assente in sola lettura | docs/08 §2, docs/06 §3 | ROL, LFC, EDT, INS, ACF, BDG, LCF |
| R17 | Verso `LIVE` servono gli istanti generati → `INSTANTS_NOT_GENERATED` | gamification §3, BO-14 | LFC |
| R18 | `REJECT` richiede un commento | docs/03 §3.6 | LFC |
| R19 | Ogni transizione scrive storico (chi, quando, commento), audit e fatto `contest.status.changed`; coda `GET /v1/approvals` dei concorsi `IN_REVIEW` | docs/03 §3.6, docs/06 §7, gamification §3, §4 | LFC |
| R20 | Fine concorso (`END` o job su `LIVE` con `end_at` passato) → `ENDED`, istanti `OPEN` → `VOID` | docs/03 §6, gamification §5 | LFC, END |
| R21 | Validazione del concorso: codice `^[A-Z][A-Z0-9-]{2,39}$` univoco, nome, meccanica `WHEEL/SCRATCH/BOX` (Q-56), distribuzione, `endAt > startAt`, limite giornaliero ≥ 1; errori 422 (409 per il duplicato) | docs/06 §2, gamification §2, F-IW-01, Q-56 | EDT, PRZ |
| R22 | Oggetto `LIVE`: modificabili solo i campi sicuri (nome, descrizione, `endAt`, priorità, immagine); premi solo prima di `LIVE`; istanti immutabili dopo l'avvio | docs/03 §3.6, §7, gamification §3 | EDT |
| R23 | Istanti coerenti con premi e periodo: cambiare premi, periodo, distribuzione o seme prima di `LIVE` li invalida | F-IW-03, docs/03 §6 | EDT, LFC |
| R24 | Blocco ottimistico con `version` (409 `VERSION_CONFLICT`) | Q-112 | EDT |
| R25 | Duplica: `<code>-COPY-n` (≤ 40), `DRAFT`, premi a quantità piena, seme dal nuovo codice, istanti da generare | Q-113 | EDT |
| R26 | Generazione: un istante per unità di premio in [`startAt`, `endAt`), `UNIFORM` o `BUSINESS_HOURS` (08–22 Europe/Rome), `SplittableRandom(seed)` in ordine di `sort_order`, seme salvato, rigenerazione da zero, audit | docs/03 §6, gamification §4, §5, §7, F-IW-03 | GEN, INS |
| R27 | Rigenerare è permesso solo in `DRAFT/IN_REVIEW/APPROVED` (409 da `LIVE` in poi) | docs/03 §6, gamification §3 | INS |
| R28 | Tabella degli istanti solo ADMIN/LEGAL; istogramma per giorno visibile a tutti; filtri `status, prizeId`; pagine ≤ 100 | gamification §3, docs/08 §2, BO-14, docs/06 §2 | INS |
| R29 | Vincitori (membro, premio, stato consegna), export CSV, statistiche (giocate, vincite, tasso, residui, serie giornaliera) | F-IW-07, gamification §3, BO-14 | RPT |
| R30 | Riepilogo per la Scheda 360° `GET /v1/members/{memberId}/gamification` (Q-167: endpoint assente, la scheda degrada) | gamification §3, Q-167 | RPT |
| R31 | Crediti da effetto `plays.grant`, idempotenti su `effectId`, fatto `contest.plays.granted`; concorso sconosciuto → errore a valle in DLQ | F-IW-05, gamification §4, campaign-service §5, RNF-03, contratto | GRT |
| R32 | Snapshot del membro da `member.registered/updated/status.changed`; nickname di default = nome + iniziale del cognome | gamification §4, docs/03 §8 | SNP, NCK |
| R33 | Metriche `COUNT`, `SUM` di `sumField`, `DISTINCT_TYPES`, `STREAK` (`DAY`/`WEEK`, un buco azzera) | docs/03 §8, F-ACH-01 | MET, STK, PRG |
| R34 | Periodi → `periodKey` in Europe/Rome; edizione = `ED-<anno>`; docs/03 §8 elenca `NONE, DAY, WEEK, MONTH, EDITION`, Q-159 tiene `EVER/MONTH/EDITION` (`DAY`/`WEEK` → 422, `EVER` = `NONE`) | docs/03 §8, Q-59, Q-159 | PER, PRG, ACF |
| R35 | Filtro dell'obiettivo = condizioni su `data.*` con la grammatica di docs/03 §3.3 | docs/03 §8, §3.3 | FLT, PRG, REF |
| R36 | Non ripetibile → una sola volta per sempre; ripetibile → una per periodo | docs/03 §8, gamification §7 | PRG |
| R37 | `achievement.progressed` solo al cambio di valore; al traguardo `achievement.completed` (+ badge collegato) | gamification §5, docs/03 §8, F-ACH-02 | PRG |
| R38 | Le azioni interne contano per gli obiettivi solo se elencate in `action_types` | gamification §5 | PRG, REF |
| R39 | Configurazione degli obiettivi (metrica, traguardo, periodo, ripetibilità, badge) | F-ACH-01, BO-15, gamification §2 | ACF |
| R40 | Badge al completamento o da effetto `AWARD_BADGE`; una sola volta per membro; `badge.awarded`; portale: ottenuti e da ottenere | F-ACH-03, gamification §2 (PK), PT-09 | BDG, PRG |
| R41 | Classifiche: `PTS_EARNED/STS_EARNED` dall'importo effettivo di `wallet.points.earned`, `ACTION_COUNT` dalle azioni elencate; periodi `MONTH, EDITION, ALL_TIME` | gamification §2, §5, F-LDB-01, Q-59 | LDB, REF |
| R42 | Ranking: solo membri `ACTIVE`; parimerito → chi ha raggiunto prima il punteggio; top N; portale con soli nickname e la posizione del membro | docs/03 §8, gamification §5, F-LDB-01, PT-10, Q-60 | LDB |
| R43 | Configurazione delle classifiche (metrica, periodo, top N) | F-LDB-01, BO-16, gamification §2 | LCF |
| R44 | Referral: `referral.completed` rientra come azione interna (ruoli `REFERRER`/`REFEREE`) e conta per obiettivi e classifiche solo se elencato | docs/03 §8, gamification §5, F-REF-02 | REF |
| R45 | Referral: legame alla registrazione (un solo invitante, non se stessi), completamento alla prima azione qualificante (member-service) | docs/03 §8, F-REF-01/02, member-service §5 | — (vedi §20) |
| R46 | Pulizia di `achievement_progress` di periodi chiusi da più di 90 giorni | gamification §5 | — (regola non implementata) |
| R47 | Job di fine concorso ogni 5 minuti (spento in demo, lanciato da BO-30) | gamification §5 | END (via endpoint demo) |

### 1.2 Rami del codice

Estratti da `services/gamification-service/src/main` (domain, application, api, messaging, infra per le query che decidono) e dalla macchina a stati comune di `libs/lh-common` usata dai concorsi. Righe dei sorgenti alla data del testbook.

| Ramo (file:riga) | Esito | Regola | Righe |
|---|---|---|---|
| `PlayService.play` :88–90 | memberId assente o vuoto → 422 `MEMBER_REQUIRED` | *ramo senza specifica* | PLY-056, PLY-057 |
| `PlayService.play` :91–92 | codice inesistente (o id al posto del codice) → 404 | docs/06 §2 | PLY-058, PLY-059 |
| `PlayService.play` :97–100 | snapshot assente o non `ACTIVE` → 422 `MEMBER_NOT_ACTIVE` | R01 | PLY-043…046 |
| `PlayService.isPlayable` :82–84, `play` :101–103 | stato ≠ `LIVE` o adesso ∉ [start, end) → 422 `CONTEST_NOT_LIVE` | R02 | PLY-001…042 |
| `PlayService.play` :105–108 | giocate di oggi ≥ tetto → 422 `DAILY_LIMIT_REACHED` | R04 | CRD |
| `PlayService.play` :109–111 | disponibili 0 → 422 `NO_PLAYS_AVAILABLE` | R05 | CRD |
| `PlayService.credits` :71–80 | gratuita non usata oggi; crediti = Σ − giocate CREDIT (≥ 0); tetto | R03, R04 | CRD |
| `PlayService.play` :112 | `FREE_DAILY` se disponibile, altrimenti `CREDIT` | R06 | CRD-046, PTL-007 |
| `PlayService.play` :115–117 | vincite ≥ `maxWinsPerMember` → nessun claim, `LOSE` | R08 | PLY-047…052 |
| `InstantRepository.claim` :110–121 | primo `OPEN` con `instant_at ≤ now`, `ORDER BY instant_at, id`, `SKIP LOCKED` | R07 | CLM |
| `PlayService.play` :118 | `WIN` → `quantity_remaining − 1` | R09 | CLM-010 |
| `PlayService.play` :131 | premio `PHYSICAL` → consegna `PENDING`, altrimenti `NA` | R11 | PRZ-001…003 |
| `PlayService.play` :128–147 | `contest.played` sempre, `contest.won` figlio solo su `WIN` (`points`/`rewardCode` se presenti) | R10, R11 | CLM-012, CLM-013, PRZ-001…003 |
| `PlayRepository.lockMemberContest` :201–204 | lock consultivo per membro+concorso | gamification §5 | — (concorrenza esclusa da questo testbook) |
| `PortalContestsController.live` :59–67 | solo `LIVE` giocabili; membro non attivo → crediti 0 | R13 (non attivo: *ramo senza specifica*) | PTL-001…006 |
| `ContestAdminService.create` :103–109 | codice fuori formato → 422; duplicato → 409 `CODE_TAKEN` | R21 | EDT-001…009 |
| `ContestAdminService.create` :112 | distribuzione assente → `UNIFORM` | *ramo senza specifica* | EDT-018 |
| `ContestAdminService.create` :113, `seedFor` :377–383 | seme assente → derivato dal codice | R26 (seme salvato) | EDT-030 |
| `ContestAdminService.validate` :415–434 | nome, meccanica, distribuzione, periodo, limite ≥ 1, premi (codice, nome, tipo, punti > 0, rewardCode, quantità ≥ 1) → 422 `CONTEST_INVALID` | R21, R11 | EDT, PRZ-004…019 |
| `ContestAdminService.validate` :429, :437 | meccanica o tipo premio `null` → 422 `CONTEST_INVALID` (era NPE → 500, D-1/D-2 risolte) | R21 | EDT-017, PRZ-013 |
| `ContestAdminService.update` :132–134 | codice diverso → 409 `CODE_IMMUTABLE` | *ramo senza specifica* | EDT-040, EDT-041 |
| `ContestAdminService.update` :135–137 | `ENDED`/`ARCHIVED` → 409 `CONTEST_NOT_EDITABLE` | *ramo senza specifica* | EDT-044, EDT-045 |
| `ContestAdminService.update` :140–142 | versione superata → 409 `VERSION_CONFLICT` | R24 | EDT-042, EDT-043 |
| `ContestAdminService.update` :151–169 | `LIVE/PAUSED`: nome, descrizione, `endAt` ammessi senza toccare gli istanti; premi, `startAt`, distribuzione, seme, meccanica, gratuita, limiti, regolamento → 409 `CONTEST_LIVE_LOCKED` (D-5/D-6 risolte) | R22 | EDT-050…063 |
| `ContestAdminService.update` :161–166 | prima di `LIVE`: premi/periodo/distribuzione/seme cambiati → istanti cancellati | R23 | EDT-064…072, LFC-058 |
| `ContestAdminService.duplicate` :186–206 | `-COPY-n` troncato a 40, `DRAFT`, premi pieni, seme dal nuovo codice | R25 | EDT-080…083 |
| `GovernedTransitions.next` (lh-common) :34–42 | APPROVE/REJECT: ruolo della policy o ADMIN; altre: ADMIN/MARKETING; altrimenti 403 | R16 | ROL |
| `GovernedTransitions.next` :44–46 | approvazione spenta: `SUBMIT` da `DRAFT` → `LIVE` | Q-282 | APR-003 |
| `ApprovalStateMachine.next` (lh-common) :21–61 | tabella stato × azione; `PUBLISH` da `DRAFT` con approvazione → 409 `APPROVAL_REQUIRED`; `REJECT` senza commento → 422; altrimenti 409 `INVALID_TRANSITION` | R15, R18 | LFC-001…056, LFC-059, LFC-060 |
| `GovernedTransitions.parse` :24–30 | azione sconosciuta → 422 `INVALID_ACTION` | Q-282 | LFC-066 |
| `ContestAdminService.transition` :220–222 | verso `LIVE` senza istanti → 422 `INSTANTS_NOT_GENERATED` | R17 | LFC-057, LFC-058 |
| `ContestAdminService.transition` :223–231 | storico, audit (override ADMIN marcato), fatto | R19, R16 | LFC-063, LFC-064 |
| `ContestAdminService.approvals` :240–248 | coda dei concorsi `IN_REVIEW` con ruolo richiesto | R19 | LFC-065 |
| `ContestAdminService.changeStatus` :388–398 | `ENDED` → istanti `OPEN` → `VOID`; fatto `contest.status.changed` | R20 | LFC-061, LFC-062, END-007 |
| `ContestAdminService.closeEnded` :270–294 | solo `LIVE` con `end_at ≤ asOf` (i `PAUSED` scaduti restano) | R20, R47 | END-001…007 |
| `GamificationDemoController.asOf` :67–75 | `asOf` data pura → fine di quel giorno a Roma | *ramo senza specifica* | END-008 |
| `ContestAdminService.plantInstant` :305–308 | concorso non `LIVE` → 409 `CONTEST_NOT_LIVE` | *ramo senza specifica* | PLT-006, PLT-007 |
| `ContestAdminService.plantInstant` :310–312 | premio non del concorso → 422 `PRIZE_NOT_FOUND` | *ramo senza specifica* | PLT-008, PLT-017 |
| `ContestAdminService.plantInstant` :313–316, `InstantRepository.plantLast` :175–189 | ultimo `OPEN` del premio (preferendo i non piantati) a `min(now−1 s, più vecchio aperto − 1 s)`; nessuno → 422 `NO_OPEN_INSTANT` | R14, Q-62 (`NO_OPEN_INSTANT`: *ramo senza specifica*) | PLT-001…005, PLT-009 |
| `ContestAdminService.generateInstants` :331–333 | da `LIVE` in poi → 409 `INSTANTS_LOCKED` | R27 | INS-004…007 |
| `ContestAdminService.generateInstants` :336–338 | nessun premio → 422 `CONTEST_INVALID` | *ramo senza specifica* | INS-017 |
| `ContestAdminService.generateInstants` :339–357 | rigenera da zero, quantità ripristinate, seme salvato, audit | R26 | INS-001…003, INS-013…016, INS-019 |
| `InstantGenerator.generate` :35–37 | `endAt ≤ startAt` → errore | R26 (periodo vuoto) | GEN-030, GEN-031 |
| `InstantGenerator.generate` :43–50 | premi in ordine di `sort_order`, `quantity` istanti ciascuno | R26 | GEN-021, GEN-028, GEN-029, GEN-036, INS-020 |
| `InstantGenerator.next` :54–62 | `BUSINESS_HOURS`: rigetta e ricampiona; periodo senza ore utili → errore di validazione, 422 `CONTEST_INVALID` nel servizio (Q-294 DECISA) | R26; Q-294 | GEN-022…025, GEN-032…035, GEN-037, GEN-033, INS-037 |
| `InstantGenerator.inBusinessHours` :64–67 | ora di Roma in [8, 22) | R26 | GEN-001…020, INS-018 |
| `ContestAdminService.updateDelivery` :363–373 | 404; non `WIN` o non `PHYSICAL` → 409 `DELIVERY_NOT_APPLICABLE`; stato ∉ {PENDING, DELIVERED} → 422; nota vuota → null; audit | R12 (nota vuota: *ramo senza specifica*) | PRZ-020…035 |
| `ContestController` `@RequiresRole` | create/update/duplicate/generate ADMIN, MARKETING; transitions + LEGAL; instants ADMIN, LEGAL; delivery ADMIN, CARE | R16, R28, R12 | EDT-031…035, EDT-046, EDT-083, INS-008…012, INS-021…027, PRZ-023…026 |
| `ContestController.instants` :155–157 | pagina ≥ 0, `size` in [1, 100] | R28 | INS-036 |
| `InstantRepository.histogram` :77–89 | conteggio per giorno di Roma e stato | R28 | INS-028…031, INS-034, INS-035 |
| `ContestController.winners/winnersCsv/stats` :167–207 | vincite, CSV, statistiche e serie continua | R29 | RPT-001…004 |
| `GamificationDemoController` `@RequiresRole(ADMIN)` | pianta istante e job solo ADMIN | docs/06 §3 | PLT-010…015, END-009 |
| `PlaysGrantHandler.handle` :50–73 | senza dati/membro → DLQ `INVALID_EFFECT`; concorso sconosciuto → DLQ `CONTEST_NOT_FOUND`; `count` < 1 o assente → 1; `effectId` già visto → nulla | R31 (`count` < 1: *ramo senza specifica*) | GRT-001…011 |
| `MemberSnapshotHandler.handle` :32–50 | stato da `status.changed`; upsert da `registered/updated`; anonimizzazione → cancellazione (Q-124, TB-GOV) | R32 | SNP-001…005 |
| `MemberSnapshotHandler.nickname` :53–63 | nickname, altrimenti nome + iniziale; nome assente → null | R32 (nome assente: *ramo senza specifica*) | NCK-001…005 |
| `AchievementService.onAction` :53–55 | subject senza membro o tipo non azione → ignorata | *ramo senza specifica* (infrastruttura) | — |
| `AchievementService.onAction` :56–59 | snapshot non `ACTIVE` → ignorata; **snapshot assente → conta** | R01 (assente: *ramo senza specifica*) | PRG-010…013 |
| `AchievementRepository.findActiveFor` :50–53 | solo obiettivi `ACTIVE` che elencano il tipo | R38 | PRG-014, PRG-015, PRG-033, PRG-034, REF-003 |
| `AchievementService.onAction` :63–65 | filtro falso → saltato | R35 | PRG-027, REF-002 |
| `AchievementService.onAction` :66–73 | non ripetibile già completato, o periodo già completato → saltato | R36 | PRG-003…006 |
| `AchievementService.onAction` :75–97 | valore invariato → nessun fatto; `progressed` (valore limitato al traguardo); `completed` + badge | R37, R40 | PRG |
| `AchievementRules.periodKey` :33–40 | `MONTH` → `aaaa-mm`, `EDITION` → `ED-aaaa`, ogni altro periodo → `EVER` (`DAY`, `WEEK` non ammessi, Q-159) | R34 | PER |
| `AchievementRules.advance` :78–103 | `COUNT` +1; `SUM` troncato, ≤ 0 ignorato; `DISTINCT_TYPES`; `STREAK` stessa unità / unità successiva / buco | R33 (troncamento e segno: *ramo senza specifica*) | MET, STK |
| `AchievementRules.matches/leaf/compare` :58–352 | grammatica di docs/03 §3.3 su `data.*` come `ConditionEvaluator` di campaign-service: `all/any/not` annidati, 14 comparatori, `[*]`, campo assente → falso tranne `nexists`, tipi incompatibili → falso (D-7 risolta); comparatore sconosciuto tra numeri = `eq` (Q-295) | R35 | FLT |
| `AchievementRules.field` :110–114 | `tipo.data.campo` (forma di docs/10) → `campo` | *ramo senza specifica* | MET-008 |
| `AchievementAdminService.create/update/build` :50–130 | codice `ACH-…` (422), duplicato 409, `CODE_IMMUTABLE`, validazioni metrica/periodo/tipi/traguardo/SUM/STREAK/DISTINCT/badge/stato | R39 (prefisso, `DISTINCT` oltre i tipi, codice immutabile: *rami senza specifica*) | ACF |
| `AchievementAdminService.build` :113–114 | metrica `null` → 422 `ACHIEVEMENT_INVALID` (D-3 risolta); periodi ammessi solo `EVER, MONTH, EDITION` (Q-159) | R39, R34 | ACF-002, ACF-017…019 |
| `AchievementAdminService.saveBadge` :78–97 | codice `BDG-…`, nome obbligatorio, duplicato 409, 404 | R40 (prefisso: *ramo senza specifica*) | BDG-008…013 |
| `BadgeAwardHandler.handle` :32–42, `BadgeService.award` :35–45 | DLQ `INVALID_EFFECT` / `BADGE_NOT_FOUND`; già posseduto → nessun fatto | R40 | BDG-001…006, PRG-031 |
| `PortalAchievementsController` :55–103 | periodo corrente; non ripetibile completato mostra il completamento; `pct` | R37, R36 | PRG-032, BDG-007 |
| `LeaderboardService.onPointsEarned` :48–62 | importo ≤ 0 o senza membro → ignorato; classifiche della valuta | R41 | LDB-001…005, LDB-008…010, LDB-023, LDB-024 |
| `LeaderboardService.onAction` :66–78 | `ACTION_COUNT` +1 se il tipo è elencato (nessun filtro di stato: lo applica il ranking) | R41 | LDB-006, LDB-007, REF-004 |
| `LeaderboardService.create/update/build` :88–140 | codice `LDB-…`, duplicato, `CODE_IMMUTABLE`, `LEADERBOARD_LOCKED`, validazioni (top N 3…50) | R43 (prefisso, top N, blocchi: *rami senza specifica*) | LCF |
| `LeaderboardService.build` :131 | metrica `null` → 422 `LEADERBOARD_INVALID` (D-4 risolta) | R43 | LCF-024 |
| `LeaderboardRepository.add` :72–80 | somma; `reached_at` = ora dell'ultimo aumento | R42 | LDB-012, LDB-013 |
| `LeaderboardRepository.ranking` :83–95 | solo snapshot `ACTIVE`, `score > 0`, ordine `score desc, reached_at, member_id`, limite | R42 | LDB-011…022 |
| `PortalLeaderboardsController` :50–65 | classifica non attiva → 404; nickname assente → «Socio Aurora»; `me` | R42 (404 e segnaposto: *rami senza specifica*) | LDB-020…022 |
| `GamificationJobs.closeContests` :26–29 | cron ogni 5 minuti con `jobs.enabled` | R47 | — (lo stesso metodo è provato da END via endpoint demo) |

## 2. Giocata: tabella decisionale (PLY)

Regole R01, R02, R07, R08 (§1.1). Ordine dei controlli non specificato: le righe con due condizioni false sono **AMBIGUO**.

**Domini dei valori**

| Ingresso | Classi valide | Classi non valide / limiti |
|---|---|---|
| stato del concorso | `LIVE` | `DRAFT`, `IN_REVIEW`, `APPROVED`, `PAUSED`, `ENDED`, `ARCHIVED` |
| adesso rispetto al periodo | `startAt` (min), metà, `endAt − 1 ms` (max) | `startAt − 1 ms` (min−1), `endAt` (max, escluso), `endAt + 1 g` |
| stato del membro | `ACTIVE` | `INACTIVE`, `BLOCKED`, `ANONYMIZED`, sconosciuto (nessuno snapshot) |
| `maxWinsPerMember` × vincite | assente; 2 con 1 (limite−1); 1 con 0 | 2 con 2 (limite), 2 con 3 (limite+1) |
| istante maturo | sì / no | — |
| `memberId` | presente | assente, soli spazi |
| concorso nel path | codice esistente | codice inesistente, id al posto del codice |

**Strategia**: stato (7) × periodo (6) = 42 ≤ 64 → **tabella completa** (PLY-001…042) con membro `ACTIVE` e gratuita disponibile; stato del membro: ogni classe non valida da sola (PLY-043…046); `maxWinsPerMember` × istante maturo: tabella completa delle 6 combinazioni significative (PLY-047…052); ordine dei controlli: 3 coppie di condizioni false (PLY-053…055); corpo e path: ogni classe non valida da sola (PLY-056…059). Riduzione: 7 × 6 × 5 × 5 × 2 = 2 100 combinazioni → 59 righe (le classi del membro e delle vincite non interagiscono con stato e periodo, che respingono prima).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-PLY-001 | stato `DRAFT` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-002 | stato `DRAFT` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-003 | stato `DRAFT` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-004 | stato `DRAFT` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-005 | stato `DRAFT` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-006 | stato `DRAFT` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-007 | stato `IN_REVIEW` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-008 | stato `IN_REVIEW` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-009 | stato `IN_REVIEW` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-010 | stato `IN_REVIEW` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-011 | stato `IN_REVIEW` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-012 | stato `IN_REVIEW` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-013 | stato `APPROVED` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-014 | stato `APPROVED` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-015 | stato `APPROVED` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-016 | stato `APPROVED` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-017 | stato `APPROVED` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-018 | stato `APPROVED` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-019 | stato `LIVE` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-020 | stato `LIVE` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 200 `FREE_DAILY`, `LOSE`, `playsAvailable` 1→0 | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-021 | stato `LIVE` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 200 `FREE_DAILY`, `LOSE`, `playsAvailable` 1→0 | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-022 | stato `LIVE` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 200 `FREE_DAILY`, `LOSE`, `playsAvailable` 1→0 | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-023 | stato `LIVE` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-024 | stato `LIVE` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-025 | stato `PAUSED` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-026 | stato `PAUSED` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-027 | stato `PAUSED` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-028 | stato `PAUSED` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-029 | stato `PAUSED` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-030 | stato `PAUSED` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-031 | stato `ENDED` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-032 | stato `ENDED` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-033 | stato `ENDED` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-034 | stato `ENDED` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-035 | stato `ENDED` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-036 | stato `ENDED` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-037 | stato `ARCHIVED` · adesso 1 ms prima di startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-038 | stato `ARCHIVED` · adesso esattamente a startAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-039 | stato `ARCHIVED` · adesso a metà periodo · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-040 | stato `ARCHIVED` · adesso 1 ms prima di endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-041 | stato `ARCHIVED` · adesso esattamente a endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-042 | stato `ARCHIVED` · adesso un giorno dopo endAt · membro ACTIVE · gratuita disponibile | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | gamification §3, docs/03 §6 [startAt, endAt), F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-043 | membro INACTIVE (concorso LIVE, IN) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | docs/03 §2, gamification §3 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-044 | membro BLOCKED (concorso LIVE, IN) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | docs/03 §2, gamification §3 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-045 | membro ANONYMIZED (concorso LIVE, IN) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | docs/03 §2, gamification §3 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-046 | membro senza snapshot (sconosciuto) (concorso LIVE, IN) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | docs/03 §2 (solo ACTIVE gioca) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-047 | maxWinsPerMember assente e 3 vincite precedenti con istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `WIN`, istante `CLAIMED` | docs/03 §6 (default illimitato) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-048 | nessun istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `LOSE` | F-IW-04 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-049 | maxWinsPerMember 2 con 1 vincita (limite-1) e istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `WIN`, istante `CLAIMED` | docs/03 §6 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-050 | maxWinsPerMember 2 con 2 vincite (limite) e istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `LOSE`, istante resta `OPEN` | docs/03 §6 (il claim lo verifica prima) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-051 | maxWinsPerMember 2 con 3 vincite (limite+1) e istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `LOSE`, istante resta `OPEN` | docs/03 §6 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-052 | maxWinsPerMember 1 con 0 vincite e istante maturo (concorso LIVE, IN) | 200 `FREE_DAILY`, `WIN`, istante `CLAIMED` | docs/03 §6 | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-053 | AMBIGUO ordine: membro BLOCKED e concorso DRAFT (concorso DRAFT, IN) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | AMBIGUO (ordine dei controlli non specificato) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-054 | AMBIGUO ordine: concorso PAUSED e nessuna giocata (concorso PAUSED, IN) | 422 `CONTEST_NOT_LIVE`, nessuna giocata registrata | AMBIGUO (ordine dei controlli non specificato) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-055 | AMBIGUO ordine: membro sconosciuto e periodo finito (concorso LIVE, AFTER) | 422 `MEMBER_NOT_ACTIVE`, nessuna giocata registrata | AMBIGUO (ordine dei controlli non specificato) | `TestbookGamPlayIT#giocata` |
| TB-GAM-PLY-056 | `memberId` assente nel corpo | 422 `MEMBER_REQUIRED` **AMBIGUO** | *ramo senza specifica* (codice non indicato da gamification §3) | `TestbookGamPlayIT#memberMissing` |
| TB-GAM-PLY-057 | `memberId` di soli spazi | 422 `MEMBER_REQUIRED` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPlayIT#memberBlank` |
| TB-GAM-PLY-058 | codice concorso inesistente | 404 | docs/06 §2 | `TestbookGamPlayIT#unknownContest` |
| TB-GAM-PLY-059 | id del concorso al posto del codice in `/v1/portal/contests/{code}/play` | 404 **AMBIGUO** | gamification §3 (path `{code}`) contro docs/06 §2 («id o code nei path») | `TestbookGamPlayIT#idInsteadOfCode` |

## 3. Crediti e giorno di Roma (CRD)

Regole R03, R04, R05, R06.

**Domini dei valori**

| Ingresso | Classi |
|---|---|
| gratuita giornaliera | non prevista (`freePlayDaily=false`) · prevista e non usata oggi · già usata oggi |
| crediti residui | 0 · 1 · n = 3 |
| tetto giornaliero × giocate di oggi | nessun tetto · tetto 5 con 1 · tetto 5 con 4 (limite−1) · tetto 5 con 5 (limite) |
| tempo | 23:59:59 / 00:00:00 di Roma, stesso giorno UTC e giorni UTC diversi, cambio d'ora di marzo e ottobre, 29 febbraio, fine mese |

**Strategia**: gratuita (3) × crediti (3) × tetto (4) = 36 ≤ 64 → **tabella completa** (CRD-001…036). Ogni riga verifica prima il portale (`playsAvailable`, `freePlayAvailable`, `credits`) e poi la giocata (tipo, `playsAvailable` dopo). Le righe col tetto raggiunto e nessuna giocata sono **AMBIGUO** (due errori veri). Giorno di Roma: una riga per ogni confine temporale (CRD-037…046), ciascuna con due giocate.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-CRD-001 | gratuita non prevista · crediti 0 · nessun tetto giornaliero | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-002 | gratuita non prevista · crediti 0 · tetto 5 con 1 giocata oggi | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-003 | gratuita non prevista · crediti 0 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-004 | gratuita non prevista · crediti 0 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | AMBIGUO (DAILY_LIMIT e NO_PLAYS entrambi veri) · docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-005 | gratuita non prevista · crediti 1 · nessun tetto giornaliero | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-006 | gratuita non prevista · crediti 1 · tetto 5 con 1 giocata oggi | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-007 | gratuita non prevista · crediti 1 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-008 | gratuita non prevista · crediti 1 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-009 | gratuita non prevista · crediti 3 · nessun tetto giornaliero | disponibili 3; 200 `CREDIT`, poi 2 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-010 | gratuita non prevista · crediti 3 · tetto 5 con 1 giocata oggi | disponibili 3; 200 `CREDIT`, poi 2 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-011 | gratuita non prevista · crediti 3 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-012 | gratuita non prevista · crediti 3 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-013 | gratuita prevista e non usata oggi · crediti 0 · nessun tetto giornaliero | disponibili 1; 200 `FREE_DAILY`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-014 | gratuita prevista e non usata oggi · crediti 0 · tetto 5 con 1 giocata oggi | disponibili 1; 200 `FREE_DAILY`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-015 | gratuita prevista e non usata oggi · crediti 0 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `FREE_DAILY`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-016 | gratuita prevista e non usata oggi · crediti 0 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-017 | gratuita prevista e non usata oggi · crediti 1 · nessun tetto giornaliero | disponibili 2; 200 `FREE_DAILY`, poi 1 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-018 | gratuita prevista e non usata oggi · crediti 1 · tetto 5 con 1 giocata oggi | disponibili 2; 200 `FREE_DAILY`, poi 1 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-019 | gratuita prevista e non usata oggi · crediti 1 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `FREE_DAILY`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-020 | gratuita prevista e non usata oggi · crediti 1 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-021 | gratuita prevista e non usata oggi · crediti 3 · nessun tetto giornaliero | disponibili 4; 200 `FREE_DAILY`, poi 3 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-022 | gratuita prevista e non usata oggi · crediti 3 · tetto 5 con 1 giocata oggi | disponibili 4; 200 `FREE_DAILY`, poi 3 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-023 | gratuita prevista e non usata oggi · crediti 3 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `FREE_DAILY`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-024 | gratuita prevista e non usata oggi · crediti 3 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-025 | gratuita già usata oggi · crediti 0 · nessun tetto giornaliero | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-026 | gratuita già usata oggi · crediti 0 · tetto 5 con 1 giocata oggi | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-027 | gratuita già usata oggi · crediti 0 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 0; 422 `NO_PLAYS_AVAILABLE` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-028 | gratuita già usata oggi · crediti 0 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | AMBIGUO (DAILY_LIMIT e NO_PLAYS entrambi veri) · docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-029 | gratuita già usata oggi · crediti 1 · nessun tetto giornaliero | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-030 | gratuita già usata oggi · crediti 1 · tetto 5 con 1 giocata oggi | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-031 | gratuita già usata oggi · crediti 1 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-032 | gratuita già usata oggi · crediti 1 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-033 | gratuita già usata oggi · crediti 3 · nessun tetto giornaliero | disponibili 3; 200 `CREDIT`, poi 2 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-034 | gratuita già usata oggi · crediti 3 · tetto 5 con 1 giocata oggi | disponibili 3; 200 `CREDIT`, poi 2 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-035 | gratuita già usata oggi · crediti 3 · tetto 5 con 4 giocate oggi (limite-1) | disponibili 1; 200 `CREDIT`, poi 0 | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-036 | gratuita già usata oggi · crediti 3 · tetto 5 con 5 giocate oggi (limite) | disponibili 0; 422 `DAILY_LIMIT_REACHED` | docs/03 §6, gamification §3, §5 | `TestbookGamPlayIT#crediti` |
| TB-GAM-CRD-037 | gratuita alle 23:59:59 e di nuovo alle 00:00:00 di Roma (stesso giorno UTC): gratuita sì, crediti 0, tetto -; giocate a `2026-03-10T22:59:59Z` e `2026-03-10T23:00:00Z` | prima 200 `FREE_DAILY`; seconda 200 `FREE_DAILY` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-038 | gratuita alle 00:00:00 e di nuovo alle 23:59:59 di Roma (giorni UTC diversi): gratuita sì, crediti 0, tetto -; giocate a `2026-03-10T23:00:00Z` e `2026-03-11T22:59:59Z` | prima 200 `FREE_DAILY`; seconda 422 `NO_PLAYS_AVAILABLE` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-039 | gratuita a cavallo della mezzanotte del cambio d'ora di marzo (CEST): gratuita sì, crediti 0, tetto -; giocate a `2026-03-29T21:59:59Z` e `2026-03-29T22:00:00Z` | prima 200 `FREE_DAILY`; seconda 200 `FREE_DAILY` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-040 | gratuita a cavallo della mezzanotte del cambio d'ora di ottobre (CET): gratuita sì, crediti 0, tetto -; giocate a `2026-10-25T22:59:59Z` e `2026-10-25T23:00:00Z` | prima 200 `FREE_DAILY`; seconda 200 `FREE_DAILY` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-041 | due gratuite nello stesso 29 febbraio di Roma: gratuita sì, crediti 0, tetto -; giocate a `2028-02-28T23:30:00Z` e `2028-02-29T22:59:59Z` | prima 200 `FREE_DAILY`; seconda 422 `NO_PLAYS_AVAILABLE` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-042 | gratuita il 31 marzo alle 23:59:59 e il 1 aprile alle 00:00 di Roma: gratuita sì, crediti 0, tetto -; giocate a `2026-03-31T21:59:59Z` e `2026-03-31T22:00:00Z` | prima 200 `FREE_DAILY`; seconda 200 `FREE_DAILY` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-043 | tetto 1 al giorno: nuova giocata a mezzanotte di Roma: gratuita no, crediti 3, tetto 1; giocate a `2026-03-10T22:59:59Z` e `2026-03-10T23:00:00Z` | prima 200 `CREDIT`; seconda 200 `CREDIT` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-044 | tetto 1 al giorno: stesso giorno di Roma a cavallo della mezzanotte UTC: gratuita no, crediti 3, tetto 1; giocate a `2026-03-10T23:30:00Z` e `2026-03-11T00:30:00Z` | prima 200 `CREDIT`; seconda 422 `DAILY_LIMIT_REACHED` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-045 | i crediti consumati non si rinnovano il giorno dopo: gratuita no, crediti 1, tetto -; giocate a `2026-03-10T10:00:00Z` e `2026-03-11T10:00:00Z` | prima 200 `CREDIT`; seconda 422 `NO_PLAYS_AVAILABLE` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |
| TB-GAM-CRD-046 | prima la gratuita e poi i crediti nello stesso giorno: gratuita sì, crediti 1, tetto -; giocate a `2026-03-10T10:00:00Z` e `2026-03-10T10:05:00Z` | prima 200 `FREE_DAILY`; seconda 200 `CREDIT` | docs/03 §6 (gratuita «non ancora usata oggi (Europe/Rome)», tetto giornaliero), gamification §5 (ordine crediti) | `TestbookGamPlayIT#sequenza` |

## 4. Portale e risoluzione degli istanti (PTL, CLM)

Regole R07, R09, R10, R13. Concorrenza (50 giocate su un istante) esclusa da questo testbook: coperta da `PlayIT` e dal dominio TB-E2E.

**Domini**: posizione dell'istante rispetto ad adesso {+1 ms, 0, −1 ms, −30 g}; stato dell'istante {`OPEN`, `CLAIMED`, `VOID`}; numero di istanti maturi {0, 1, 2 di premi diversi}; concorso dell'istante {questo, un altro}. **Strategia**: confini temporali e stati da soli (CLM-001…006), poi un caso per ogni interazione (CLM-007…014); portale: ogni stato del concorso rilevante e ogni condizione del periodo da sola (PTL-001…006) più lo storico (PTL-007).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-CLM-001 | istante OPEN 1 ms dopo adesso (`instant_at` = adesso +1 ms, stato OPEN) | 200 `LOSE`; istante resta `OPEN` | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-002 | istante OPEN esattamente adesso (`instant_at` = adesso +0 ms, stato OPEN) | 200 `WIN`; istante `CLAIMED`, premio residuo −1 | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-003 | istante OPEN 1 ms prima di adesso (`instant_at` = adesso -1 ms, stato OPEN) | 200 `WIN`; istante `CLAIMED`, premio residuo −1 | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-004 | istante OPEN maturato da 30 giorni e mai riscosso (`instant_at` = adesso -2592000000 ms, stato OPEN) | 200 `WIN`; istante `CLAIMED`, premio residuo −1 | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-005 | istante VOID nel passato (annullato a fine concorso) (`instant_at` = adesso -1000 ms, stato VOID) | 200 `LOSE`; istante resta `VOID` | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-006 | istante CLAIMED nel passato (già riscosso) (`instant_at` = adesso -1000 ms, stato CLAIMED) | 200 `LOSE`; istante resta `CLAIMED` | docs/03 §6 (claim `status='OPEN' AND instant_at <= now()`), F-IW-04 | `TestbookGamPlayIT#claim` |
| TB-GAM-CLM-007 | due istanti maturi: premio B a −2 s, premio A a −1 s | vince B (il più vecchio); l'istante di A resta `OPEN` | docs/03 §6 (`ORDER BY instant_at LIMIT 1`) | `TestbookGamPlayIT#oldestFirst` |
| TB-GAM-CLM-008 | un istante maturo, due membri giocano in sequenza | primo `WIN`, secondo `LOSE`; un solo istante `CLAIMED` | F-IW-04 (claim), docs/03 §6 | `TestbookGamPlayIT#oneInstantOneWin` |
| TB-GAM-CLM-009 | istante maturo solo in un altro concorso | `LOSE`; l'istante dell'altro concorso resta `OPEN` | docs/03 §6 (`contest_id=:contest`) | `TestbookGamPlayIT#otherContestInstant` |
| TB-GAM-CLM-010 | vincita | istante `CLAIMED` con `claimed_by`, `play_id`, `claimed_at` = adesso; residuo 5 → 4; giocata `WIN` | docs/03 §6 (SQL del claim e decremento) | `TestbookGamPlayIT#winMarksInstant` |
| TB-GAM-CLM-011 | nessun istante maturo | `LOSE`; 5 istanti `OPEN`; residuo invariato | docs/03 §6 | `TestbookGamPlayIT#loseTouchesNothing` |
| TB-GAM-CLM-012 | perdita | un `contest.played` {`contestCode`, `playId`, `outcome` LOSE, `kind` FREE_DAILY}; nessun `contest.won` | gamification §4, §5, contratto `fact.contest.played` | `TestbookGamPlayIT#loseFacts` |
| TB-GAM-CLM-013 | vincita | `contest.played` e `contest.won` con lo stesso `lhcorrelationid` della risposta | gamification §5, §7 (stesso tracciato), F-IW-06 | `TestbookGamPlayIT#winFacts` |
| TB-GAM-CLM-014 | due istanti maturi, gratuita + 1 credito, nessun `maxWinsPerMember` | `WIN`, `WIN` | docs/03 §6 («può vincere più volte») | `TestbookGamPlayIT#winTwice` |
| TB-GAM-PTL-001 | concorso `LIVE` in periodo | in elenco con `mechanic`, `endAt`, `playsAvailable` 1, gratuita disponibile, premi `{code, type, …}`; nessuna quantità né istante | gamification §3 («mai quantità residue né istanti»), PT-05 | `TestbookGamPlayIT#portalListsLive` |
| TB-GAM-PTL-002 | concorso `LIVE` con `startAt` domani | non in elenco **AMBIGUO** | gamification §3 dice «concorsi LIVE» senza parlare del periodo | `TestbookGamPlayIT#portalHidesFuture` |
| TB-GAM-PTL-003 | concorso `LIVE` con `endAt` ieri (job non ancora passato) | non in elenco **AMBIGUO** | come sopra | `TestbookGamPlayIT#portalHidesExpired` |
| TB-GAM-PTL-004 | concorso `PAUSED` | non in elenco | gamification §3, PT-05 (solo `LIVE`) | `TestbookGamPlayIT#portalHidesPaused` |
| TB-GAM-PTL-005 | concorso `APPROVED` non pubblicato | non in elenco | gamification §3, PT-05 | `TestbookGamPlayIT#portalHidesApproved` |
| TB-GAM-PTL-006 | membro `BLOCKED` con 2 crediti | concorso in elenco con 0 giocate e gratuita non disponibile **AMBIGUO** | nessuna fonte sul portale di un membro non attivo (docs/03 §2 solo per la giocata) | `TestbookGamPlayIT#portalBlockedMember` |
| TB-GAM-PTL-007 | tre giocate del membro (gratuita + 2 crediti) e una di un altro | storico con 3 giocate, più recente prima, tipo ed esito | gamification §3 (storico), PT-06 («Le tue giocate»), §5 (ordine crediti) | `TestbookGamPlayIT#portalHistory` |

## 5. Premi e consegna (PRZ)

Regole R10, R11, R12, R21.

**Domini**: tipo {`POINTS`, `COUPON`, `PHYSICAL`, `DIGITAL` (sconosciuto), assente}; punti {assente, 0 (min−1), 1 (min), −5}; `rewardCode` {assente, spazi, valido}; quantità {assente, −1, 0 (min−1), 1 (min)}; consegna: giocata {vincita PHYSICAL, POINTS, COUPON, perdita, inesistente} × ruolo {ADMIN, CARE, MARKETING, LEGAL, ANALYST, nessuno} × stato {`DELIVERED`, `PENDING`, `NA`, `SHIPPED`, assente}. **Strategia**: vincita per ogni tipo (PRZ-001…003); validazione: ogni classe non valida e ogni limite da solo (PRZ-004…019); consegna: il ruolo, lo stato e il tipo di giocata variano uno alla volta a partire dal caso valido (CARE, `DELIVERED`, PHYSICAL) (PRZ-020…034), più l'audit (PRZ-035). Riduzione: 5 × 6 × 5 = 150 → 16 righe (guasto singolo).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-PRZ-001 | vincita di un premio POINTS da 50 punti: premio `POINTS` da 50 punti, istante maturo | 200 `WIN`; `prize` e `contest.won` con `prizeType`, `points` 50, nessun altro campo di consegna; consegna `NA` | F-IW-02, F-IW-06, docs/03 §6, gamification §2, contratto `fact.contest.won` | `TestbookGamPrizeIT#vincitaPerTipo` |
| TB-GAM-PRZ-002 | vincita di un premio COUPON collegato a RWD-COFFEE: premio `COUPON` → `RWD-COFFEE`, istante maturo | 200 `WIN`; `prize` e `contest.won` con `prizeType`, `rewardCode`, nessun altro campo di consegna; consegna `NA` | F-IW-02, F-IW-06, docs/03 §6, gamification §2, contratto `fact.contest.won` | `TestbookGamPrizeIT#vincitaPerTipo` |
| TB-GAM-PRZ-003 | vincita di un premio PHYSICAL: premio `PHYSICAL`, istante maturo | 200 `WIN`; `prize` e `contest.won` con `prizeType`, nessun altro campo di consegna; consegna `PENDING` | F-IW-02, F-IW-06, docs/03 §6, gamification §2, contratto `fact.contest.won` | `TestbookGamPrizeIT#vincitaPerTipo` |
| TB-GAM-PRZ-004 | POINTS senza punti (tipo POINTS, punti -, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-005 | POINTS con 0 punti (min-1) (tipo POINTS, punti 0, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-006 | POINTS con 1 punto (min) (tipo POINTS, punti 1, rewardCode -, quantità 3) | 201, quantità residua = totale | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-007 | POINTS con punti negativi (tipo POINTS, punti -5, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-008 | COUPON senza rewardCode (tipo COUPON, punti -, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-009 | COUPON con rewardCode di soli spazi (tipo COUPON, punti -, rewardCode «   », quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-010 | COUPON con rewardCode valido (tipo COUPON, punti -, rewardCode RWD-COFFEE, quantità 3) | 201, quantità residua = totale | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-011 | PHYSICAL senza punti né rewardCode (tipo PHYSICAL, punti -, rewardCode -, quantità 3) | 201, quantità residua = totale | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-012 | tipo sconosciuto DIGITAL (tipo DIGITAL, punti -, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-013 | tipo assente (tipo -, punti -, rewardCode -, quantità 3) | 422 `CONTEST_INVALID`, nessun concorso creato — D-2 risolta (§21.5) | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-014 | quantità 0 (min-1) (tipo PHYSICAL, punti -, rewardCode -, quantità 0) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-015 | quantità 1 (min) (tipo PHYSICAL, punti -, rewardCode -, quantità 1) | 201, quantità residua = totale | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-016 | quantità assente (tipo PHYSICAL, punti -, rewardCode -, quantità -) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-017 | quantità negativa (tipo PHYSICAL, punti -, rewardCode -, quantità -1) | 422 `CONTEST_INVALID`, nessun concorso creato | F-IW-02, BO-14 (tipo: POINTS n / COUPON premio / PHYSICAL), docs/06 §2 (422), contratto `fact.contest.won` (`points` ≥ 1) | `TestbookGamPrizeIT#validazione` |
| TB-GAM-PRZ-018 | due premi con lo stesso codice | 422 `CONTEST_INVALID` | gamification §2 (codice premio), docs/06 §2 | `TestbookGamPrizeIT#duplicatePrizeCodes` |
| TB-GAM-PRZ-019 | premio con nome di soli spazi | 422 `CONTEST_INVALID` | gamification §2 (`name`), contratto `fact.contest.won` (`prizeName` minLength 1) | `TestbookGamPrizeIT#prizeWithoutName` |
| TB-GAM-PRZ-020 | CARE segna DELIVERED una vincita PHYSICAL con nota: giocata PHYSICAL, ruolo CARE, stato DELIVERED, nota «Spedito con corriere» | 204; consegna `DELIVERED`; nota e stato visibili tra i vincitori | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-021 | ADMIN segna DELIVERED una vincita PHYSICAL: giocata PHYSICAL, ruolo ADMIN, stato DELIVERED | 204; consegna `DELIVERED` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-022 | CARE riporta a PENDING una vincita PHYSICAL: giocata PHYSICAL, ruolo CARE, stato PENDING | 204; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-023 | MARKETING non può aggiornare la consegna: giocata PHYSICAL, ruolo MARKETING, stato DELIVERED | 403; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-024 | LEGAL non può aggiornare la consegna: giocata PHYSICAL, ruolo LEGAL, stato DELIVERED | 403; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-025 | ANALYST non può aggiornare la consegna: giocata PHYSICAL, ruolo ANALYST, stato DELIVERED | 403; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-026 | senza intestazione X-LH-Actor: giocata PHYSICAL, ruolo NONE, stato DELIVERED | 403; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-027 | stato SHIPPED fuori enumerato: giocata PHYSICAL, ruolo CARE, stato SHIPPED | 422; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-028 | stato assente: giocata PHYSICAL, ruolo CARE, stato - | 422; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-029 | stato NA (non applicabile a un premio fisico): giocata PHYSICAL, ruolo CARE, stato NA | 422; consegna `PENDING` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-030 | consegna manuale su una vincita POINTS: giocata POINTS, ruolo CARE, stato DELIVERED | rifiutata (409 o 422); consegna `NA` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-031 | consegna manuale su una vincita COUPON: giocata COUPON, ruolo CARE, stato DELIVERED | rifiutata (409 o 422); consegna `NA` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-032 | consegna manuale su una giocata perdente: giocata LOSE, ruolo CARE, stato DELIVERED | rifiutata (409 o 422); consegna `NA` | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-033 | giocata inesistente: giocata UNKNOWN, ruolo CARE, stato DELIVERED | 404 | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-034 | Q-297 nota di soli spazi: giocata PHYSICAL, ruolo CARE, stato DELIVERED, nota «   » | 204; consegna `DELIVERED`; nota null (Q-297 DECISA) | gamification §2 (`NA, PENDING, DELIVERED`), §3 (`{status, note}` per premi PHYSICAL — CARE/ADMIN), docs/08 §2 `delivery.handle`, docs/06 §3 | `TestbookGamPrizeIT#consegna` |
| TB-GAM-PRZ-035 | consegna aggiornata da CARE | una voce di audit `PLAY:<playId>` con attore CARE e stato prima/dopo | gamification §4 (audit delle consegne), docs/06 §3 | `TestbookGamPrizeIT#deliveryAudit` |

## 6. Istante piantato (PLT)

Regola R14 (Q-62).

**Domini**: istanti maturi di altri premi {nessuno, uno più vecchio}; piantati per lo stesso premio {1, 2}; stato del concorso {`LIVE`, `DRAFT`, `PAUSED`}; premio {del concorso, sconosciuto, senza istanti aperti, assente}; ruolo {ADMIN, MARKETING, LEGAL, CARE, ANALYST, nessuno}. **Strategia**: ogni classe da sola a partire dal caso valido (LIVE, premio con istanti, ADMIN).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-PLT-001 | nessun istante maturo; pianta PRZ-A | istante a adesso − 1 s, `planted=true`; la giocata successiva vince PRZ-A | gamification §3, F-IW-08, BO-14 | `TestbookGamPrizeIT#plantNow` |
| TB-GAM-PLT-002 | istante di PRZ-B maturo da un'ora; pianta PRZ-A | istante a (più vecchio − 1 s); la giocata vince PRZ-A; l'istante di PRZ-B resta `OPEN` | Q-62 | `TestbookGamPrizeIT#plantBeforeOlder` |
| TB-GAM-PLT-003 | pianta PRZ-A | stessi istanti per premio, stessi aperti, stesse quantità (montepremi invariato) | gamification §3 | `TestbookGamPrizeIT#plantKeepsPool` |
| TB-GAM-PLT-004 | istanti generati sul periodo; pianta PRZ-A | si sposta l'ultimo istante `OPEN` di PRZ-A | gamification §3 («sottraendolo dall'ultimo OPEN dello stesso premio») | `TestbookGamPrizeIT#plantMovesLast` |
| TB-GAM-PLT-005 | due piantati per PRZ-A, due giocate | due istanti diversi; entrambe le giocate vincono PRZ-A | gamification §3, Q-62 | `TestbookGamPrizeIT#plantTwice` |
| TB-GAM-PLT-006 | concorso `DRAFT` | 409 `CONTEST_NOT_LIVE` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPrizeIT#plantDraft` |
| TB-GAM-PLT-007 | concorso `PAUSED` | 409 `CONTEST_NOT_LIVE` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPrizeIT#plantPaused` |
| TB-GAM-PLT-008 | premio non presente nel concorso | 422 `PRIZE_NOT_FOUND` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPrizeIT#plantUnknownPrize` |
| TB-GAM-PLT-009 | tutti gli istanti di PRZ-A già riscossi | 422 `NO_OPEN_INSTANT` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPrizeIT#plantNoOpen` |
| TB-GAM-PLT-010 | MARKETING non pianta istanti | 403, nessun istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-011 | LEGAL non pianta istanti | 403, nessun istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-012 | CARE non pianta istanti | 403, nessun istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-013 | ANALYST non pianta istanti | 403, nessun istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-014 | senza intestazione X-LH-Actor | 403, nessun istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-015 | ADMIN pianta un istante | 200, un istante piantato | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN), docs/08 §2 `demo.admin` | `TestbookGamPrizeIT#piantaRuoli` |
| TB-GAM-PLT-016 | pianta PRZ-A | voce di audit `CONTEST:<code>` con `instantId` e `planted=true` | gamification §4 (audit degli istanti piantati) | `TestbookGamPrizeIT#plantAudit` |
| TB-GAM-PLT-017 | richiesta senza `prizeCode` | 422 `PRIZE_NOT_FOUND` **AMBIGUO** | *ramo senza specifica* | `TestbookGamPrizeIT#plantWithoutPrize` |

## 7. Ciclo di vita e approvazione LEGAL (LFC, ROL, APR)

Regole R15…R20.

**Domini**: stato {7 stati di docs/03 §3.6}; azione {`SUBMIT, APPROVE, REJECT, PUBLISH, PAUSE, RESUME, END, ARCHIVE`}; ruolo {ADMIN, MARKETING, LEGAL, CARE, ANALYST, intestazione assente, ruolo inesistente}; policy {approvazione accesa (sempre per `CONTEST`), spenta}; commento del rifiuto {presente, assente, spazi}; istanti {generati, assenti}.

**Strategia**: macchina a stati **completa** stato × azione = 56 righe col ruolo abilitato (LFC-001…056); **completa** azione × ruolo = 56 righe dallo stato di partenza valido (ROL-001…056); policy spenta, istanti, commento e tracciamento: un caso per ramo (LFC-057…066, APR-001…004). Ogni riga controlla lo stato finale e il numero di fatti `contest.status.changed` (1 se la transizione riesce, 0 altrimenti).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-LFC-001 | da `DRAFT` · azione `SUBMIT` · ruolo MARKETING | 200 → `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-002 | da `DRAFT` · azione `APPROVE` · ruolo LEGAL | 409, resta `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-003 | da `DRAFT` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-004 | da `DRAFT` · azione `PUBLISH` · ruolo MARKETING | 409, resta `DRAFT` (approvazione LEGAL obbligatoria) | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-005 | da `DRAFT` · azione `PAUSE` · ruolo MARKETING | 409, resta `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-006 | da `DRAFT` · azione `RESUME` · ruolo MARKETING | 409, resta `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-007 | da `DRAFT` · azione `END` · ruolo MARKETING | 409, resta `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-008 | da `DRAFT` · azione `ARCHIVE` · ruolo MARKETING | 200 → `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-009 | da `IN_REVIEW` · azione `SUBMIT` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-010 | da `IN_REVIEW` · azione `APPROVE` · ruolo LEGAL | 200 → `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-011 | da `IN_REVIEW` · azione `REJECT` · ruolo LEGAL con commento | 200 → `DRAFT` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-012 | da `IN_REVIEW` · azione `PUBLISH` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-013 | da `IN_REVIEW` · azione `PAUSE` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-014 | da `IN_REVIEW` · azione `RESUME` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-015 | da `IN_REVIEW` · azione `END` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-016 | da `IN_REVIEW` · azione `ARCHIVE` · ruolo MARKETING | 409, resta `IN_REVIEW` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-017 | da `APPROVED` · azione `SUBMIT` · ruolo MARKETING | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-018 | da `APPROVED` · azione `APPROVE` · ruolo LEGAL | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-019 | da `APPROVED` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-020 | da `APPROVED` · azione `PUBLISH` · ruolo MARKETING | 200 → `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-021 | da `APPROVED` · azione `PAUSE` · ruolo MARKETING | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-022 | da `APPROVED` · azione `RESUME` · ruolo MARKETING | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-023 | da `APPROVED` · azione `END` · ruolo MARKETING | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-024 | da `APPROVED` · azione `ARCHIVE` · ruolo MARKETING | 409, resta `APPROVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-025 | da `LIVE` · azione `SUBMIT` · ruolo MARKETING | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-026 | da `LIVE` · azione `APPROVE` · ruolo LEGAL | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-027 | da `LIVE` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-028 | da `LIVE` · azione `PUBLISH` · ruolo MARKETING | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-029 | da `LIVE` · azione `PAUSE` · ruolo MARKETING | 200 → `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-030 | da `LIVE` · azione `RESUME` · ruolo MARKETING | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-031 | da `LIVE` · azione `END` · ruolo MARKETING | 200 → `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-032 | da `LIVE` · azione `ARCHIVE` · ruolo MARKETING | 409, resta `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-033 | da `PAUSED` · azione `SUBMIT` · ruolo MARKETING | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-034 | da `PAUSED` · azione `APPROVE` · ruolo LEGAL | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-035 | da `PAUSED` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-036 | da `PAUSED` · azione `PUBLISH` · ruolo MARKETING | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-037 | da `PAUSED` · azione `PAUSE` · ruolo MARKETING | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-038 | da `PAUSED` · azione `RESUME` · ruolo MARKETING | 200 → `LIVE` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-039 | da `PAUSED` · azione `END` · ruolo MARKETING | 200 → `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-040 | da `PAUSED` · azione `ARCHIVE` · ruolo MARKETING | 409, resta `PAUSED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-041 | da `ENDED` · azione `SUBMIT` · ruolo MARKETING | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-042 | da `ENDED` · azione `APPROVE` · ruolo LEGAL | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-043 | da `ENDED` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-044 | da `ENDED` · azione `PUBLISH` · ruolo MARKETING | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-045 | da `ENDED` · azione `PAUSE` · ruolo MARKETING | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-046 | da `ENDED` · azione `RESUME` · ruolo MARKETING | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-047 | da `ENDED` · azione `END` · ruolo MARKETING | 409, resta `ENDED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-048 | da `ENDED` · azione `ARCHIVE` · ruolo MARKETING | 200 → `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-049 | da `ARCHIVED` · azione `SUBMIT` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-050 | da `ARCHIVED` · azione `APPROVE` · ruolo LEGAL | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-051 | da `ARCHIVED` · azione `REJECT` · ruolo LEGAL con commento | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-052 | da `ARCHIVED` · azione `PUBLISH` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-053 | da `ARCHIVED` · azione `PAUSE` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-054 | da `ARCHIVED` · azione `RESUME` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-055 | da `ARCHIVED` · azione `END` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-056 | da `ARCHIVED` · azione `ARCHIVE` · ruolo MARKETING | 409, resta `ARCHIVED` | docs/03 §3.6, docs/06 §2 (409 transizione non valida), §7 | `TestbookGamLifecycleIT#statoAzione` |
| TB-GAM-LFC-057 | `PUBLISH` da `APPROVED` senza istanti | 422 `INSTANTS_NOT_GENERATED`, resta `APPROVED` | gamification §3, BO-14 | `TestbookGamLifecycleIT#publishWithoutInstants` |
| TB-GAM-LFC-058 | `APPROVED` con istanti, poi `endAt` cambiato, poi `PUBLISH` | 422 `INSTANTS_NOT_GENERATED` | gamification §3, F-IW-03 | `TestbookGamLifecycleIT#publishAfterPeriodChange` |
| TB-GAM-LFC-059 | `REJECT` senza commento | 422, resta `IN_REVIEW` | docs/03 §3.6 (commento obbligatorio) | `TestbookGamLifecycleIT#rejectWithoutComment` |
| TB-GAM-LFC-060 | `REJECT` con commento di soli spazi | 422, resta `IN_REVIEW` | docs/03 §3.6 | `TestbookGamLifecycleIT#rejectBlankComment` |
| TB-GAM-LFC-061 | `END` da `LIVE` con 1 istante riscosso e 4 aperti | 4 `VOID`, 1 `CLAIMED` | docs/03 §6, gamification §3 | `TestbookGamLifecycleIT#endVoidsOpen` |
| TB-GAM-LFC-062 | `END` da `PAUSED` con 1 istante riscosso e 4 aperti | 4 `VOID`, 1 `CLAIMED` | docs/03 §3.6, §6 | `TestbookGamLifecycleIT#endFromPausedVoidsOpen` |
| TB-GAM-LFC-063 | `SUBMIT` con commento | storico (attore, commento, da/a), fatto `contest.status.changed` DRAFT → IN_REVIEW, audit `TRANSITION` | docs/03 §3.6, docs/06 §7, gamification §4 | `TestbookGamLifecycleIT#transitionTrail` |
| TB-GAM-LFC-064 | `APPROVE` da ADMIN | audit marcato come override | docs/08 §2 (`object.approve`: ADMIN override, marcato in audit) | `TestbookGamLifecycleIT#adminOverrideAudited` |
| TB-GAM-LFC-065 | `SUBMIT`, poi `APPROVE` | in coda `GET /v1/approvals` con `entityType` CONTEST, `requiredRole` LEGAL, `submittedBy`; dopo l'approvazione non più `IN_REVIEW` | docs/06 §7, gamification §3 | `TestbookGamLifecycleIT#approvalQueue` |
| TB-GAM-LFC-066 | azione sconosciuta `LAUNCH` | 422 `INVALID_ACTION`, resta `DRAFT` | Q-282 | `TestbookGamLifecycleIT#unknownAction` |
| TB-GAM-ROL-001 | `SUBMIT` da `DRAFT` · ADMIN | 200 → `IN_REVIEW` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-002 | `SUBMIT` da `DRAFT` · MARKETING | 200 → `IN_REVIEW` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-003 | `SUBMIT` da `DRAFT` · LEGAL | 403, resta `DRAFT` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-004 | `SUBMIT` da `DRAFT` · CARE | 403, resta `DRAFT` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-005 | `SUBMIT` da `DRAFT` · ANALYST | 403, resta `DRAFT` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-006 | `SUBMIT` da `DRAFT` · senza X-LH-Actor | 403, resta `DRAFT` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-007 | `SUBMIT` da `DRAFT` · X-LH-Actor con ruolo inesistente | 403, resta `DRAFT` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-008 | `APPROVE` da `IN_REVIEW` · ADMIN | 200 → `APPROVED` (override ADMIN) | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-009 | `APPROVE` da `IN_REVIEW` · MARKETING | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-010 | `APPROVE` da `IN_REVIEW` · LEGAL | 200 → `APPROVED` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-011 | `APPROVE` da `IN_REVIEW` · CARE | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-012 | `APPROVE` da `IN_REVIEW` · ANALYST | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-013 | `APPROVE` da `IN_REVIEW` · senza X-LH-Actor | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-014 | `APPROVE` da `IN_REVIEW` · X-LH-Actor con ruolo inesistente | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-015 | `REJECT` da `IN_REVIEW` · ADMIN | 200 → `DRAFT` (override ADMIN) | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-016 | `REJECT` da `IN_REVIEW` · MARKETING | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-017 | `REJECT` da `IN_REVIEW` · LEGAL | 200 → `DRAFT` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-018 | `REJECT` da `IN_REVIEW` · CARE | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-019 | `REJECT` da `IN_REVIEW` · ANALYST | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-020 | `REJECT` da `IN_REVIEW` · senza X-LH-Actor | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-021 | `REJECT` da `IN_REVIEW` · X-LH-Actor con ruolo inesistente | 403, resta `IN_REVIEW` | docs/08 §2 (`object.approve`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-022 | `PUBLISH` da `APPROVED` · ADMIN | 200 → `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-023 | `PUBLISH` da `APPROVED` · MARKETING | 200 → `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-024 | `PUBLISH` da `APPROVED` · LEGAL | 403, resta `APPROVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-025 | `PUBLISH` da `APPROVED` · CARE | 403, resta `APPROVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-026 | `PUBLISH` da `APPROVED` · ANALYST | 403, resta `APPROVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-027 | `PUBLISH` da `APPROVED` · senza X-LH-Actor | 403, resta `APPROVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-028 | `PUBLISH` da `APPROVED` · X-LH-Actor con ruolo inesistente | 403, resta `APPROVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-029 | `PAUSE` da `LIVE` · ADMIN | 200 → `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-030 | `PAUSE` da `LIVE` · MARKETING | 200 → `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-031 | `PAUSE` da `LIVE` · LEGAL | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-032 | `PAUSE` da `LIVE` · CARE | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-033 | `PAUSE` da `LIVE` · ANALYST | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-034 | `PAUSE` da `LIVE` · senza X-LH-Actor | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-035 | `PAUSE` da `LIVE` · X-LH-Actor con ruolo inesistente | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-036 | `RESUME` da `PAUSED` · ADMIN | 200 → `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-037 | `RESUME` da `PAUSED` · MARKETING | 200 → `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-038 | `RESUME` da `PAUSED` · LEGAL | 403, resta `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-039 | `RESUME` da `PAUSED` · CARE | 403, resta `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-040 | `RESUME` da `PAUSED` · ANALYST | 403, resta `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-041 | `RESUME` da `PAUSED` · senza X-LH-Actor | 403, resta `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-042 | `RESUME` da `PAUSED` · X-LH-Actor con ruolo inesistente | 403, resta `PAUSED` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-043 | `END` da `LIVE` · ADMIN | 200 → `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-044 | `END` da `LIVE` · MARKETING | 200 → `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-045 | `END` da `LIVE` · LEGAL | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-046 | `END` da `LIVE` · CARE | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-047 | `END` da `LIVE` · ANALYST | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-048 | `END` da `LIVE` · senza X-LH-Actor | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-049 | `END` da `LIVE` · X-LH-Actor con ruolo inesistente | 403, resta `LIVE` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-050 | `ARCHIVE` da `ENDED` · ADMIN | 200 → `ARCHIVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-051 | `ARCHIVE` da `ENDED` · MARKETING | 200 → `ARCHIVED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-052 | `ARCHIVE` da `ENDED` · LEGAL | 403, resta `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-053 | `ARCHIVE` da `ENDED` · CARE | 403, resta `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-054 | `ARCHIVE` da `ENDED` · ANALYST | 403, resta `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-055 | `ARCHIVE` da `ENDED` · senza X-LH-Actor | 403, resta `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-ROL-056 | `ARCHIVE` da `ENDED` · X-LH-Actor con ruolo inesistente | 403, resta `ENDED` | docs/08 §2 (`object.edit`), docs/06 §3, §7, Q-261 | `TestbookGamLifecycleIT#ruoloAzione` |
| TB-GAM-APR-001 | policy accesa, tipo `CONTEST` | approvazione richiesta, ruolo LEGAL | docs/06 §7 | `TestbookGamApprovalPolicyTest#contestRequiresLegal` |
| TB-GAM-APR-002 | policy spenta, `PUBLISH` da `DRAFT` (MARKETING) | `LIVE` | docs/06 §7 («fino a M7 `DRAFT → LIVE` diretto») | `TestbookGamApprovalPolicyTest#disabledPublishFromDraft` |
| TB-GAM-APR-003 | policy spenta, `SUBMIT` da `DRAFT` | `LIVE` | Q-282 (docs/06 §7 cita solo `DRAFT → LIVE`) | `TestbookGamApprovalPolicyTest#disabledSubmitPublishes` |
| TB-GAM-APR-004 | `APPROVE` di ADMIN / di LEGAL | override sì / no | docs/08 §2 | `TestbookGamApprovalPolicyTest#adminOverride` |

## 8. Configurazione del concorso (EDT)

Regole R16, R21…R25.

**Domini**: codice {2 (min−1), 3 (min), 40 (max), 41 (max+1), inizia con cifra, `_`, assente, minuscolo, duplicato}; nome {spazi, assente}; meccanica {`WHEEL`, `SCRATCH`, `BOX`, `GIFT`, sconosciuta, assente}; distribuzione {assente, `BUSINESS_HOURS`, sconosciuta}; periodo {`endAt = startAt`, `startAt − 1 s`, `startAt + 1 ms`, `startAt` assente}; limite giornaliero {−1, 0, 1, assente}; seme {42, assente}; ruolo {5 ruoli + assente}; modifica: stato {`DRAFT`, `IN_REVIEW`, `LIVE`, `PAUSED`, `ENDED`, `ARCHIVED`} × campo {12 campi}. **Strategia**: creazione, ogni classe e limite da solo su un corpo valido (EDT-001…035); modifica di un oggetto `LIVE`: tutti i 12 campi (EDT-050…061), `PAUSED` 2 righe **AMBIGUO**, prima di `LIVE` un campo per tipo di effetto sugli istanti (EDT-064…072); versioni, codice e stati chiusi: un caso per ramo. Riduzione: 6 stati × 12 campi = 72 → 25 righe (le 23 della tabella più EDT-044 e EDT-045) (i campi sono indipendenti tra loro; per `DRAFT` basta un campo per effetto «istanti cancellati/conservati»; `ENDED`/`ARCHIVED` respingono qualunque campo).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-EDT-001 | codice di 2 caratteri (min-1) (`code` = `ab`); ruolo MARKETING | 422 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-002 | codice di 3 caratteri (min) (`code` = `T3X`); ruolo MARKETING | 201 in `DRAFT`, `code` = `T3X` | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-003 | codice di 40 caratteri (max) (`code` = `@40`); ruolo MARKETING | 201 in `DRAFT` | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-004 | codice di 41 caratteri (max+1) (`code` = `@41`); ruolo MARKETING | 422 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-005 | codice che inizia con una cifra (`code` = `1ABC`); ruolo MARKETING | 422 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-006 | codice con trattino basso (`code` = `IW_UNDERSCORE`); ruolo MARKETING | 422 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-007 | codice assente (`code` = assente); ruolo MARKETING | 422 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-008 | AMBIGUO codice minuscolo normalizzato (`code` = `iw-lower-case`); ruolo MARKETING | 201 in `DRAFT`, `code` = `IW-LOWER-CASE` **AMBIGUO** | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-009 | codice già usato (`code` = `@DUP`); ruolo MARKETING | 409 | docs/06 §2 (codice `^[A-Z][A-Z0-9-]{2,39}$`, 409 duplicato) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-010 | nome di soli spazi (`name` = «   »); ruolo MARKETING | 422 | gamification §2, docs/06 §2 (422) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-011 | nome assente (`name` = assente); ruolo MARKETING | 422 | gamification §2, docs/06 §2 (422) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-012 | meccanica WHEEL (`mechanic` = `WHEEL`); ruolo MARKETING | 201 in `DRAFT`, `mechanic` = `WHEEL` | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-013 | meccanica SCRATCH (`mechanic` = `SCRATCH`); ruolo MARKETING | 201 in `DRAFT`, `mechanic` = `SCRATCH` | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-014 | meccanica BOX (Q-56) (`mechanic` = `BOX`); ruolo MARKETING | 201 in `DRAFT`, `mechanic` = `BOX` | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-015 | meccanica GIFT (Q-56: in API vale BOX) (`mechanic` = `GIFT`); ruolo MARKETING | 422 | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-016 | meccanica sconosciuta SPIN (`mechanic` = `SPIN`); ruolo MARKETING | 422 | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-017 | meccanica assente (`mechanic` = assente); ruolo MARKETING | 422 — D-1 risolta (§21.5) | F-IW-01, gamification §2, Q-56 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-018 | AMBIGUO distribuzione assente (default UNIFORM) (`distribution` = assente); ruolo MARKETING | 201 in `DRAFT`, `distribution` = `UNIFORM` **AMBIGUO** | docs/03 §6, gamification §2 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-019 | distribuzione BUSINESS_HOURS (`distribution` = `BUSINESS_HOURS`); ruolo MARKETING | 201 in `DRAFT`, `distribution` = `BUSINESS_HOURS` | docs/03 §6, gamification §2 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-020 | distribuzione sconosciuta RANDOM (`distribution` = `RANDOM`); ruolo MARKETING | 422 | docs/03 §6, gamification §2 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-021 | endAt uguale a startAt (`endAt` = `=start`); ruolo MARKETING | 422 | F-IW-01, docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-022 | endAt 1 s prima di startAt (`endAt` = `start-1s`); ruolo MARKETING | 422 | F-IW-01, docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-023 | endAt 1 ms dopo startAt (`endAt` = `start+1ms`); ruolo MARKETING | 201 in `DRAFT` | F-IW-01, docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-024 | startAt assente (`startAt` = assente); ruolo MARKETING | 422 | F-IW-01, docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-025 | limite giornaliero 0 (min-1) (`maxPlaysPerMemberPerDay` = `0`); ruolo MARKETING | 422 | F-IW-01, docs/03 §6 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-026 | limite giornaliero 1 (min) (`maxPlaysPerMemberPerDay` = `1`); ruolo MARKETING | 201 in `DRAFT`, `maxPlaysPerMemberPerDay` = `1` | F-IW-01, docs/03 §6 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-027 | limite giornaliero negativo (`maxPlaysPerMemberPerDay` = `-1`); ruolo MARKETING | 422 | F-IW-01, docs/03 §6 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-028 | limite giornaliero assente (nessun tetto) (`maxPlaysPerMemberPerDay` = assente); ruolo MARKETING | 201 in `DRAFT` | F-IW-01, docs/03 §6 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-029 | seme indicato 42 (`seed` = `42`); ruolo MARKETING | 201 in `DRAFT`, `seed` = `42` | docs/03 §6, F-IW-03 (seme salvato sul concorso) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-030 | seme assente: il concorso ne salva uno (`seed` = assente); ruolo MARKETING | 201 in `DRAFT`, `seed` salvato | docs/03 §6, F-IW-03 (seme salvato sul concorso) | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-031 | ADMIN crea un concorso; ruolo ADMIN | 201 in `DRAFT` | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-032 | LEGAL non crea concorsi; ruolo LEGAL | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-033 | CARE non crea concorsi; ruolo CARE | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-034 | ANALYST non crea concorsi; ruolo ANALYST | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-035 | senza intestazione X-LH-Actor; ruolo NONE | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#creazione` |
| TB-GAM-EDT-040 | `PUT` con un codice diverso | 409 `CODE_IMMUTABLE` **AMBIGUO** | Q-51 decide l'immutabilità del codice solo per le campagne | `TestbookGamContestIT#codeImmutable` |
| TB-GAM-EDT-041 | `PUT` con lo stesso codice in minuscolo e un nome nuovo | 200, codice invariato, nome nuovo | docs/06 §2 | `TestbookGamContestIT#sameCodeOtherCase` |
| TB-GAM-EDT-042 | due `PUT` con la stessa `version` | il secondo 409 `VERSION_CONFLICT`, resta la prima modifica | Q-112 | `TestbookGamContestIT#staleVersion` |
| TB-GAM-EDT-043 | `PUT` con la `version` corrente | 200 | Q-112 | `TestbookGamContestIT#currentVersion` |
| TB-GAM-EDT-044 | `PUT` su un concorso `ENDED` | 409 `CONTEST_NOT_EDITABLE` **AMBIGUO** | docs/03 §3.6 tace su ENDED (scelta analoga a Q-51) | `TestbookGamContestIT#endedNotEditable` |
| TB-GAM-EDT-045 | `PUT` su un concorso `ARCHIVED` | 409 `CONTEST_NOT_EDITABLE` **AMBIGUO** | come sopra | `TestbookGamContestIT#archivedNotEditable` |
| TB-GAM-EDT-046 | `PUT` con LEGAL | 403 | docs/08 §2 `object.edit` | `TestbookGamContestIT#legalCannotEdit` |
| TB-GAM-EDT-050 | LIVE: cambio del nome (campo sicuro) (`PUT` del solo `name`) | 200, campo aggiornato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-051 | LIVE: cambio della descrizione (campo sicuro) (`PUT` del solo `description`) | 200, campo aggiornato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-052 | LIVE: proroga di endAt (campo sicuro per docs/03 §3.6) (`PUT` del solo `endAt`) | 200, campo aggiornato; istanti invariati — D-5 risolta (§21.5) | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-053 | LIVE: cambio del regolamento (non tra i campi sicuri) (`PUT` del solo `rulesText`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati — D-6 risolta (§21.5) | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-054 | LIVE: cambio di startAt (`PUT` del solo `startAt`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-055 | LIVE: cambio del montepremi (`PUT` del solo `prizes`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-056 | LIVE: cambio della distribuzione (`PUT` del solo `distribution`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-057 | LIVE: cambio del seme (`PUT` del solo `seed`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-058 | LIVE: cambio della meccanica (`PUT` del solo `mechanic`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-059 | LIVE: cambio della giocata gratuita giornaliera (`PUT` del solo `freePlayDaily`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-060 | LIVE: cambio del limite giornaliero (`PUT` del solo `maxPlaysPerMemberPerDay`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-061 | LIVE: cambio del limite di vincite (`PUT` del solo `maxWinsPerMember`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati | docs/03 §3.6 (campi sicuri: nome, descrizione, `endAt`), §7 (istanti immutabili dopo l'avvio), gamification §3 (premi solo prima di LIVE), docs/06 §2 (409) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-062 | AMBIGUO PAUSED: cambio del nome (`PUT` del solo `name`) | 200, campo aggiornato; istanti invariati **AMBIGUO** | AMBIGUO: docs/03 §3.6 cita solo LIVE (Q-51 decide per le campagne) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-063 | AMBIGUO PAUSED: cambio del montepremi (`PUT` del solo `prizes`) | 409 `CONTEST_LIVE_LOCKED`, concorso invariato; istanti invariati **AMBIGUO** | AMBIGUO: docs/03 §3.6 cita solo LIVE (Q-51 decide per le campagne) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-064 | DRAFT: proroga di endAt invalida gli istanti (`PUT` del solo `endAt`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-065 | DRAFT: cambio del montepremi invalida gli istanti (`PUT` del solo `prizes`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-066 | DRAFT: cambio della distribuzione invalida gli istanti (`PUT` del solo `distribution`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-067 | DRAFT: cambio del seme invalida gli istanti (`PUT` del solo `seed`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-068 | DRAFT: cambio di startAt invalida gli istanti (`PUT` del solo `startAt`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-069 | DRAFT: cambio del nome conserva gli istanti (`PUT` del solo `name`) | 200, campo aggiornato; istanti invariati | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-070 | DRAFT: cambio della meccanica conserva gli istanti (`PUT` del solo `mechanic`) | 200, campo aggiornato; istanti invariati | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-071 | DRAFT: cambio del limite giornaliero conserva gli istanti (`PUT` del solo `maxPlaysPerMemberPerDay`) | 200, campo aggiornato; istanti invariati | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-072 | IN_REVIEW: cambio del montepremi invalida gli istanti (`PUT` del solo `prizes`) | 200, campo aggiornato; istanti cancellati (`instantsGeneratedAt` null) | F-IW-03, docs/03 §6 (istanti coerenti con premi e periodo) | `TestbookGamContestIT#modifica` |
| TB-GAM-EDT-080 | *Duplica* un concorso `ENDED` con una vincita | 201 `<code>-COPY-1`, `DRAFT`, residuo = totale, nessun istante, seme diverso dall'originale | Q-113, docs/08 §3.3 (*Duplica* da ENDED) | `TestbookGamContestIT#duplicate` |
| TB-GAM-EDT-081 | seconda copia dello stesso concorso | `<code>-COPY-2` | Q-113 | `TestbookGamContestIT#duplicateTwice` |
| TB-GAM-EDT-082 | copia di un codice di 40 caratteri | codice di 40 caratteri che finisce con `-COPY-1` | Q-113 (troncamento a 40), docs/06 §2 | `TestbookGamContestIT#duplicateLongCode` |
| TB-GAM-EDT-083 | *Duplica* con LEGAL | 403 | docs/08 §2 `object.edit` | `TestbookGamContestIT#legalCannotDuplicate` |

## 9. Istanti: generatore e API (GEN, INS)

Regole R26…R28.

**Domini**: ora di Roma {07:59:59.999, 08:00:00.000, 08:00:00.001, 08:59, 09:00, 21:59:59.999, 22:00:00.000, 22:00:00.001} × {ora solare, ora legale, 29 marzo 2026, 25 ottobre 2026}; periodo {vuoto, rovesciato, 1 ms, 1 ora di notte, finestre di 2 ms sui confini, 10 giorni, giorno del cambio d'ora, 29 febbraio}; seme {uguale, diverso}; premi {quantità 0/1/n, ordine d'ingresso, `sort_order`}; stato {7}; ruolo {5 + assente + inesistente}; endpoint {tabella, istogramma}. **Strategia**: confini orari tutti (GEN-001…020, anche con un calcolo indipendente sul fuso); generatore: un caso per proprietà (GEN-021…037); API: stato × generazione completa (7) più ruoli (INS-001…012), visibilità ruolo × endpoint (INS-021…031, 7 + 4 dove l'istogramma è pubblico), filtri, giorno di Roma e paginazione (INS-032…036).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-GEN-001 | 07:59:59.999 di Roma in ora solare (min-1): `2026-01-15T06:59:59.999Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-002 | 08:00:00.000 di Roma in ora solare (min): `2026-01-15T07:00:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-003 | 08:00:00.001 di Roma in ora solare (min+1): `2026-01-15T07:00:00.001Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-004 | 08:59 di Roma in ora solare: `2026-01-15T07:59:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-005 | 09:00 di Roma in ora solare: `2026-01-15T08:00:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-006 | 21:59:59.999 di Roma in ora solare (max-1): `2026-01-15T20:59:59.999Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-007 | 22:00:00.000 di Roma in ora solare (max): `2026-01-15T21:00:00Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-008 | 22:00:00.001 di Roma in ora solare (max+1): `2026-01-15T21:00:00.001Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-009 | 07:59:59.999 di Roma in ora legale: `2026-07-15T05:59:59.999Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-010 | 08:00 di Roma in ora legale: `2026-07-15T06:00:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-011 | 21:59:59.999 di Roma in ora legale: `2026-07-15T19:59:59.999Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-012 | 22:00 di Roma in ora legale: `2026-07-15T20:00:00Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-013 | 08:00 di Roma il 29 marzo (giorno del passaggio all'ora legale): `2026-03-29T06:00:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-014 | 07:59:59 di Roma il 29 marzo: `2026-03-29T05:59:59Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-015 | 21:59:59 di Roma il 29 marzo: `2026-03-29T19:59:59Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-016 | 22:00 di Roma il 29 marzo (21:00 se si usasse ancora UTC+1): `2026-03-29T20:00:00Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-017 | 08:00 di Roma il 25 ottobre (giorno del ritorno all'ora solare): `2026-10-25T07:00:00Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-018 | 07:59:59 di Roma il 25 ottobre (08:59:59 se si usasse ancora UTC+2): `2026-10-25T06:59:59Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-019 | 22:00 di Roma il 25 ottobre: `2026-10-25T21:00:00Z` | fuori dalla fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-020 | 21:59:59 di Roma il 25 ottobre: `2026-10-25T20:59:59Z` | dentro la fascia | docs/03 §6 (`BUSINESS_HOURS`: solo 08–22 Europe/Rome), gamification §5 | `TestbookGamInstantGeneratorTest#fasciaOraria` |
| TB-GAM-GEN-021 | premi A 3, B 1, C 5 | 9 istanti: 3, 1 e 5 per premio | docs/03 §6 (un istante per unità), F-IW-03 | `TestbookGamInstantGeneratorTest#onePerUnit` |
| TB-GAM-GEN-022 | `UNIFORM`, 2 000 istanti in un'ora | tutti in [startAt, endAt) | docs/03 §6 | `TestbookGamInstantGeneratorTest#uniformWithinPeriod` |
| TB-GAM-GEN-023 | `UNIFORM`, 1 000 istanti su 10 giorni | alcuni fuori 08–22, alcuni dentro | docs/03 §6 (casuale uniforme sul periodo) | `TestbookGamInstantGeneratorTest#uniformCoversNight` |
| TB-GAM-GEN-024 | `BUSINESS_HOURS`, 1 000 istanti dal 25 marzo al 4 aprile 2026 | tutti tra 08:00 e 22:00 di Roma, anche il 29 marzo | docs/03 §6 | `TestbookGamInstantGeneratorTest#businessHoursMarchDst` |
| TB-GAM-GEN-025 | `BUSINESS_HOURS`, 500 istanti nel solo 25 ottobre 2026 | tutti quel giorno tra 08:00 e 22:00 di Roma | docs/03 §6 | `TestbookGamInstantGeneratorTest#businessHoursOctoberDst` |
| TB-GAM-GEN-026 | seme 42 due volte, stessi premi e periodo | elenchi identici | gamification §5, §7 | `TestbookGamInstantGeneratorTest#sameSeedSameInstants` |
| TB-GAM-GEN-027 | semi 42 e 43 | elenchi diversi | docs/03 §6 (il seme determina gli istanti) | `TestbookGamInstantGeneratorTest#otherSeedOtherInstants` |
| TB-GAM-GEN-028 | stessi premi elencati in ordine diverso | elenchi identici | gamification §5 (in ordine di `sort_order`) | `TestbookGamInstantGeneratorTest#inputOrderIrrelevant` |
| TB-GAM-GEN-029 | premio Z (`sort_order` 9) elencato prima di A (`sort_order` 1) | prima i 3 istanti di A, poi i 2 di Z | gamification §5 | `TestbookGamInstantGeneratorTest#sortOrderFirst` |
| TB-GAM-GEN-030 | `endAt = startAt` | errore (periodo vuoto) | docs/03 §6 [startAt, endAt) | `TestbookGamInstantGeneratorTest#emptyPeriod` |
| TB-GAM-GEN-031 | `endAt` prima di `startAt` | errore | docs/03 §6 | `TestbookGamInstantGeneratorTest#reversedPeriod` |
| TB-GAM-GEN-032 | periodo di 1 ms, 3 istanti | tutti a `startAt` | docs/03 §6 (estremo incluso) | `TestbookGamInstantGeneratorTest#oneMillisecond` |
| TB-GAM-GEN-033 | `BUSINESS_HOURS` su 01:00–02:00 di Roma | errore di validazione (Q-294 DECISA: 422 `CONTEST_INVALID` nel servizio, INS-037) | Q-294 | `TestbookGamInstantGeneratorTest#noBusinessHours` |
| TB-GAM-GEN-034 | `BUSINESS_HOURS` su [07:59:59.999, 08:00:00.001) di Roma | tutti alle 08:00:00.000 | docs/03 §6 (08 incluso) | `TestbookGamInstantGeneratorTest#openingBoundary` |
| TB-GAM-GEN-035 | `BUSINESS_HOURS` su [21:59:59.999, 22:00:00.001) di Roma | tutti alle 21:59:59.999 | docs/03 §6 (22 escluso) | `TestbookGamInstantGeneratorTest#closingBoundary` |
| TB-GAM-GEN-036 | premio con quantità 0 | nessun istante | docs/03 §6 | `TestbookGamInstantGeneratorTest#zeroQuantity` |
| TB-GAM-GEN-037 | `BUSINESS_HOURS` sul 29 febbraio 2028 | tutti il 29 tra 08:00 e 22:00 di Roma | docs/03 §6 | `TestbookGamInstantGeneratorTest#leapDay` |
| TB-GAM-INS-001 | genera in DRAFT (stato DRAFT, ruolo MARKETING) | 200: 9 istanti nuovi (3+1+5) tutti in [startAt, endAt), quantità ripristinate | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-002 | genera in IN_REVIEW (stato IN_REVIEW, ruolo MARKETING) | 200: 9 istanti nuovi (3+1+5) tutti in [startAt, endAt), quantità ripristinate | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-003 | genera in APPROVED (stato APPROVED, ruolo MARKETING) | 200: 9 istanti nuovi (3+1+5) tutti in [startAt, endAt), quantità ripristinate | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-004 | rigenera in LIVE (stato LIVE, ruolo MARKETING) | 409, istanti invariati | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-005 | rigenera in PAUSED (stato PAUSED, ruolo MARKETING) | 409, istanti invariati | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-006 | rigenera in ENDED (stato ENDED, ruolo MARKETING) | 409, istanti invariati | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-007 | rigenera in ARCHIVED (stato ARCHIVED, ruolo MARKETING) | 409, istanti invariati | docs/03 §6 (rigenerare solo in DRAFT/IN_REVIEW/APPROVED), gamification §3 (409 da LIVE in poi) | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-008 | ADMIN genera in DRAFT (stato DRAFT, ruolo ADMIN) | 200: 9 istanti nuovi (3+1+5) tutti in [startAt, endAt), quantità ripristinate | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-009 | LEGAL non genera (stato DRAFT, ruolo LEGAL) | 403, istanti invariati | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-010 | CARE non genera (stato DRAFT, ruolo CARE) | 403, istanti invariati | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-011 | ANALYST non genera (stato DRAFT, ruolo ANALYST) | 403, istanti invariati | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-012 | senza intestazione X-LH-Actor (stato DRAFT, ruolo NONE) | 403, istanti invariati | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamContestIT#generazione` |
| TB-GAM-INS-013 | copia di `IW-AUTUNNO`, seme 42 due volte | 355 istanti, elenchi identici | gamification §7 | `TestbookGamContestIT#seedTwiceSameInstants` |
| TB-GAM-INS-014 | istanti riscossi e piantati, quantità a 1, poi rigenerazione | 5 istanti tutti `OPEN`, nessuno piantato, residuo 5 | gamification §3 («rigenera da zero») | `TestbookGamContestIT#regenerateFromScratch` |
| TB-GAM-INS-015 | generazione con seme 4242 | seme salvato sul concorso | docs/03 §6 (seme salvato) | `TestbookGamContestIT#seedStored` |
| TB-GAM-INS-016 | generazione con seme 77, poi senza seme | stesso seme e stessi istanti | gamification §3 (`{seed?}`), docs/03 §6 | `TestbookGamContestIT#seedOmittedReproducible` |
| TB-GAM-INS-017 | concorso senza premi | 422 `CONTEST_INVALID` (Q-294 DECISA) | Q-294 | `TestbookGamContestIT#generateWithoutPrizes` |
| TB-GAM-INS-018 | `BUSINESS_HOURS` dal 24 al 27 ottobre 2026, 300 istanti (dal DB) | tutti tra 08:00 e 22:00 di Roma, alcuni il 25 | docs/03 §6 | `TestbookGamContestIT#businessHoursInDb` |
| TB-GAM-INS-019 | generazione con seme 9 | voce di audit con 5 istanti e seme 9 | gamification §4 (audit della generazione) | `TestbookGamContestIT#generationAudit` |
| TB-GAM-INS-020 | premi da 3, 1 e 5 pezzi (dal DB) | 3, 1 e 5 istanti per premio | F-IW-03 | `TestbookGamContestIT#onePerUnit` |
| TB-GAM-INS-021 | tabella degli istanti con ADMIN | 200, 5 istanti paginati | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-022 | tabella degli istanti con LEGAL | 200, 5 istanti paginati | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-023 | tabella degli istanti con MARKETING | 403 | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-024 | tabella degli istanti con CARE | 403 | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-025 | tabella degli istanti con ANALYST | 403 | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-026 | tabella degli istanti senza X-LH-Actor | 403 | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-027 | tabella degli istanti con ruolo inesistente | 403 | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-028 | istogramma con MARKETING | 200, conteggi per giorno senza gli orari | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-029 | istogramma con CARE | 403 `FORBIDDEN_ROLE` (Q-303 DECISA: istogramma solo ad ADMIN, MARKETING e LEGAL) | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-030 | istogramma con ANALYST | 403 `FORBIDDEN_ROLE` (Q-303 DECISA: istogramma solo ad ADMIN, MARKETING e LEGAL) | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-031 | istogramma senza X-LH-Actor | 403 `FORBIDDEN_ROLE` (Q-303 DECISA: istogramma solo ad ADMIN, MARKETING e LEGAL) | gamification §3 (istanti solo ADMIN/LEGAL, istogramma visibile a MARKETING), docs/08 §2 `instants.view` e «tutte le personas leggono tutto», F-IW-07 | `TestbookGamContestIT#visibilita` |
| TB-GAM-INS-032 | filtro `status=CLAIMED` (LEGAL) dopo una vincita | solo l'istante riscosso | gamification §3 (filtri `status, prizeId`) | `TestbookGamContestIT#filterByStatus` |
| TB-GAM-INS-033 | filtro `prizeId` (ADMIN) | solo gli istanti di quel premio | gamification §3 | `TestbookGamContestIT#filterByPrize` |
| TB-GAM-INS-034 | istanti alle 23:59:59 e alle 00:00:00 di Roma | due giorni nell'istogramma (1 e 4) | gamification §3 (conteggio per giorno), convenzioni Europe/Rome | `TestbookGamContestIT#histogramRomeDays` |
| TB-GAM-INS-035 | istanti il 29 febbraio 2028 | un giorno `2028-02-29` con 5 aperti | gamification §3 | `TestbookGamContestIT#histogramLeapDay` |
| TB-GAM-INS-036 | 150 istanti, `size=500` | pagina da 100, `totalItems` 150 | docs/06 §2 (`size` massimo 100) | `TestbookGamContestIT#pageSizeCapped` |
| TB-GAM-INS-037 | `BUSINESS_HOURS` su 01:00–02:00 di Roma, generazione via API | 422 `CONTEST_INVALID` (Q-294 DECISA; prima 500) | Q-294 | `TestbookGamContestIT#generateWithoutBusinessHours` |

## 10. Vincitori e statistiche (RPT)

Regole R29, R30. Una vincita `PHYSICAL` e una perdita in un concorso di 3 pezzi.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-RPT-001 | elenco vincitori | 1 riga: membro, nickname, premio `PHY-W`, tipo, consegna `PENDING` | gamification §3, F-IW-07, BO-14 | `TestbookGamContestIT#winners` |
| TB-GAM-RPT-002 | export CSV (LEGAL) | intestazione e la stessa riga | gamification §3 (`winners.csv`), F-IW-07 | `TestbookGamContestIT#winnersCsv` |
| TB-GAM-RPT-003 | statistiche | giocate 2, vincite 1, tasso 50 %, totale 3, residui 2, serie di un giorno (2/1) | gamification §3 (`stats`), BO-14 | `TestbookGamContestIT#stats` |
| TB-GAM-RPT-004 | elenco dei concorsi | giocate 2, vincite 1, residui 2 | BO-14 (elenco) | `TestbookGamContestIT#listCounters` |
| TB-GAM-RPT-005 | `GET /v1/members/{memberId}/gamification` (CARE) | 404: endpoint assente, la scheda di BO-03 degrada con lo stato vuoto | Q-167 (gamification §3 lo prevede: regola non implementata registrata) | `TestbookGamContestIT#memberSummary` |

## 11. Fine concorso (END)

Regole R20, R47. Il job si lancia con `POST /v1/demo/jobs/close-contests?asOf=` (ADMIN) con date del 2020, lontane da ogni altro concorso.

**Domini**: `endAt − asOf` {+1 ms, 0, −1 ms, −1 g}; stato {`LIVE`, `PAUSED`, `APPROVED`, `DRAFT`}; `asOf` {istante, data}; ruolo {ADMIN, MARKETING}. **Strategia**: confini su `LIVE` e ogni altro stato da solo (END-001…006), effetti e formato di `asOf` (END-007, END-008), ruolo (END-009).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-END-001 | LIVE con endAt 1 ms dopo asOf | resta/diventa `LIVE` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-002 | LIVE con endAt uguale ad asOf | resta/diventa `ENDED` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-003 | LIVE con endAt 1 ms prima di asOf | resta/diventa `ENDED` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-004 | PAUSED con endAt passato da un giorno | resta/diventa `PAUSED` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-005 | APPROVED con endAt passato da un giorno | resta/diventa `APPROVED` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-006 | DRAFT con endAt passato da un giorno | resta/diventa `DRAFT` | gamification §5 («LIVE con end_at passato → ENDED»), docs/03 §6 [startAt, endAt) | `TestbookGamContestIT#fineConcorso` |
| TB-GAM-END-007 | `LIVE` scaduto con 1 istante riscosso e 4 aperti | `ENDED`; 4 `VOID`, 1 `CLAIMED`; fatto `LIVE → ENDED`; audit `JOB` con `voided` 4 | docs/03 §6, gamification §4, §5 | `TestbookGamContestIT#closeEffects` |
| TB-GAM-END-008 | `asOf=2020-02-10`: concorsi che finiscono alle 23:59:59 e alle 00:00 di Roma | il primo `ENDED`, il secondo resta `LIVE` (Q-294 DECISA, come Q-156 per il wallet) | Q-294; Q-156 | `TestbookGamContestIT#asOfDate` |
| TB-GAM-END-009 | job lanciato da MARKETING | 403 | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) | `TestbookGamContestIT#closeRole` |

## 12. Obiettivi: regole pure (PER, STK, MET, FLT)

Regole R33, R34, R35.

**Domini**: periodo {`NONE`, `DAY`, `WEEK`, `MONTH`, `EDITION`, `EVER`} × istanti {mezzanotte di Roma d'estate e d'inverno, 29 febbraio, notte del cambio d'ora, fine anno}; serie {stesso giorno, giorno dopo, buco, mezzanotte di Roma contro UTC, cambi d'ora, fine mese, anni bisestili e no, fine anno, settimane ISO 53/01, domenica/lunedì, fuori ordine}; metrica {`COUNT`, `SUM` (intero, decimale, 0, negativo, assente, testo, annidato, molto grande), `DISTINCT_TYPES`}; filtro: ogni comparatore di docs/03 §3.3 (`eq neq gt gte lt lte in nin contains ncontains exists nexists between startsWith`) più uno sconosciuto, ai confini (49.99/50/50.01), campo assente, tipi incompatibili, gruppi `all/any/not`, annidati, `[*]`. **Strategia**: un caso per confine e per valore dell'enumerato (nessuna combinazione: le regole sono indipendenti).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-PER-001 | obiettivo MONTH: 31 agosto 23:59:59 di Roma: `MONTH` a `2026-08-31T21:59:59Z` | `2026-08` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-002 | obiettivo MONTH: 1 settembre 00:00 di Roma (ancora 31 agosto in UTC): `MONTH` a `2026-08-31T22:00:00Z` | `2026-09` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-003 | obiettivo MONTH: 31 gennaio 23:59:59 di Roma (ora solare): `MONTH` a `2026-01-31T22:59:59Z` | `2026-01` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-004 | obiettivo MONTH: 1 febbraio 00:00 di Roma: `MONTH` a `2026-01-31T23:00:00Z` | `2026-02` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-005 | obiettivo MONTH: 29 febbraio 2028: `MONTH` a `2028-02-29T12:00:00Z` | `2028-02` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-006 | obiettivo MONTH: 1 marzo 2028 00:00 di Roma (29 febbraio in UTC): `MONTH` a `2028-02-29T23:00:00Z` | `2028-03` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-007 | obiettivo MONTH: notte del passaggio all'ora legale: `MONTH` a `2026-03-29T00:30:00Z` | `2026-03` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-008 | obiettivo MONTH: 31 ottobre 23:59:59 di Roma dopo il ritorno all'ora solare: `MONTH` a `2026-10-31T22:59:59Z` | `2026-10` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-009 | obiettivo MONTH: 1 novembre 00:00 di Roma: `MONTH` a `2026-10-31T23:00:00Z` | `2026-11` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-010 | obiettivo EDITION: 31 dicembre 23:59:59 di Roma (Q-59): `EDITION` a `2026-12-31T22:59:59Z` | `ED-2026` | docs/03 §8, convenzioni (Europe/Rome), Q-59 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-011 | obiettivo EDITION: 1 gennaio 00:00 di Roma (Q-59): `EDITION` a `2026-12-31T23:00:00Z` | `ED-2027` | docs/03 §8, convenzioni (Europe/Rome), Q-59 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-012 | obiettivo EVER: `EVER` a `2026-09-24T10:00:00Z` | `EVER` | docs/03 §8, convenzioni (Europe/Rome) | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-013 | classifica MONTH: 31 agosto 23:59:59 di Roma: `MONTH` a `2026-08-31T21:59:59Z` | `2026-08` | docs/03 §8, convenzioni (Europe/Rome), gamification §2 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-014 | classifica MONTH: 1 settembre 00:00 di Roma: `MONTH` a `2026-08-31T22:00:00Z` | `2026-09` | docs/03 §8, convenzioni (Europe/Rome), gamification §2 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-015 | classifica EDITION: 1 gennaio 00:00 di Roma (Q-59): `EDITION` a `2026-12-31T23:00:00Z` | `ED-2027` | docs/03 §8, convenzioni (Europe/Rome), Q-59, gamification §2 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-016 | classifica ALL_TIME: `ALL_TIME` a `2026-09-24T10:00:00Z` | `ALL` | docs/03 §8, convenzioni (Europe/Rome), gamification §2 | `TestbookGamAchievementRulesTest#chiaveDiPeriodo` |
| TB-GAM-PER-017 | periodo DAY non ammesso (Q-159): chiave unica anche per giorni diversi: `2026-09-24T10:00:00Z` e `2026-09-25T10:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-018 | periodo DAY: stesso giorno di Roma stessa chiave: `2026-09-24T08:00:00Z` e `2026-09-24T20:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-019 | periodo WEEK non ammesso (Q-159): chiave unica anche per settimane diverse: `2026-09-21T10:00:00Z` e `2026-09-28T10:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-020 | periodo WEEK: lunedì e domenica della stessa settimana: `2026-09-21T10:00:00Z` e `2026-09-27T10:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-021 | periodo NONE: una sola chiave per sempre: `2020-01-01T10:00:00Z` e `2030-01-01T10:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-022 | periodo DAY non ammesso (Q-159): 23:59:59 e 00:00 di Roma stessa chiave: `2026-01-10T22:59:59Z` e `2026-01-10T23:00:00Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-PER-023 | periodo MONTH: 1 e 30 settembre stessa chiave: `2026-08-31T22:00:00Z` e `2026-09-30T21:59:59Z` | chiavi uguali | docs/03 §8, Q-159 (periodi ammessi `EVER/MONTH/EDITION`; `EVER` = `NONE`) | `TestbookGamAchievementRulesTest#finestraDiPeriodo` |
| TB-GAM-STK-001 | DAY: prima azione (DAY: `2026-09-01T10:00:00Z`) | valori 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-002 | DAY: due azioni nello stesso giorno (DAY: `2026-09-01T10:00:00Z`, `2026-09-01T18:00:00Z`) | valori 1 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-003 | DAY: giorni consecutivi (DAY: `2026-09-01T10:00:00Z`, `2026-09-02T10:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-004 | DAY: giorni 1 2 4 (un buco azzera) (DAY: `2026-09-01T10:00:00Z`, `2026-09-02T10:00:00Z`, `2026-09-04T10:00:00Z`) | valori 1 → 2 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-005 | DAY: 23:59:59 e 00:00 di Roma nello stesso giorno UTC sono consecutivi (DAY: `2026-01-10T22:59:59Z`, `2026-01-10T23:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-006 | DAY: 00:30 e 22:00 di Roma in giorni UTC diversi sono lo stesso giorno (DAY: `2026-01-10T23:30:00Z`, `2026-01-11T21:00:00Z`) | valori 1 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-007 | DAY: attraverso il passaggio all'ora legale (DAY: `2026-03-28T12:00:00Z`, `2026-03-29T12:00:00Z`, `2026-03-30T12:00:00Z`) | valori 1 → 2 → 3 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-008 | DAY: attraverso il ritorno all'ora solare (DAY: `2026-10-24T12:00:00Z`, `2026-10-25T12:00:00Z`, `2026-10-26T12:00:00Z`) | valori 1 → 2 → 3 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-009 | DAY: 31 gennaio e 1 febbraio (DAY: `2026-01-31T12:00:00Z`, `2026-02-01T12:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-010 | DAY: 28 febbraio e 1 marzo in anno non bisestile (DAY: `2027-02-28T12:00:00Z`, `2027-03-01T12:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-011 | DAY: 28 e 29 febbraio 2028 (DAY: `2028-02-28T12:00:00Z`, `2028-02-29T12:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-012 | DAY: 28 febbraio e 1 marzo 2028 (manca il 29) (DAY: `2028-02-28T12:00:00Z`, `2028-03-01T12:00:00Z`) | valori 1 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-013 | DAY: 31 dicembre e 1 gennaio (DAY: `2026-12-31T12:00:00Z`, `2027-01-01T12:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-014 | WEEK: lunedì e domenica della stessa settimana (WEEK: `2026-09-21T10:00:00Z`, `2026-09-27T10:00:00Z`) | valori 1 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-015 | WEEK: settimane consecutive (WEEK: `2026-09-21T10:00:00Z`, `2026-09-28T10:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-016 | WEEK: una settimana saltata (WEEK: `2026-09-21T10:00:00Z`, `2026-10-05T10:00:00Z`) | valori 1 → 1 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-017 | WEEK: 2026-W53 e 2027-W01 consecutive (WEEK: `2026-12-31T12:00:00Z`, `2027-01-04T12:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-018 | WEEK: domenica 23:59:59 e lunedì 00:00 di Roma (WEEK: `2026-09-27T21:59:59Z`, `2026-09-27T22:00:00Z`) | valori 1 → 2 | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-STK-019 | AMBIGUO DAY: azione più vecchia arrivata dopo (DAY: `2026-09-02T10:00:00Z`, `2026-09-01T10:00:00Z`) | valori 1 → 1 **AMBIGUO** | docs/03 §8 (`STREAK`: unità consecutive, un buco azzera), gamification §5 (`last_unit_key`) | `TestbookGamAchievementRulesTest#serie` |
| TB-GAM-MET-001 | COUNT: +1 per azione (COUNT: A, A, A) | valori 1 → 2 → 3 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-002 | AMBIGUO SUM: 45.90 troncato a 45 (SUM, campo `data.amount`: A=45.90) | valori 45 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-003 | AMBIGUO SUM: 45.90 e 54.10 danno 99 (SUM, campo `data.amount`: A=45.90, A=54.10) | valori 45 → 99 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-004 | SUM: importo 0 (SUM, campo `data.amount`: A=0) | valori 0 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-005 | AMBIGUO SUM: importo negativo ignorato (SUM, campo `data.amount`: A=-10) | valori 0 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-006 | SUM: campo assente (SUM, campo `data.amount`: A=miss) | valori 0 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-007 | AMBIGUO SUM: campo testuale ignorato (SUM, campo `data.amount`: A=s50) | valori 0 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-008 | AMBIGUO SUM: campo nella forma di docs/10 (tipo.data.campo) (SUM, campo `purchase.completed.data.amount`: A=30) | valori 30 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-009 | SUM: campo annidato data.order.total (SUM, campo `data.order.total`: A=n12) | valori 12 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-010 | DISTINCT_TYPES: A B A C (DISTINCT_TYPES: A, B, A, C) | valori 1 → 2 → 2 → 3 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-011 | DISTINCT_TYPES: tipo ripetuto (DISTINCT_TYPES: A, A) | valori 1 → 1 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-012 | SUM: importo molto grande (10^12) (SUM, campo `data.amount`: A=1000000000000) | valori 1000000000000 | docs/03 §8 (metriche), F-ACH-01 | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-MET-013 | AMBIGUO SUM: 0.99 troncato a 0 (SUM, campo `data.amount`: A=0.99) | valori 0 **AMBIGUO** | docs/03 §8 (metriche), F-ACH-01 — troncamento/segno/tipo del campo non specificati | `TestbookGamAchievementRulesTest#metrica` |
| TB-GAM-FLT-001 | nessun filtro: filtro `-` su `{"amount":1}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-002 | filtro senza regole: filtro `{"op":"all"}` su `{"amount":1}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-003 | eq numerico uguale: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"eq","value":50}]}` su `{"amount":50}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-004 | eq numerico diverso: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"eq","value":50}]}` su `{"amount":51}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-005 | eq testuale uguale: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"eq","value":"APP"}]}` su `{"channel":"APP"}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-006 | neq su valore diverso: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"neq","value":"APP"}]}` su `{"channel":"WEB"}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-007 | neq su valore uguale: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"neq","value":"APP"}]}` su `{"channel":"APP"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-008 | gt 50 con 50: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gt","value":50}]}` su `{"amount":50}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-009 | gt 50 con 50.01: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gt","value":50}]}` su `{"amount":50.01}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-010 | gte 50 con 49.99 (min-1): filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}` su `{"amount":49.99}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-011 | gte 50 con 50 (min): filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}` su `{"amount":50}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-012 | gte 50 con 50.01 (min+1): filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}` su `{"amount":50.01}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-013 | lt 50 con 49.99: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"lt","value":50}]}` su `{"amount":49.99}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-014 | lt 50 con 50: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"lt","value":50}]}` su `{"amount":50}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-015 | lte 50 con 50: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"lte","value":50}]}` su `{"amount":50}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-016 | lte 50 con 50.01: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"lte","value":50}]}` su `{"amount":50.01}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-017 | in con valore presente: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"in","value":["APP","WEB"]}]}` su `{"channel":"APP"}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-018 | in con valore assente: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"in","value":["APP","WEB"]}]}` su `{"channel":"POS"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-019 | nin con valore fuori elenco: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"nin","value":["APP"]}]}` su `{"channel":"POS"}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-020 | contains su un elenco che contiene il valore: filtro `{"op":"all","rules":[{"field":"data.tags","cmp":"contains","value":"a"}]}` su `{"tags":["a","b"]}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-021 | ncontains su un testo che non contiene il valore: filtro `{"op":"all","rules":[{"field":"data.code","cmp":"ncontains","value":"z"}]}` su `{"code":"abc"}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-022 | exists su campo presente: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"exists"}]}` su `{"amount":50}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-023 | exists su campo assente: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"exists"}]}` su `{"channel":"APP"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-024 | nexists su campo assente: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"nexists"}]}` su `{"channel":"APP"}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-025 | nexists su campo presente: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"nexists"}]}` su `{"amount":50}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-026 | between 10 e 100 con 50: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"between","value":[10,100]}]}` su `{"amount":50}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-027 | startsWith AP su APP: filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"startsWith","value":"AP"}]}` su `{"channel":"APP"}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-028 | campo assente con eq: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"eq","value":50}]}` su `{"channel":"APP"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-029 | campo assente con neq (docs/03 §3.3: foglia falsa): filtro `{"op":"all","rules":[{"field":"data.channel","cmp":"neq","value":"APP"}]}` su `{"amount":5}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-030 | tipi incompatibili: gte 50 su testo: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}` su `{"amount":"abc"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-031 | all con una regola falsa: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50},{"field":"data.channel","cmp":"eq","value":"APP"}]}` su `{"amount":60,"channel":"WEB"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-032 | all con tutte le regole vere: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50},{"field":"data.channel","cmp":"eq","value":"APP"}]}` su `{"amount":60,"channel":"APP"}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-033 | any con una regola vera: filtro `{"op":"any","rules":[{"field":"data.amount","cmp":"gte","value":50},{"field":"data.channel","cmp":"eq","value":"APP"}]}` su `{"amount":10,"channel":"APP"}` | vero (conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-034 | any con tutte le regole false: filtro `{"op":"any","rules":[{"field":"data.amount","cmp":"gte","value":50},{"field":"data.channel","cmp":"eq","value":"APP"}]}` su `{"amount":10,"channel":"WEB"}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-035 | not su una regola falsa: filtro `{"op":"not","rules":[{"field":"data.amount","cmp":"gte","value":100}]}` su `{"amount":50}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-036 | campo su elenco items[*].category (almeno un elemento): filtro `{"op":"all","rules":[{"field":"data.items[*].category","cmp":"eq","value":"food"}]}` su `{"items":[{"category":"tech"},{"category":"food"}]}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-037 | AMBIGUO comparatore sconosciuto foo tra numeri uguali: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"foo","value":50}]}` su `{"amount":50}` | vero (conta) **AMBIGUO** | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-038 | gruppo annidato any dentro all: filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":10},{"op":"any","rules":[{"field":"data.channel","cmp":"eq","value":"APP"},{"field":"data.channel","cmp":"eq","value":"WEB"}]}]}` su `{"amount":20,"channel":"WEB"}` | vero (conta) — D-7 risolta (§21.5) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |
| TB-GAM-FLT-039 | between 10 e 100 con 5 (fuori): filtro `{"op":"all","rules":[{"field":"data.amount","cmp":"between","value":[10,100]}]}` su `{"amount":5}` | falso (non conta) | docs/03 §8 (filtro = condizioni su `data.*`), §3.3 (comparatori, gruppi, campo assente, tipi incompatibili) | `TestbookGamAchievementRulesTest#filtro` |

## 13. Obiettivi: progresso (PRG)

Regole R01, R33…R38, R40. Le azioni entrano da `AchievementService.onAction` come le consegna il listener di `lh.actions.v1`; i fatti si leggono dall'outbox. Ogni `achievement.progressed` deve avere `target` = traguardo e 0 ≤ `value` ≤ traguardo (contratto).

**Domini**: metrica × traguardo {traguardo−1, traguardo, traguardo+1}; periodo {`EVER`, `MONTH`} × ripetibile {sì, no} × mesi {uno, due}; stato del membro {5}; stato dell'obiettivo {`ACTIVE`, `INACTIVE`}; tipo d'azione {elencato, no}; confini di mese (estate, inverno); tempo di business dell'azione contro ora di elaborazione. **Strategia**: traguardo ai confini per `COUNT` (PRG-001…003, 029), ripetibilità × periodo completa (2 × 2, PRG-003…006), confini di mese (PRG-007…009), stato del membro e dell'obiettivo da soli (PRG-010…015), una riga per comportamento di ogni metrica (PRG-016…026), filtro e tempo di business (PRG-027, 028); badge e azioni interne (PRG-030…034).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-PRG-001 | COUNT 3: due azioni (traguardo-1) — COUNT traguardo 3, periodo EVER, non ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 0 `achievement.completed`; valore 2 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-002 | COUNT 3: tre azioni (traguardo) — COUNT traguardo 3, periodo EVER, non ripetibile, membro ACTIVE; azioni 3 | 3 `achievement.progressed`, 1 `achievement.completed`; valore 3 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-003 | COUNT 3 non ripetibile: la quarta azione non riemette — COUNT traguardo 3, periodo EVER, non ripetibile, membro ACTIVE; azioni 4 | 3 `achievement.progressed`, 1 `achievement.completed`; valore 3 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-004 | COUNT 3 MONTH ripetibile: quattro azioni nello stesso mese — COUNT traguardo 3, periodo MONTH, ripetibile, membro ACTIVE; azioni 4 | 3 `achievement.progressed`, 1 `achievement.completed`; valore 3 nel periodo `2026-09` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-005 | COUNT 3 MONTH ripetibile: completato a settembre e di nuovo a ottobre — COUNT traguardo 3, periodo MONTH, ripetibile, membro ACTIVE; azioni 6 | 6 `achievement.progressed`, 2 `achievement.completed`; valore 3 nel periodo `2026-10` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-006 | COUNT 3 MONTH non ripetibile: completato a settembre e mai più — COUNT traguardo 3, periodo MONTH, non ripetibile, membro ACTIVE; azioni 6 | 3 `achievement.progressed`, 1 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-007 | COUNT 2 MONTH: 31 agosto 23:59:59 e 1 settembre 00:00 di Roma in due mesi — COUNT traguardo 2, periodo MONTH, ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 0 `achievement.completed`; valore 1 nel periodo `2026-09` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-008 | COUNT 2 MONTH: 1 settembre 00:00 e 30 settembre 23:59:59 di Roma nello stesso mese — COUNT traguardo 2, periodo MONTH, ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 1 `achievement.completed`; valore 2 nel periodo `2026-09` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-009 | COUNT 2 MONTH: 31 gennaio 23:59:59 e 1 febbraio 00:00 di Roma (ora solare) — COUNT traguardo 2, periodo MONTH, ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 0 `achievement.completed`; valore 1 nel periodo `2026-02` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-010 | membro INACTIVE: azioni ignorate — COUNT traguardo 1, periodo EVER, non ripetibile, membro INACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-011 | membro BLOCKED: azioni ignorate — COUNT traguardo 1, periodo EVER, non ripetibile, membro BLOCKED; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-012 | membro ANONYMIZED: azioni ignorate — COUNT traguardo 1, periodo EVER, non ripetibile, membro ANONYMIZED; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-013 | AMBIGUO membro senza snapshot: la sua azione conta — COUNT traguardo 1, periodo EVER, non ripetibile, membro UNKNOWN; azioni 1 | 1 `achievement.progressed`, 1 `achievement.completed`; valore 1 nel periodo `EVER` **AMBIGUO** | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-014 | tipo di azione non osservato — COUNT traguardo 1, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-015 | obiettivo INACTIVE — COUNT traguardo 1, periodo EVER, non ripetibile, obiettivo INACTIVE, membro ACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; nessun progresso | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-016 | DISTINCT_TYPES 2: A A B — DISTINCT_TYPES traguardo 2, periodo EVER, non ripetibile, membro ACTIVE; azioni 3 | 2 `achievement.progressed`, 1 `achievement.completed`; valore 2 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-017 | DISTINCT_TYPES 2: A A (tipo ripetuto senza fatto) — DISTINCT_TYPES traguardo 2, periodo EVER, non ripetibile, membro ACTIVE; azioni 2 | 1 `achievement.progressed`, 0 `achievement.completed`; valore 1 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-018 | AMBIGUO SUM 100: 45.90 e 54.10 troncati (45 + 54) — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 0 `achievement.completed`; valore 99 nel periodo `EVER` **AMBIGUO** | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-019 | SUM 100: 60 e 40 (traguardo esatto) — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 1 `achievement.completed`; valore 100 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-020 | SUM: importo 0 non cambia il valore — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; valore 0 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-021 | AMBIGUO SUM: importo negativo ignorato — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; valore 0 nel periodo `EVER` **AMBIGUO** | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-022 | SUM: campo assente — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 0 `achievement.progressed`, 0 `achievement.completed`; valore 0 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-023 | SUM 100: 101 in una sola azione (oltre il traguardo) — SUM traguardo 100, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 1 `achievement.progressed`, 1 `achievement.completed`; valore 101 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-024 | STREAK DAY 3: giorni 1 2 e 4 (il buco azzera) — STREAK traguardo 3, periodo EVER, non ripetibile, membro ACTIVE; azioni 3 | 3 `achievement.progressed`, 0 `achievement.completed`; valore 1 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-025 | STREAK DAY 3: giorni 1 1 2 3 — STREAK traguardo 3, periodo EVER, non ripetibile, membro ACTIVE; azioni 4 | 3 `achievement.progressed`, 1 `achievement.completed`; valore 3 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-026 | STREAK WEEK 2: due settimane ISO consecutive — STREAK traguardo 2, periodo EVER, non ripetibile, membro ACTIVE; azioni 2 | 2 `achievement.progressed`, 1 `achievement.completed`; valore 2 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-027 | filtro data.amount gte 50: 49.99 no e 50 sì — COUNT traguardo 1, periodo EVER, non ripetibile, membro ACTIVE, con filtro; azioni 2 | 1 `achievement.progressed`, 1 `achievement.completed`; valore 1 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-028 | periodo dal tempo della azione (agosto) elaborata a settembre — COUNT traguardo 5, periodo MONTH, ripetibile, membro ACTIVE; azioni 1 | 1 `achievement.progressed`, 0 `achievement.completed`; valore 1 nel periodo `2026-08` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-029 | COUNT 1 (traguardo minimo): una azione completa — COUNT traguardo 1, periodo EVER, non ripetibile, membro ACTIVE; azioni 1 | 1 `achievement.progressed`, 1 `achievement.completed`; valore 1 nel periodo `EVER` | docs/03 §2, §8, gamification §5, §7, F-ACH-01, F-ACH-02, contratto `achievement.progressed` | `TestbookGamAchievementIT#progresso` |
| TB-GAM-PRG-030 | obiettivo COUNT 1 con badge collegato, completato | `badge.awarded` {`badgeCode`, `badgeName`, `origin` ACHIEVEMENT}; badge al membro | docs/03 §8, F-ACH-03, contratto `fact.badge.awarded` | `TestbookGamAchievementIT#completionAwardsBadge` |
| TB-GAM-PRG-031 | badge già ricevuto da un effetto, poi obiettivo completato | `achievement.completed` sì, un solo `badge.awarded` in totale | gamification §2 (PK `member_id, badge_code`), F-ACH-03 | `TestbookGamAchievementIT#badgeAlreadyOwned` |
| TB-GAM-PRG-032 | portale a settembre: COUNT 3 MONTH con 2 azioni, COUNT 1 EVER completato | 2/3, `pct` 66–67, `periodKey` `2026-09`; completato con `pct` 100 e data | gamification §3 (portale obiettivi), PT-09 | `TestbookGamAchievementIT#portalView` |
| TB-GAM-PRG-033 | obiettivo che elenca l'azione interna `achievement.completed` (con filtro sul codice), due azioni | completato | gamification §5 (le interne contano se elencate) | `TestbookGamAchievementIT#internalActionListed` |
| TB-GAM-PRG-034 | obiettivo che non elenca `achievement.completed`, arriva quell'azione | nessun progresso | gamification §5 (niente cicli impliciti) | `TestbookGamAchievementIT#internalActionNotListed` |

## 14. Obiettivi: configurazione (ACF)

Regola R39 (e R34 per i periodi, R16 per i ruoli).

**Domini**: metrica {4 + sconosciuta + assente}; traguardo {−1, 0, 1}; `SUM` con/senza campo; `STREAK` con unità {assente, `DAY`, `WEEK`, `MONTH`}; `DISTINCT_TYPES` traguardo {= tipi, > tipi}; tipi {vuoti}; badge {esistente, inesistente}; periodo {`NONE`, `DAY`, `WEEK`, `MONTH`, `EDITION`, `EVER`, sconosciuto}; stato {sconosciuto}; codice {senza prefisso, duplicato}; nome {spazi}; ruolo {5 + assente}. **Strategia**: ogni classe da sola su un corpo valido (COUNT 3 EVER).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-ACF-001 | metrica sconosciuta FOO (`metric=FOO`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-002 | metrica assente (`metric=~`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` — D-3 risolta (§21.5) | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-003 | traguardo 0 (min-1) (`target=0`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-004 | traguardo 1 (min) (`target=1`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-005 | traguardo negativo (`target=-1`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-006 | SUM senza campo da sommare (`metric=SUM`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-007 | SUM con campo data.amount (`metric=SUM;sumField=data.amount`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-008 | STREAK senza unità (`metric=STREAK`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-009 | STREAK con unità MONTH (`metric=STREAK;streakUnit=MONTH`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-010 | STREAK con unità DAY (`metric=STREAK;streakUnit=DAY`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-011 | STREAK con unità WEEK (`metric=STREAK;streakUnit=WEEK`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-012 | AMBIGUO DISTINCT_TYPES con traguardo oltre i tipi osservati (`metric=DISTINCT_TYPES;types=tb.acf.a\|tb.acf.b;target=3`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` **AMBIGUO** | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-013 | DISTINCT_TYPES con traguardo pari ai tipi osservati (`metric=DISTINCT_TYPES;types=tb.acf.a\|tb.acf.b;target=2`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-014 | nessun tipo di azione (`types=`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-015 | badge collegato inesistente (`badgeCode=BDG-NON-ESISTE`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-016 | badge collegato esistente (`badgeCode=BDG-FIRST`; ruolo MARKETING) | 201 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-017 | periodo DAY non ammesso (Q-159) (`period=DAY`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-018 | periodo WEEK non ammesso (Q-159) (`period=WEEK`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-019 | periodo NONE non ammesso: vale EVER (Q-159) (`period=NONE`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-020 | periodo MONTH (`period=MONTH`; ruolo MARKETING) | 201 | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-021 | periodo EDITION (`period=EDITION`; ruolo MARKETING) | 201 | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-022 | periodo EVER (docs/08 BO-15 e docs/10) (`period=EVER`; ruolo MARKETING) | 201 | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-023 | periodo sconosciuto YEAR (`period=YEAR`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | docs/03 §8 (periodi), docs/08 BO-15, Q-159 (restano `EVER/MONTH/EDITION`) | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-024 | stato PAUSED fuori enumerato (`status=PAUSED`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-025 | AMBIGUO codice senza prefisso ACH- (`code=OBJ-TESTBOOK`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` **AMBIGUO** | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-026 | codice già usato (`code=@DUP`; ruolo MARKETING) | 409 | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-027 | nome di soli spazi (`name=`; ruolo MARKETING) | 422 `ACHIEVEMENT_INVALID` | F-ACH-01, BO-15, gamification §2, docs/06 §2 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-028 | ADMIN crea un obiettivo (corpo valido; ruolo ADMIN) | 201 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-029 | LEGAL non crea obiettivi (corpo valido; ruolo LEGAL) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-030 | CARE non crea obiettivi (corpo valido; ruolo CARE) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-031 | ANALYST non crea obiettivi (corpo valido; ruolo ANALYST) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-032 | senza intestazione X-LH-Actor (corpo valido; ruolo NONE) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamAchievementIT#configurazione` |
| TB-GAM-ACF-033 | `PUT` con un codice diverso | 409 `CODE_IMMUTABLE` **AMBIGUO** | *ramo senza specifica* | `TestbookGamAchievementIT#achievementCodeImmutable` |
| TB-GAM-ACF-034 | `PUT` del traguardo a 5 | 200, traguardo 5 | F-ACH-01, BO-15 | `TestbookGamAchievementIT#achievementUpdateTarget` |
| TB-GAM-ACF-035 | `PUT` di un obiettivo inesistente | 404 | docs/06 §2 | `TestbookGamAchievementIT#achievementUnknown` |

## 15. Badge (BDG)

Regola R40.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-BDG-001 | effetto `badge.award` | badge al membro con origine CAMPAIGN; `badge.awarded` {origin CAMPAIGN, `badgeName`} | F-ACH-03, docs/03 §3.4 (`AWARD_BADGE`), contratto | `TestbookGamAchievementIT#badgeFromEffect` |
| TB-GAM-BDG-002 | stesso badge da un secondo effetto | nessun secondo badge né fatto | gamification §2 (PK `member_id, badge_code`) | `TestbookGamAchievementIT#badgeOnce` |
| TB-GAM-BDG-003 | stesso effetto consegnato due volte | nulla di nuovo | gamification §2 (`effect_id` univoco), RNF-03 | `TestbookGamAchievementIT#badgeEffectReplay` |
| TB-GAM-BDG-004 | badge inesistente nell'effetto | errore non ritentabile `BADGE_NOT_FOUND` (DLQ); nessun fatto | campaign-service §5 («l'errore emergerà a valle in DLQ») | `TestbookGamAchievementIT#badgeUnknown` |
| TB-GAM-BDG-005 | effetto senza `data` | errore non ritentabile `INVALID_EFFECT` (Q-297 DECISA) | Q-297 | `TestbookGamAchievementIT#badgeWithoutData` |
| TB-GAM-BDG-006 | effetto senza membro nel subject | errore non ritentabile `INVALID_EFFECT` (Q-297 DECISA) | Q-297 | `TestbookGamAchievementIT#badgeWithoutMember` |
| TB-GAM-BDG-007 | portale badge di un membro con un badge | prima il badge ottenuto con data e origine; poi quelli da ottenere senza data e con indicazione | gamification §3 («ottenuti + da ottenere»), PT-09 | `TestbookGamAchievementIT#portalBadges` |
| TB-GAM-BDG-008 | badge con nome di soli spazi | 422 `BADGE_INVALID` | gamification §2 (`name`), docs/06 §2 | `TestbookGamAchievementIT#badgeWithoutName` |
| TB-GAM-BDG-009 | codice badge già usato | 409 | docs/06 §2 | `TestbookGamAchievementIT#badgeDuplicate` |
| TB-GAM-BDG-010 | `PUT` di un badge inesistente | 404 | docs/06 §2 | `TestbookGamAchievementIT#badgeUpdateUnknown` |
| TB-GAM-BDG-011 | LEGAL crea un badge | 403 | docs/08 §2 `object.edit` | `TestbookGamAchievementIT#badgeLegal` |
| TB-GAM-BDG-012 | MARKETING crea un badge | 201; in elenco con 0 membri | BO-15, docs/08 §2 | `TestbookGamAchievementIT#badgeCreate` |
| TB-GAM-BDG-013 | codice senza prefisso `BDG-` | 422 `BADGE_INVALID` **AMBIGUO** | *ramo senza specifica* (docs/06 §2 fissa solo `^[A-Z][A-Z0-9-]{2,39}$`) | `TestbookGamAchievementIT#badgeCodeFormat` |
| TB-GAM-BDG-014 | badge assegnato a due membri | `holders` 2 | BO-15 («quanti membri lo hanno») | `TestbookGamAchievementIT#badgeHolders` |

## 16. Classifiche (LDB)

Regole R41, R42 (Q-59, Q-60). I fatti entrano da `LeaderboardService` con l'ora di elaborazione fissata (serve al parimerito); LDB-024 entra dal listener di `lh.facts.v1` (deserializzazione, router, idempotenza). Nessun caso pubblica sul broker: nella stessa JVM i contesti Spring in cache degli altri `*IT` condividono il gruppo `lh-gamification` e possono prendersi la partizione.

**Domini**: valuta {PTS, STS} × metrica {`PTS_EARNED`, `STS_EARNED`, `ACTION_COUNT`}; importo {−10, 0, 100, somma}; periodo {`MONTH` al confine di mese, `EDITION` al confine d'anno, `ALL_TIME`}; stato del membro {5}; parimerito {raggiunto prima, raggiunto con un secondo accredito}; posizione del membro {in top N, fuori, assente}; stato della classifica {`INACTIVE`}. **Strategia**: valuta × metrica nelle combinazioni che cambiano l'esito (LDB-001, 002, 006, 007), importi ai confini (003…005), periodi ai confini (008…010), ogni stato del membro da solo (014…018), parimerito (012, 013), portale (020…022).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-LDB-001 | `wallet.points.earned` PTS 100 | +100 in `PTS_EARNED` | gamification §5 (importo effettivo), F-LDB-01 | `TestbookGamLeaderboardIT#ptsEarned` |
| TB-GAM-LDB-002 | `wallet.points.earned` STS 50 | +50 in `STS_EARNED`; niente in `PTS_EARNED` | gamification §2, §5 | `TestbookGamLeaderboardIT#stsEarned` |
| TB-GAM-LDB-003 | importo 0 | nessun punteggio | gamification §5 | `TestbookGamLeaderboardIT#zeroAmount` |
| TB-GAM-LDB-004 | importo −10 | nessun punteggio | gamification §5 (punti guadagnati) | `TestbookGamLeaderboardIT#negativeAmount` |
| TB-GAM-LDB-005 | 100 e 60 nello stesso mese | 160 | docs/03 §8 (punteggio aggiornato dai fatti) | `TestbookGamLeaderboardIT#sumOfAmounts` |
| TB-GAM-LDB-006 | `ACTION_COUNT`, azione elencata | +1 | F-LDB-01, gamification §4 | `TestbookGamLeaderboardIT#actionListed` |
| TB-GAM-LDB-007 | `ACTION_COUNT`, azione non elencata | nessun punteggio | gamification §2 (`action_types`) | `TestbookGamLeaderboardIT#actionNotListed` |
| TB-GAM-LDB-008 | `MONTH`: 31 agosto 23:59:59 e 1 settembre 00:00 di Roma | 10 in `2026-08`, 20 in `2026-09` | docs/03 §8, convenzioni Europe/Rome, docs/03 §3.1 (tempo di business) | `TestbookGamLeaderboardIT#monthBoundary` |
| TB-GAM-LDB-009 | `EDITION`: 31 dicembre 23:59:59 e 1 gennaio 00:00 di Roma | `ED-2026` e `ED-2027` | Q-59 | `TestbookGamLeaderboardIT#editionBoundary` |
| TB-GAM-LDB-010 | `ALL_TIME`: accrediti del 2025 e del 2027 | 30 in un solo periodo | gamification §2 | `TestbookGamLeaderboardIT#allTime` |
| TB-GAM-LDB-011 | tre membri 50, 150, 100 | ordine 150, 100, 50; primo rank 1 | F-LDB-01 | `TestbookGamLeaderboardIT#orderByScore` |
| TB-GAM-LDB-012 | due membri a 100, B prima di A | B primo | docs/03 §8 (parimerito → chi ha raggiunto prima) | `TestbookGamLeaderboardIT#tieEarlierFirst` |
| TB-GAM-LDB-013 | A 50, B 100, poi A +50 | B primo (A ha raggiunto 100 dopo) | docs/03 §8 | `TestbookGamLeaderboardIT#tieLastIncrement` |
| TB-GAM-LDB-014 | membro BLOCKED con 1 000 punti | escluso; l'ACTIVE è primo | gamification §5, Q-60 | `TestbookGamLeaderboardIT#blockedExcluded` |
| TB-GAM-LDB-015 | membro INACTIVE | escluso | gamification §5 | `TestbookGamLeaderboardIT#inactiveExcluded` |
| TB-GAM-LDB-016 | membro ANONYMIZED | escluso | gamification §5 | `TestbookGamLeaderboardIT#anonymizedExcluded` |
| TB-GAM-LDB-017 | membro senza snapshot | escluso | gamification §5 (solo ACTIVE) | `TestbookGamLeaderboardIT#unknownExcluded` |
| TB-GAM-LDB-018 | membro BLOCKED con 300 punti torna ACTIVE | rientra con 300 | gamification §5 | `TestbookGamLeaderboardIT#backToActive` |
| TB-GAM-LDB-019 | top N 3 e 4 membri | 3 righe nel ranking di gestione | F-LDB-01 (top N) | `TestbookGamLeaderboardIT#topN` |
| TB-GAM-LDB-020 | portale del secondo classificato | `top` con nickname e `isMe`; `me` {rank 2, score 100}; nessun `memberId` | PT-10 («solo nickname»), gamification §3, F-LDB-01 | `TestbookGamLeaderboardIT#portalView` |
| TB-GAM-LDB-021 | portale del quarto con top N 3 | `top` di 3; `me.rank` 4 | PT-10 (riga del membro sempre visibile) | `TestbookGamLeaderboardIT#portalOutsideTop` |
| TB-GAM-LDB-022 | portale di un membro senza punteggio | `me` assente | gamification §3 | `TestbookGamLeaderboardIT#portalNotRanked` |
| TB-GAM-LDB-023 | classifica INACTIVE | nessun punteggio | gamification §2 (`status`) | `TestbookGamLeaderboardIT#inactiveBoard` |
| TB-GAM-LDB-024 | `wallet.points.earned` 75 consegnato due volte al listener di `lh.facts.v1` (record Kafka) | 75 in `ALL` una sola volta | gamification §4 (consuma `lh.facts.v1`), RNF-03 | `TestbookGamLeaderboardIT#throughListener` |

## 17. Classifiche: configurazione (LCF)

Regola R43 (R16 per i ruoli). **Strategia**: ogni classe da sola su un corpo valido (`PTS_EARNED`, `MONTH`, top N 10); top N ai limiti del codice (3, 50) **AMBIGUO** perché la specifica non li fissa.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-LCF-001 | AMBIGUO top N 2 (min-1) (`topN=2`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` **AMBIGUO** | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-002 | top N 3 (min) (`topN=3`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-003 | top N 50 (max) (`topN=50`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-004 | AMBIGUO top N 51 (max+1) (`topN=51`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` **AMBIGUO** | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-005 | ACTION_COUNT senza tipi di azione (`metric=ACTION_COUNT`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-006 | ACTION_COUNT con un tipo di azione (`metric=ACTION_COUNT;types=tb.lcf.a`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-007 | metrica sconosciuta FOO (`metric=FOO`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-008 | periodo WEEK fuori enumerato (`period=WEEK`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-009 | periodo ALL_TIME (`period=ALL_TIME`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-010 | periodo EDITION (`period=EDITION`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-011 | metrica STS_EARNED (`metric=STS_EARNED`; ruolo MARKETING) | 201 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-012 | stato PAUSED fuori enumerato (`status=PAUSED`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-013 | nome di soli spazi (`name=`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-014 | codice già usato (`code=@DUP`; ruolo MARKETING) | 409 | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-015 | AMBIGUO codice senza prefisso LDB- (`code=CLASSIFICA-TB`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` **AMBIGUO** | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-016 | LEGAL non crea classifiche (corpo valido; ruolo LEGAL) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-017 | CARE non crea classifiche (corpo valido; ruolo CARE) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-018 | ANALYST non crea classifiche (corpo valido; ruolo ANALYST) | 403 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-019 | ADMIN crea una classifica (corpo valido; ruolo ADMIN) | 201 | docs/08 §2 `object.edit`, docs/06 §3 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-024 | metrica assente (`metric=~`; ruolo MARKETING) | 422 `LEADERBOARD_INVALID` — D-4 risolta (§21.5) | F-LDB-01, BO-16, gamification §2 (metriche e periodi), docs/06 §2 | `TestbookGamLeaderboardIT#configurazione` |
| TB-GAM-LCF-020 | `PUT` con metrica diversa | 409 `LEADERBOARD_LOCKED` **AMBIGUO** | *ramo senza specifica* | `TestbookGamLeaderboardIT#metricLocked` |
| TB-GAM-LCF-021 | `PUT` con periodo diverso | 409 `LEADERBOARD_LOCKED` **AMBIGUO** | *ramo senza specifica* | `TestbookGamLeaderboardIT#periodLocked` |
| TB-GAM-LCF-022 | `PUT` del top N a 5 | 200, top N 5 | F-LDB-01, BO-16 | `TestbookGamLeaderboardIT#topNUpdate` |
| TB-GAM-LCF-023 | `PUT` con un codice diverso | 409 `CODE_IMMUTABLE` **AMBIGUO** | *ramo senza specifica* | `TestbookGamLeaderboardIT#boardCodeImmutable` |

## 18. Crediti da effetto (GRT)

Regola R31.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-GRT-001 | `plays.grant` count 2 | 2 crediti nel portale; `contest.plays.granted` {`contestCode`, `count` 2, `effectId`} | F-IW-05, gamification §4, contratto `fact.contest.plays.granted` | `TestbookGamEffectIT#grantTwo` |
| TB-GAM-GRT-002 | stesso `effectId` consegnato due volte | 3 crediti, un solo fatto | gamification §2 (`effect_id` UQ), RNF-03 | `TestbookGamEffectIT#grantIdempotent` |
| TB-GAM-GRT-003 | due effetti da 3 e 2 | 5 crediti | docs/03 §6 (Σ `play_grant.count`) | `TestbookGamEffectIT#grantsSum` |
| TB-GAM-GRT-004 | `count` assente | 1 credito (Q-297 DECISA, come Q-230) | docs/03 §3.4; Q-230; Q-297 | `TestbookGamEffectIT#grantWithoutCount` |
| TB-GAM-GRT-005 | `count` 0 o negativo | errore non ritentabile `INVALID_EFFECT` (DLQ), nessun credito (Q-297 DECISA) | Q-297 | `TestbookGamEffectIT#grantZero` |
| TB-GAM-GRT-006 | concorso inesistente | errore non ritentabile `CONTEST_NOT_FOUND` (DLQ); nessun credito | campaign-service §5 (errore a valle in DLQ) | `TestbookGamEffectIT#grantUnknownContest` |
| TB-GAM-GRT-007 | effetto senza `data` | errore non ritentabile `INVALID_EFFECT` (Q-297 DECISA) | Q-297 | `TestbookGamEffectIT#grantWithoutData` |
| TB-GAM-GRT-008 | effetto senza membro nel subject | errore non ritentabile `INVALID_EFFECT` (Q-297 DECISA) | Q-297 | `TestbookGamEffectIT#grantWithoutMember` |
| TB-GAM-GRT-009 | credito su un concorso `DRAFT`, poi `LIVE` senza gratuita | il credito resta e la giocata è `CREDIT` (Q-297 DECISA) | Q-297 | `TestbookGamEffectIT#grantBeforeLive` |
| TB-GAM-GRT-010 | `plays.grant` consegnato due volte al listener di `lh.effects.v1` (record Kafka) | 2 crediti nel portale, un solo `contest.plays.granted` | gamification §4 (consuma `lh.effects.v1`), RNF-03 | `TestbookGamEffectIT#grantThroughListener` |
| TB-GAM-GRT-011 | `contestCode` valorizzato con l'id del concorso | `CONTEST_NOT_FOUND` | docs/03 §3.4 (`GRANT_PLAYS.contestCode`) | `TestbookGamEffectIT#grantWithId` |

## 19. Snapshot del membro e nickname (SNP, NCK)

Regole R01, R32.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-SNP-001 | `member.registered` ACTIVE di un membro nuovo | può giocare (200) | gamification §4, docs/03 §2 | `TestbookGamEffectIT#registeredCanPlay` |
| TB-GAM-SNP-002 | `member.status.changed` ACTIVE → BLOCKED | 422 `MEMBER_NOT_ACTIVE` | gamification §3, §4, docs/03 §2 | `TestbookGamEffectIT#blockedCannotPlay` |
| TB-GAM-SNP-003 | BLOCKED → ACTIVE | può giocare | docs/03 §2 | `TestbookGamEffectIT#unblockedCanPlay` |
| TB-GAM-SNP-004 | `member.registered` con nome Giulia, cognome Rossi, senza nickname | nickname «Giulia R.» | docs/03 §8 | `TestbookGamEffectIT#defaultNickname` |
| TB-GAM-SNP-005 | `member.registered` con stato BLOCKED | 422 `MEMBER_NOT_ACTIVE` | docs/03 §2, contratto `member.registered` | `TestbookGamEffectIT#registeredBlocked` |
| TB-GAM-NCK-001 | nickname presente: `{"nickname":"Giuly","firstName":"Giulia","lastName":"Rossi"}` | «Giuly» | docs/03 §8 (default: nome + iniziale del cognome), F-LDB-01 | `TestbookGamNicknameTest#nickname` |
| TB-GAM-NCK-002 | nickname assente: nome e iniziale del cognome: `{"firstName":"Giulia","lastName":"Rossi"}` | «Giulia R.» | docs/03 §8 (default: nome + iniziale del cognome), F-LDB-01 | `TestbookGamNicknameTest#nickname` |
| TB-GAM-NCK-003 | nickname di soli spazi: nome e iniziale del cognome: `{"nickname":"  ","firstName":"Giulia","lastName":"Rossi"}` | «Giulia R.» | docs/03 §8 (default: nome + iniziale del cognome), F-LDB-01 | `TestbookGamNicknameTest#nickname` |
| TB-GAM-NCK-004 | cognome vuoto: solo il nome: `{"firstName":"Giulia","lastName":""}` | «Giulia» | docs/03 §8 (default: nome + iniziale del cognome), F-LDB-01 | `TestbookGamNicknameTest#nickname` |
| TB-GAM-NCK-005 | Q-297 nome assente: nessun nickname: `{"lastName":"Rossi"}` | nessun nickname (il portale mostra il segnaposto; Q-297 DECISA) | docs/03 §8 (default: nome + iniziale del cognome), F-LDB-01 | `TestbookGamNicknameTest#nickname` |

## 20. Referral (REF)

Il referral è di **member-service** (gamification §1, docs/02 F-REF-01/02 «MBR+CMP»): il legame alla registrazione (codice valido, un solo invitante, non se stessi; Q-61 per il codice di un membro non attivo) e il completamento alla prima azione qualificante con due fatti `referral.completed` (`REFERRER`, `REFEREE`) non hanno codice in gamification e non sono eseguibili con i test di questo modulo (vincolo del testbook: soli file nuovi in `services/gamification-service/src/test`). Quelle foglie (docs/17 §5 MBR-05, MBR-06, MBR-09) restano da coprire con una classe `Testbook*` in member-service (proposta in §21). Qui si prova la parte di gioco: `referral.completed` rientra come azione interna (bridge) e conta per obiettivi e classifiche solo se elencato (R44).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GAM-REF-001 | obiettivo COUNT 1 che elenca `referral.completed`; azione con `role` REFERRER | completato | docs/03 §8, gamification §5 | `TestbookGamAchievementIT#referralCountsWhenListed` |
| TB-GAM-REF-002 | come sopra con filtro `data.role eq REFERRER`; azioni dell'invitato e del presentatore | completato solo per il presentatore | docs/03 §8 (due fatti con ruoli opposti), §3.3 | `TestbookGamAchievementIT#referralRoleFilter` |
| TB-GAM-REF-003 | obiettivo che non elenca `referral.completed` | nessun progresso | gamification §5 (azioni interne solo se elencate) | `TestbookGamAchievementIT#referralNotListed` |
| TB-GAM-REF-004 | classifica `ACTION_COUNT` su `referral.completed`; un'azione per ruolo | +1 al presentatore e +1 all'invitato | F-LDB-01, docs/03 §8 | `TestbookGamAchievementIT#referralLeaderboard` |

## 21. Copertura

### 21.1 Conteggi

| Voce | Valore |
|---|---|
| Regole della specifica inventariate (§1.1) | 47 (R01…R47); con righe in questo testbook: 45 (R45 referral di member-service e R46 pulizia dei progressi senza righe, vedi §21.4) |
| Rami del codice mappati (§1.2) | 78 nodi, di cui 19 con almeno un *ramo senza specifica*; i 5 in contrasto con la specifica (⚠) sono stati corretti (§21.5) |
| Righe del testbook | 710 |
| Righe **AMBIGUO** (comportamento attuale, domanda registrata: Q-289…Q-297 di docs/15) | 56 |
| Righe in **DIVERGENZA** | 16 alla prima esecuzione, 0 aperte: tutte risolte correggendo il codice (§21.5) |
| Casi JUnit eseguiti | 710 (una riga = un caso) |

| Area | Righe | Area | Righe | Area | Righe |
|---|---|---|---|---|---|
| PLY | 59 | CRD | 46 | PTL | 7 |
| CLM | 14 | PRZ | 35 | PLT | 17 |
| LFC | 66 | ROL | 56 | APR | 4 |
| EDT | 69 | GEN | 37 | INS | 36 |
| RPT | 5 | END | 9 | PER | 23 |
| STK | 19 | MET | 13 | FLT | 39 |
| PRG | 34 | ACF | 35 | BDG | 14 |
| LDB | 24 | LCF | 24 | GRT | 11 |
| SNP | 5 | NCK | 5 | REF | 4 |

### 21.2 Combinazioni ridotte

| Decisione | Prodotto dei domini | Strategia | Righe |
|---|---|---|---|
| Giocata: stato × periodo | 7 × 6 = 42 | tabella completa | PLY-001…042 |
| Giocata: + membro, vincite, istante maturo | 7 × 6 × 5 × 5 × 2 = 2 100 | stato × periodo completi con classi valide; ogni classe non valida del membro da sola; `maxWinsPerMember` × istante maturo completo (6); 3 coppie di condizioni false per l'ordine | 59 |
| Crediti: gratuita × crediti × tetto | 3 × 3 × 4 = 36 | tabella completa | CRD-001…036 |
| Giorno di Roma | — | un confine per riga (mezzanotte, cambi d'ora, 29 febbraio, fine mese) | CRD-037…046 |
| Ciclo di vita: stato × azione | 7 × 8 = 56 | macchina a stati completa | LFC-001…056 |
| Ciclo di vita: azione × ruolo | 8 × 7 = 56 | tabella completa | ROL-001…056 |
| Consegna: giocata × ruolo × stato | 5 × 6 × 5 = 150 | guasto singolo dal caso valido | 16 |
| Modifica del concorso: stato × campo | 6 × 12 = 72 | LIVE completo (12), PAUSED 2, DRAFT/IN_REVIEW un campo per effetto sugli istanti (9), ENDED/ARCHIVED 1 ciascuno | 25 |
| Creazione del concorso, del premio, dell'obiettivo, della classifica | somma delle classi | ogni classe non valida e ogni limite da solo su un corpo valido | EDT-001…035, PRZ-004…019, ACF-001…032, LCF-001…019, 024 |
| Generazione × stato × ruolo | 7 + 5 | stati completi, ruoli da DRAFT | INS-001…012 |
| Visibilità: ruolo × endpoint | 7 × 2 = 14 | completa per la tabella (7), 4 ruoli per l'istogramma pubblico (i restanti 3 equivalenti) | INS-021…031 |
| Obiettivi: ripetibile × periodo × mesi | 2 × 2 × 2 = 8 | le 4 combinazioni con esito diverso | PRG-003…006 |
| Filtro: comparatori × esito | 15 comparatori × vero/falso | ogni comparatore almeno una volta, i numerici ai confini, campo assente per `eq/neq/exists/nexists` | FLT-001…039 |

### 21.3 Rami senza specifica

- `PlayService.play` :88–90 → memberId assente o vuoto → 422 `MEMBER_REQUIRED`
- `PortalContestsController.live` :59–67 → solo `LIVE` giocabili; membro non attivo → crediti 0
- `ContestAdminService.create` :112 → distribuzione assente → `UNIFORM`
- `ContestAdminService.update` :132–134 → codice diverso → 409 `CODE_IMMUTABLE`
- `ContestAdminService.update` :135–137 → `ENDED`/`ARCHIVED` → 409 `CONTEST_NOT_EDITABLE`
- `GamificationDemoController.asOf` :67–75 → `asOf` data pura → fine di quel giorno a Roma
- `ContestAdminService.plantInstant` :305–308 → concorso non `LIVE` → 409 `CONTEST_NOT_LIVE`
- `ContestAdminService.plantInstant` :310–312 → premio non del concorso → 422 `PRIZE_NOT_FOUND`
- `ContestAdminService.plantInstant` :313–316, `InstantRepository.plantLast` :175–189 → ultimo `OPEN` del premio (preferendo i non piantati) a `min(now−1 s, più vecchio aperto − 1 s)`; nessuno → 422 `NO_OPEN_INSTANT`
- `ContestAdminService.generateInstants` :336–338 → nessun premio → 422 `CONTEST_INVALID`
- `InstantGenerator.next` :54–62 → `BUSINESS_HOURS`: rigetta e ricampiona; nessun orario valido in 10 000 tentativi → errore
- `ContestAdminService.updateDelivery` :363–373 → 404; non `WIN` o non `PHYSICAL` → 409 `DELIVERY_NOT_APPLICABLE`; stato ∉ {PENDING, DELIVERED} → 422; nota vuota → null; audit
- `PlaysGrantHandler.handle` :50–73 → senza dati/membro → DLQ `INVALID_EFFECT`; concorso sconosciuto → DLQ `CONTEST_NOT_FOUND`; `count` < 1 o assente → 1; `effectId` già visto → nulla
- `MemberSnapshotHandler.nickname` :53–63 → nickname, altrimenti nome + iniziale; nome assente → null
- `AchievementService.onAction` :53–55 → subject senza membro o tipo non azione → ignorata
- `AchievementService.onAction` :56–59 → snapshot non `ACTIVE` → ignorata; **snapshot assente → conta**
- `AchievementRules.advance` :78–103 → `COUNT` +1; `SUM` troncato, ≤ 0 ignorato; `DISTINCT_TYPES`; `STREAK` stessa unità / unità successiva / buco
- `AchievementRules.field` :110–114 → `tipo.data.campo` (forma di docs/10) → `campo`
- `AchievementAdminService.saveBadge` :78–97 → codice `BDG-…`, nome obbligatorio, duplicato 409, 404

Ognuno ha una riga **AMBIGUO** (salvo i due rami d'infrastruttura senza riga: subject non membro e tipo non azione in `AchievementService.onAction`).

### 21.4 Regole non implementate

- **R30** — riepilogo per la Scheda 360° `GET /v1/members/{memberId}/gamification` (gamification §3): nessun endpoint; la mancanza è registrata in Q-167 (la scheda di BO-03 degrada). Riga TB-GAM-RPT-005 (verde, asserisce il 404 di Q-167).
- **R34** — periodi `DAY`, `WEEK` (e il nome `NONE`) di docs/03 §8: non implementati per scelta registrata (Q-159: restano `EVER/MONTH/EDITION`). Righe TB-GAM-ACF-017…019, TB-GAM-PER-017, 019, 022 (verdi sulla scelta di Q-159).
- ~~**R35**~~ — grammatica delle condizioni di docs/03 §3.3 nel filtro degli obiettivi: **implementata** (D-7 risolta, §21.5). Righe TB-GAM-FLT-019…022, 024, 026, 027, 035, 036, 038 verdi; il campo assente con `neq` (TB-GAM-FLT-029) era già stato corretto su main (6b1b964).
- **R46** — pulizia di `achievement_progress` dei periodi chiusi da oltre 90 giorni (gamification §5): nessun codice e nessun punto d'osservazione (job non esposto): **nessuna riga**; da coprire quando il job esisterà.
- **R45** — referral: implementato in member-service, fuori da questo modulo (§20, §21.7).

### 21.5 Registro delle divergenze

| # | Riga | Specifica | Comportamento osservato | Causa (file:riga) | Esito |
|---|---|---|---|---|---|
| 1 | TB-GAM-EDT-017 | docs/06 §2: regola violata → 422 `CONTEST_INVALID` (meccanica obbligatoria, gamification §2) | 500 `INTERNAL_ERROR` con `mechanic` assente | `ContestAdminService.java:418` — `Contest.MECHANICS` è `List.of(…)`: `contains(null)` lancia `NullPointerException` | D-1 risolta: `mechanic`/`distribution` `null` → 422 `CONTEST_INVALID` (`ContestAdminService.validate` :429–430) |
| 2 | TB-GAM-PRZ-013 | docs/06 §2, gamification §2 (`type` del premio) → 422 | 500 con `type` del premio assente | `ContestAdminService.java:426` — `Prize.TYPES.contains(null)` | D-2 risolta: `type` `null` → 422 `CONTEST_INVALID` (`ContestAdminService.validate` :437) |
| 3 | TB-GAM-ACF-002 | docs/06 §2, F-ACH-01 (metrica obbligatoria) → 422 `ACHIEVEMENT_INVALID` | 500 con `metric` assente | `AchievementAdminService.java:113` — `Achievement.METRICS.contains(null)` | D-3 risolta: `metric`/`period` `null` → 422 `ACHIEVEMENT_INVALID` (`AchievementAdminService.build` :113–114) |
| 4 | TB-GAM-LCF-024 | docs/06 §2, F-LDB-01 → 422 `LEADERBOARD_INVALID` | 500 con `metric` assente | `LeaderboardService.java:131` — `Leaderboard.METRICS.contains(null)` | D-4 risolta: `metric`/`period` `null` → 422 `LEADERBOARD_INVALID` (`LeaderboardService.build` :131–132) |
| 5 | TB-GAM-EDT-052 | docs/03 §3.6: un oggetto `LIVE` si modifica nei campi sicuri «nome, descrizione, `endAt`, priorità, immagine» | 409 `CONTEST_LIVE_LOCKED` alla proroga di `endAt` | `ContestAdminService.java:149–151, 155–158` — `endAt` conta tra i campi che toccano gli istanti e blocca il LIVE | D-5 risolta: su `LIVE`/`PAUSED` `endAt` è un campo sicuro, gli istanti restano (`ContestAdminService.update` :151–169); editor BO-14 allineato (`ContestSetupForm.tsx`, `LIVE_FIELDS`) |
| 6 | TB-GAM-EDT-053 | docs/03 §3.6: fuori dai campi sicuri serve duplicare; il regolamento non è tra i campi sicuri | 200, regolamento cambiato su un concorso `LIVE` | `ContestAdminService.java:152–155` — `rulesText` escluso dal controllo (javadoc :125–127 «solo nome, descrizione e regolamento») | D-6 risolta: il regolamento conta tra le regole bloccate → 409 `CONTEST_LIVE_LOCKED` (`ContestAdminService.update` :154–167); in BO-14 il campo è in sola lettura da LIVE |
| — | TB-GAM-ACF-017…019, TB-GAM-PER-017, 019, 022 | docs/03 §8 elenca `NONE, DAY, WEEK` | rifiutati / chiave `EVER` | **chiusa da Q-159** (scelta registrata dopo la prima esecuzione): le righe asseriscono Q-159 e sono verdi | — |
| 7 | TB-GAM-FLT-019, 020, 021, 022, 024, 026, 027, 035, 036, 038 | docs/03 §8 (filtro = condizioni su `data.*`) e §3.3 (comparatori, `not`, gruppi annidati, `[*]`) | comparatori sconosciuti trattati come `eq` o falsi; `not` come `all`; gruppi annidati falsi; `[*]` non risolto | `AchievementRules.java:61–72` (solo `all/any` piatti), `:106–120` (`field` senza `[*]`), `:122–150` (`rule`: solo `eq neq gt gte lt lte in`) | D-7 risolta: `AchievementRules` valuta l'albero con la semantica di `ConditionEvaluator` (campaign-service), implementata localmente (nessuna dipendenza tra servizi) |
| — | TB-GAM-RPT-005 | gamification §3: riepilogo per la Scheda 360° | 404 | **registrata in Q-167**: la riga asserisce il 404 ed è verde | — |

### 21.6 Ambiguità (domande proposte per docs/15)

Righe **AMBIGUO** (56): TB-GAM-PLY-053, TB-GAM-PLY-054, TB-GAM-PLY-055, TB-GAM-PLY-056, TB-GAM-PLY-057, TB-GAM-PLY-059, TB-GAM-CRD-004, TB-GAM-CRD-028, TB-GAM-PTL-002, TB-GAM-PTL-003, TB-GAM-PTL-006, TB-GAM-PRZ-034, TB-GAM-PLT-006, TB-GAM-PLT-007, TB-GAM-PLT-008, TB-GAM-PLT-009, TB-GAM-PLT-017, TB-GAM-EDT-008, TB-GAM-EDT-018, TB-GAM-EDT-040, TB-GAM-EDT-044, TB-GAM-EDT-045, TB-GAM-EDT-062, TB-GAM-EDT-063, TB-GAM-GEN-033, TB-GAM-INS-017, TB-GAM-END-008, TB-GAM-STK-019, TB-GAM-MET-002, TB-GAM-MET-003, TB-GAM-MET-005, TB-GAM-MET-007, TB-GAM-MET-008, TB-GAM-MET-013, TB-GAM-FLT-037, TB-GAM-PRG-013, TB-GAM-PRG-018, TB-GAM-PRG-021, TB-GAM-ACF-012, TB-GAM-ACF-025, TB-GAM-ACF-033, TB-GAM-BDG-005, TB-GAM-BDG-006, TB-GAM-BDG-013, TB-GAM-LCF-001, TB-GAM-LCF-004, TB-GAM-LCF-015, TB-GAM-LCF-020, TB-GAM-LCF-021, TB-GAM-LCF-023, TB-GAM-GRT-004, TB-GAM-GRT-005, TB-GAM-GRT-007, TB-GAM-GRT-008, TB-GAM-GRT-009, TB-GAM-NCK-005.

Domande registrate in docs/15 (identificativi provvisori `Q-289…Q-297`, da rinumerare):
1. **Q-289** — Ordine dei controlli della giocata e codice quando più condizioni sono false (PLY-053…055, CRD con tetto raggiunto e nessuna giocata).
2. **Q-290** — `memberId` assente o vuoto nella giocata e id al posto del codice nel path del portale (PLY-056, PLY-057, PLY-059).
3. **Q-291** — Portale: concorsi `LIVE` fuori periodo, membro non attivo (PTL-002, PTL-003, PTL-006).
4. **Q-292** — Istante piantato: stato del concorso, premio sconosciuto o assente, nessun istante aperto (PLT-006…009, PLT-017).
5. **Q-293** — Concorsi `PAUSED` come `LIVE` per le modifiche; `ENDED/ARCHIVED` non modificabili; codice immutabile (EDT-040, EDT-044, EDT-045, EDT-062, EDT-063 — Q-51 vale solo per le campagne); codice minuscolo normalizzato, distribuzione di default (EDT-008, EDT-018).
6. **Q-294** — Generazione senza premi o senza ore utili in `BUSINESS_HOURS` (INS-017, GEN-033); formato di `asOf` (END-008, come Q-156 per il wallet).
7. **Q-295** — `SUM`: troncamento dei decimali, importi negativi o testuali, forma `tipo.data.campo` (MET-002…008, MET-013, PRG-018, PRG-021); serie con azione fuori ordine (STK-019); progresso di un membro senza snapshot (PRG-013); comparatore sconosciuto (FLT-037).
8. **Q-296** — Configurazione: prefissi `ACH-`/`BDG-`/`LDB-`, `DISTINCT_TYPES` oltre i tipi, top N 3…50, metrica/periodo/codice immutabili (ACF-012, ACF-025, ACF-033, BDG-013, LCF-001, LCF-004, LCF-015, LCF-020, LCF-021, LCF-023).
9. **Q-297** — Effetti: `count` assente o < 1, crediti verso un concorso non `LIVE`, codice DLQ per effetti senza dati o senza membro (GRT-004, GRT-005, GRT-007…009, BDG-005, BDG-006); nota di consegna vuota (PRZ-034); nickname senza nome (NCK-005).

### 21.7 Referral di member-service (non coperto qui)

Le regole R45 (docs/17 §5: MBR-05 `resolveReferral` — codice vuoto, inesistente, invitante non `ACTIVE` per Q-61, `REFERRAL_SELF`; MBR-06 codice `A-Z2-9` di 8 caratteri e `REFERRAL_CODE_EXHAUSTED`; MBR-09 `ReferralService.onAction` — tipo non qualificante, legame assente o già completato, prima azione qualificante → due `referral.completed`, invitante non più attivo) vivono in `services/member-service` e non sono eseguibili con test di questo modulo. Proposta: una classe `TestbookGamReferralIT` in member-service con righe TB-GAM-REF-005 e seguenti, oppure spostare quelle foglie in TB-GOV.

### 21.8 Esecuzione

- Un solo contesto Spring per tutte le classi `TestbookGam*IT`; tempi misurati sul modulo: classi unitarie < 2 s, classi d'integrazione ≈ 45 s più ≈ 20 s di avvio del contesto; `./mvnw -pl services/gamification-service -am verify -Dtest='Testbook*Test' -Dit.test='TestbookGam*IT'` ≈ 70 s (compilazione compresa). Il verify completo del modulo (con i `*IT` preesistenti) ≈ 5 min.
- Rapporto (`scripts/testbook.sh`, `TESTBOOK_WEB=0`): TB-GAM — 710 righe; prima esecuzione 694 OK e 16 fallite (le divergenze di §21.5), dopo le correzioni 710 OK, 0 non eseguite, 0 test senza riga.
- **Verifica a mutazione** (11 mutazioni, file ripristinati, nessuna modifica di produzione residua), righe rosse oltre le divergenze note:

| Classe | Mutazione (file) | Righe rosse |
|---|---|---|
| `TestbookGamPlayIT` | `PlayService.isPlayable`: `endAt` incluso | PLY-023 |
| `TestbookGamPrizeIT` | `PlayService.play`: premio PHYSICAL con consegna `NA` | PRZ-003, PRZ-023…029, PRZ-035 |
| `TestbookGamLifecycleIT` | `ContestAdminService.transition`: niente `INSTANTS_NOT_GENERATED` | LFC-057, LFC-058 |
| `TestbookGamContestIT` | `ContestAdminService.generateInstants`: rigenerazione da LIVE ammessa | INS-004…007 |
| `TestbookGamAchievementIT` | `AchievementService.onAction`: non ripetibile ricompletabile | PRG-006 |
| `TestbookGamLeaderboardIT` | `LeaderboardRepository.ranking`: senza filtro ACTIVE | LDB-014, LDB-015, LDB-016, LDB-018 |
| `TestbookGamEffectIT` | `PlaysGrantHandler`: `effectId` già visto rielaborato | GRT-002 |
| `TestbookGamInstantGeneratorTest` | `InstantGenerator`: chiusura alle 23 | GEN-007, 008, 012, 016, 019, 024, 025, 035, 037 |
| `TestbookGamAchievementRulesTest` | `AchievementRules`: fuso UTC invece di Europe/Rome | PER-002, 004, 006, 009, 011, 023, STK-005, 006, 018 |
| `TestbookGamNicknameTest` | `MemberSnapshotHandler.nickname`: senza iniziale | NCK-002, NCK-003 |
| `TestbookGamApprovalPolicyTest` | `ApprovalPolicy.forContest` (lh-common): nessuna approvazione | APR-001, APR-004 |

