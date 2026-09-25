# TB-GOV — Testbook funzionale: governance e ciclo di vita dei membri

Dominio **governance e membri** del testbook funzionale (`docs/16 §8`): identità simulata e guardie di ruolo, ciclo di vita
degli oggetti governati (campagne, premi, concorsi, contenuti) con approvazioni e policy, stati del membro e loro effetti
negli altri servizi, anonimizzazione, attributi personalizzati, segmenti. Servizi: `libs/lh-common` (logica comune),
`services/member-service`, l'hub consolidato `deploy/hub` per i flussi tra servizi.

- **Oracolo**: `docs/03 §2, §3.3, §3.6, §10` · `docs/06 §2, §3, §7` · `docs/08 §2` (matrice capacità × ruolo, ogni cella) ·
  `docs/02` (`F-APR-*`, `F-MBR-*`, `F-SEG-*`) · `docs/servizi/member-service.md` · `docs/05` + `contracts/events/` ·
  scelte registrate in `docs/15` (Q-08, Q-61, Q-70, Q-85, Q-87, Q-88, Q-91, Q-93, Q-94, Q-96, Q-112, Q-120…Q-128, Q-137,
  Q-138, Q-139, Q-157). Mai «quello che il codice fa oggi».
- **AMBIGUO**: la specifica tace e `docs/15` non registra una scelta → la riga asserisce il comportamento attuale, il test
  lo dichiara con `// TESTBOOK: ambiguo, vedi …` e la riga è elencata in §13 come domanda da registrare.
- **Divergenza**: il test asserisce la specifica e fallisce; registro in §14.

## 0. Esecuzione

| Classe | Tipo | Dati (`src/test/resources/testbook/gov/`) | Aree |
|---|---|---|---|
| `libs/lh-common` · `TestbookGovActorTest` | unit: `ActorContext.parse`, `RequiresRoleInterceptor` | `actor.csv`, `guard.csv` | ACT, GRD |
| `libs/lh-common` · `TestbookGovTransitionsTest` | unit: `GovernedTransitions` (+ `ApprovalStateMachine`) | `parse.csv`, `sm-required.csv`, `sm-none.csv`, `sm-off.csv`, `roles.csv`, `comments.csv`, `override.csv` | PRS, SMR, SMN, SMF, ROL, CMT, OVR |
| `libs/lh-common` · `TestbookGovPolicyTest` | unit: `ApprovalPolicy` | `policy.csv`, `policy-rows.csv` | POL |
| `services/member-service` · `TestbookGovSegmentCriteriaTest` | unit: `SegmentCriteria` | `criteria.csv`, `criteria-validation.csv` | CRT, CRV |
| `services/member-service` · `TestbookGovAttributesTest` | unit: `MemberAttributes` | `attribute-values.csv`, `attribute-definitions.csv` | ATV, ATD |
| `services/member-service` · `TestbookGovMemberIT` | integrazione (Spring, Postgres e Kafka embedded, profilo `demo`) | `member-status.csv`, `member-roles.csv`, `anonymize.csv`, `attributes-in-use.csv`, `segments.csv` | MST, MRL, ANO, ATU, SEG |

Ogni caso ha nome `[<ID>] <descrizione>`; una riga = un caso eseguito. Nei CSV i valori speciali sono scritti come
`NULL` (assente), `EMPTY` (""), `SPACE`/`SPACES` (spazi), `TABNL` (tabulazione e a capo), `NBSP` (U+00A0), `LONG` (2000
caratteri) e `«…»` (valore con spazi ai bordi conservati).

Nelle tabelle di stato × azione la regola della policy è **REQ** (approvazione richiesta, approvatore LEGAL: concorsi,
premi, campagne con `requiresLegal` o budget oltre soglia) oppure **NONE** (campagna sotto soglia; qualunque oggetto con
la policy spenta, perché `ApprovalPolicy.for*` restituisce `NONE` quando `loyaltyhub.approval.enabled=false`). Il ruolo
delle righe di stato è quello autorizzato (MARKETING per `object.edit`, LEGAL per `object.approve`) con un commento non
vuoto, così che ogni riga misuri una sola decisione.

## 1. Inventario delle regole

| Regola | Enunciato (sintesi) | Fonte | Righe |
|---|---|---|---|
| R-01 | `X-LH-Actor: <RUOLO>:<username>`; assente → `ANALYST:anonymous` (sola lettura) | docs/06 §3 | ACT |
| R-02 | Guardia minima `@RequiresRole`: ADMIN passa sempre; elenco di ruoli; annotazione vuota = scrittura (≠ ANALYST) | docs/06 §3 · docs/08 §2 | GRD |
| R-03 | Matrice capacità × ruolo: ● ⇒ il backend rifiuta con 403; il backend rifiuta ogni scrittura di ANALYST | docs/08 §2 | MAT, MRL |
| R-04 | Azioni del ciclo di vita: `SUBMIT, APPROVE, REJECT, PUBLISH, PAUSE, RESUME, END, ARCHIVE` (enum `UPPER_SNAKE`) | docs/03 §3.6 · docs/06 §2 | PRS |
| R-05 | Tabella delle transizioni; ogni altra coppia stato × azione ⇒ 409 `conflict` | docs/03 §3.6 · docs/06 §2 | SMR, SMN, SMF, ENT |
| R-06 | `PUBLISH` da `DRAFT` solo se la policy non richiede approvazione | docs/03 §3.6 · docs/06 §7 | SMR-004, SMN-001, SMF-004, ENT |
| R-07 | `REJECT` richiede un commento (422) | docs/03 §3.6 · docs/12 M7 | CMT |
| R-08 | `APPROVE`/`REJECT` al ruolo della policy o ad ADMIN (`object.approve` = ADMIN, LEGAL) | docs/03 §3.6 · docs/06 §3 · docs/08 §2 | ROL |
| R-09 | Le altre transizioni con `object.edit` = ADMIN, MARKETING | docs/08 §2 | ROL, ENT |
| R-10 | ADMIN che decide al posto dell'approvatore: override marcato in audit | docs/08 §2 | OVR, ENT |
| R-11 | Policy: `CONTEST`, `REWARD` sempre LEGAL; `CAMPAIGN` se `requiresLegal` o budget > 100 000 punti; `CONTENT` mai | docs/06 §7 · F-APR-02 · Q-08 | POL, ENT |
| R-12 | Policy spenta (`LH_APPROVAL_ENABLED=false`): `DRAFT → LIVE` diretto per tutti | docs/06 §7 | SMF, ROL-051…055, POL, ENT |
| R-13 | Ogni transizione scrive storico (chi, quando, commento), audit e fatto `*.status.changed` | docs/03 §3.6 · docs/06 §7 · docs/05 | ENT |
| R-14 | Casella approvazioni `GET /v1/approvals?status=IN_REVIEW` nel formato comune; `?submittedBy=`; `/policy` | docs/06 §7 · F-APR-03 · Q-96 | APQ, POL-037…044 |
| R-15 | Stati del membro `ACTIVE, INACTIVE, BLOCKED, ANONYMIZED`; `POST …/status` ammette `BLOCKED/INACTIVE/ACTIVE`; `ANONYMIZED` irreversibile | docs/03 §2 · F-MBR-04 · member §3 · Q-139 | MST |
| R-16 | Cambio di stato ⇒ `member.status.changed {previousStatus, newStatus, reason}` + audit; motivo facoltativo | member §4 · docs/05 · Q-138 | MST |
| R-17 | Solo i membri `ACTIVE` accumulano, spendono, giocano; `BLOCKED` conserva i saldi e le sue azioni sono `REJECTED` in ingresso | docs/03 §2 · F-MBR-04 · ingestion §5 · reward §3 · gamification §3 · EVT-FACT-22 | EFF |
| R-18 | Rettifiche manuali: rifiutate (409 `MEMBER_ANONYMIZED`) solo per gli anonimizzati | Q-127 | EFF |
| R-19 | `member.write` (crea, modifica, cambia stato) = ADMIN, CARE | docs/08 §2 · Q-157 | MRL, MAT |
| R-20 | Anonimizzazione: solo ADMIN (● `member.anonymize`), conferma con l'id, irreversibile | F-MBR-05 · member §3 · docs/08 §2, §3.5 | ANO |
| R-21 | Anonimizzazione: nome → «Membro anonimo» (nel `nickname`), e-mail/telefono → `null`, attributi e consensi cancellati; restano id, movimenti, statistiche, etichette, referral | docs/03 §2 · Q-120 · Q-121 | ANO |
| R-22 | Propagazione: ogni servizio cancella i dati personali dal proprio snapshot; eventi successivi `UNMATCHED`/`MEMBER_NOT_ACTIVE` | docs/12 M7.5 · Q-122…Q-128 · Q-70 | ANX |
| R-23 | Attributi personalizzati tipizzati (string, number, boolean, date): valore conforme alla definizione | docs/03 §2 · F-MBR-03 | ATV |
| R-24 | Definizioni degli attributi: `segment.write` (ADMIN, MARKETING); definizione non valida ⇒ 422 | F-MBR-03 · docs/08 §2 · Q-94 | ATD |
| R-25 | Chiave con valori sui membri: non si toglie né cambia tipo (409 `ATTRIBUTE_IN_USE`) | Q-93 | ATU |
| R-26 | Criteri dei segmenti: formato delle condizioni, 14 comparatori; campo assente ⇒ falso (tranne `nexists`); tipi incompatibili ⇒ falso | docs/03 §3.3, §10 · Q-91 | CRT |
| R-27 | Campi dei segmenti: `member.*` più `balance.PTS`, `lifetimeEarned.PTS`, `lastActivityDaysAgo`, `actions.<type>.count30d`, `purchases.amount90d`, `city` | docs/03 §10 · Q-87 | CRT, CRV |
| R-28 | Criteri non validi ⇒ 422 `INVALID_CRITERIA`; criteri vuoti ⇒ nessun membro | member §3 · Q-87 | CRV, SEG |
| R-29 | Segmenti dinamici: si valutano tutti gli stati tranne `ANONYMIZED` | Q-85 | SEG |
| R-30 | Segmenti: scritture con `segment.write`; anteprima in lettura per tutti | docs/08 §2 · member §3 | SEG, MRL |
| R-31 | Contenuti: stesso ciclo di vita, pubblicazione diretta | docs/03 §3.6 · docs/06 §7 | ENT |

## 2. Rami del codice mappati sulle regole

Percorsi relativi a `libs/lh-common/src/main/java/io/loyaltyhub/common/` e `services/member-service/src/main/java/io/loyaltyhub/member/`.
«Senza specifica» = ramo che nessuna fonte decide (righe AMBIGUO); «non raggiungibile» = protetto da un controllo precedente.

| Ramo | Punto (file:riga) | Regola | Righe |
|---|---|---|---|
| B-01 | `web/ActorContext.java:13` intestazione assente o vuota → `ANALYST:anonymous` | R-01 | ACT-001…003 |
| B-02 | `web/ActorContext.java:17` senza `:` o `:` in testa/coda → ruolo dal testo intero, username `anonymous` | senza specifica | ACT-010, ACT-011, ACT-013 |
| B-03 | `web/ActorContext.java:22` username vuoto → `anonymous` | senza specifica | ACT-012 |
| B-04 | `web/Role.java:12-18` ruolo assente/sconosciuto → `ANALYST`; `trim` + maiuscole | R-01; lettura permissiva senza specifica | ACT-007…009 |
| B-05 | `web/RequiresRoleInterceptor.java:15` gestore non `HandlerMethod` → passa | non raggiungibile per le API | — |
| B-06 | `web/RequiresRoleInterceptor.java:19-23` annotazione di metodo, poi di classe, assente → passa | R-02 | GRD-001…005, GRD-026…030 |
| B-07 | `web/RequiresRoleInterceptor.java:26` ADMIN passa sempre | R-02, R-03 | GRD (ADMIN) |
| B-08 | `web/RequiresRoleInterceptor.java:30-35` annotazione vuota → tutti tranne ANALYST | R-02 | GRD-006…010 |
| B-09 | `web/RequiresRoleInterceptor.java:37-40` ruolo nell'elenco, altrimenti 403 `FORBIDDEN_ROLE` | R-02, R-03 | GRD-011…025 |
| B-10 | `approval/GovernedTransitions.java:26-28` `trim` + maiuscole; sconosciuta/vuota/assente → 422 `INVALID_ACTION` | R-04 (codice e permissività senza specifica) | PRS |
| B-11 | `approval/GovernedTransitions.java:35-38` decisione con ruolo ≠ approvatore (default LEGAL) e ≠ ADMIN → 403 | R-08 | ROL-006…015, ROL-041…050 |
| B-12 | `approval/GovernedTransitions.java:40-41` altre azioni con ruolo ≠ ADMIN/MARKETING → 403 | R-09 | ROL-001…005, ROL-016…040, ROL-053…055 |
| B-13 | `approval/GovernedTransitions.java:44-45` policy spenta + `SUBMIT` da `DRAFT` → `LIVE` | R-12 (che sia `SUBMIT` a farlo: senza specifica) | SMF-001, ROL-051…055 |
| B-14 | `approval/GovernedTransitions.java:47` policy spenta ⇒ approvazione mai richiesta | R-12 | SMF |
| B-15 | `approval/GovernedTransitions.java:52` override = decisione di ADMIN con approvatore di policy ≠ ADMIN | R-10 | OVR |
| B-16 | `approval/ApprovalStateMachine.java:24-25` `SUBMIT` DRAFT→IN_REVIEW, `APPROVE` IN_REVIEW→APPROVED | R-05 | SMR/SMF (colonne SUBMIT, APPROVE) |
| B-17 | `approval/ApprovalStateMachine.java:26-31` `REJECT` solo da IN_REVIEW, poi commento (stato prima del commento) | R-05, R-07 | SMR-011, CMT, ROL-060 |
| B-18 | `approval/ApprovalStateMachine.java:33-44` `PUBLISH` da APPROVED; da DRAFT solo senza approvazione (409 `APPROVAL_REQUIRED`) | R-06 | SMR-004, SMR-020, SMN-001…007, SMF-004 |
| B-19 | `approval/ApprovalStateMachine.java:46-58` `PAUSE`, `RESUME`, `END` (LIVE/PAUSED), `ARCHIVE` (ENDED/DRAFT) | R-05 | SMR, SMF |
| B-20 | `approval/ApprovalStateMachine.java:70-76` ogni altra coppia → 409 `INVALID_TRANSITION` | R-05 | SMR, SMF (409) |
| B-21 | `approval/ApprovalPolicy.java:45-49` concorsi e premi: LEGAL se accesa | R-11 | POL-029…032 |
| B-22 | `approval/ApprovalPolicy.java:54-63` campagna: spenta → no; `requiresLegal` → LEGAL; budget > soglia → LEGAL; altrimenti no | R-11, R-12 | POL-001…028, POL-035, POL-036 |
| B-23 | `approval/ApprovalPolicy.java:67` righe della scheda policy (CONTENT sempre «mai») | R-11, R-14 | POL-033, POL-034, POL-037…044 |
| B-24 | `application/MemberService.java:249-250` membro inesistente 404; anonimizzato 409 `MEMBER_ANONYMIZED` | R-15 | MST-025…032, MST-037 |
| B-25 | `application/MemberService.java:371-377` destinazione ∉ {ACTIVE, INACTIVE, BLOCKED} → 400 (con `trim` + maiuscole) | R-15; minuscole senza specifica | MST (colonne ANONYMIZED, CLOSED, SOSPESO, assente, minuscolo) |
| B-26 | `application/MemberService.java:253-255` stesso stato → nessun fatto né audit | senza specifica | MST-001, MST-010, MST-019 |
| B-27 | `application/MemberService.java:256-268` cambio → fatto `member.status.changed` (motivo `""` se assente) + audit TRANSITION | R-16 | MST, MST-033…036 |
| B-28 | `application/MemberService.java:379-388` filtro di stato sconosciuto → 400; `CLOSED` accettato (enum `domain/MemberStatus.java:9`) | R-15; `CLOSED` senza specifica (Q-139) | MST-039…041 |
| B-29 | `application/MemberService.java:282-288` anonimizzazione: 404, poi `CONFIRM_MISMATCH` (422), poi già anonimizzato (409) | R-20 | ANO-001…010, ANO-018 |
| B-30 | `application/MemberService.java:291-292` versione concorrente → 409 `VERSION_CONFLICT` | R-20 | non raggiungibile senza concorrenza |
| B-31 | `application/MemberService.java:294-304` fatti `status.changed` poi `member.updated` ripulito, audit senza dati personali | R-20, R-22 | ANO-037…040 |
| B-32 | `domain/Anonymization.java:28-52` campi cancellati e conservati | R-21 (Q-120, Q-121) | ANO-019…036 |
| B-33 | `domain/Anonymization.java:55-57` conferma confrontata dopo `trim` | R-20; `trim` senza specifica | ANO-002, ANO-003 |
| B-34 | `application/MemberService.java:170, 203` PATCH: anonimizzato 409; attributi/etichette non validi 422 `MEMBER_INVALID` | R-20, R-23 | ANO-046, ATU-013, ATU-014 |
| B-35 | `application/MemberService.java:339-341` codice invito di un membro non ACTIVE → 422 | Q-61 | ANO-047 |
| B-36 | `domain/MemberAttributes.java:105-131` valore × tipo; STRING: non vuoto, ≤ 200, nelle opzioni | R-23; vuoto e 200 senza specifica | ATV |
| B-37 | `domain/MemberAttributes.java:69-103` chiave non definita o interna → problema; `null` rimuove | R-23; `null` senza specifica | ATV-015/031/047/063, ATV-068…070 |
| B-38 | `domain/MemberAttributes.java:151-180` definizioni: chiave, etichetta, tipo, opzioni, tetto 30 | R-24; formati e tetti senza specifica | ATD |
| B-39 | `domain/MemberAttributes.java:165` tipo assente → `NullPointerException` (500) | R-24 — **divergenza** | ATD-023, ATU-011 |
| B-40 | `application/AttributeService.java:44-60` 422 `ATTRIBUTE_DEFINITION_INVALID`; chiave tolta o ritipizzata con valori → 409 | R-24, R-25 | ATU |
| B-41 | `domain/SegmentCriteria.java:49-101` validazione di gruppi, campi, comparatori e valori | R-26…R-28 | CRV |
| B-42 | `domain/SegmentCriteria.java:118-155` criteri vuoti ⇒ falso; `any`/`not`/`all` | R-26, R-28 (Q-87, Q-90) | CRT-109…118 |
| B-43 | `domain/SegmentCriteria.java:159-199` risoluzione dei campi (età e giorni in Europe/Rome o a blocchi di 24 h) | R-27 | CRT-084…108 |
| B-44 | `domain/SegmentCriteria.java:225-253` assente ⇒ falso (tranne `nexists`); liste elemento per elemento, `in/nin` sull'intersezione | R-26 | CRT-061…083 |
| B-45 | `domain/SegmentCriteria.java:258, 264, 266, 268` `neq`/`nin`/`ncontains` veri e `startsWith` su `toString()` con tipi incompatibili | R-26 — **divergenza** | CRT-024, 028, 038, 042, 057…060 |
| B-46 | `infra/SegmentFactsRepository.java:69` esclusi gli `ANONYMIZED` | R-29 (Q-85) | SEG-026…029 |
| B-47 | `application/SegmentService.java:112-132` creazione: codice, nome, tipo, criteri, duplicato | R-28, docs/06 §2 | SEG-005…014 |
| B-48 | `application/SegmentService.java:152-177` modifica: versione, codice e tipo immutabili, stato | Q-88 | SEG-018, SEG-019, SEG-022, SEG-030 |
| B-49 | `application/SegmentService.java:191-198` archiviazione ⇒ `left` per tutti; criteri cambiati ⇒ ricalcolo | Q-88, F-SEG-03 | SEG-020, SEG-024 |
| B-50 | `application/SegmentService.java:206, 220-224` archiviato ⇒ 409 `SEGMENT_ARCHIVED`; elenco su dinamico ⇒ 409 `SEGMENT_NOT_STATIC` | R-28 (codici senza specifica) | SEG-017, SEG-021 |
| B-51 | `application/SegmentService.java:263-278` statico con membri inesistenti o anonimizzati ⇒ 422 `MEMBER_NOT_FOUND` | senza specifica | SEG-015, SEG-016 |
| B-52 | `api/MembersController.java`, `SegmentsController.java`, `AttributeDefinitionsController.java`, `MemberJobsController.java` guardie `@RequiresRole` | R-03, R-19, R-30 | MRL |
| B-53 | `web/GlobalExceptionHandler.java:66` (lh-common) corpo assente o JSON illeggibile → 500 `INTERNAL_ERROR` | docs/06 §2 (400) — **divergenza** | MST-038 |

## 3. Identità simulata e guardie di ruolo

### 3.1 `X-LH-Actor` (`ActorContext.parse`)
Domini: intestazione assente / vuota / solo spazi (classe «assente», docs/06 §3); forma canonica per ogni ruolo; forme non
canoniche (minuscole, spazi, ruolo sconosciuto, senza `:`, `:` in testa o in coda, più `:`). Strategia: ogni classe una
volta (dominio a una dimensione). La specifica fissa solo la forma canonica e l'assenza: le altre righe sono AMBIGUO (la
risposta attuale è la più conservativa: ruolo sconosciuto ⇒ ANALYST, sola lettura).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ACT-001 | `X-LH-Actor` = assente | `ANALYST:anonymous` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-002 | `X-LH-Actor` = `""` | `ANALYST:anonymous` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-003 | `X-LH-Actor` = `"   "` | `ANALYST:anonymous` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-004 | `X-LH-Actor` = `"MARKETING:luca.marketing"` | `MARKETING:luca.marketing` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-005 | `X-LH-Actor` = `"ADMIN:marta.admin"` | `ADMIN:marta.admin` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-006 | `X-LH-Actor` = `"ANALYST:anna"` | `ANALYST:anna` | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-007 | `X-LH-Actor` = `"marketing:luca"` | `MARKETING:luca` — AMBIGUO | docs/06 §3 (enum UPPER_SNAKE, docs/06 §2) | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-008 | `X-LH-Actor` = `" CARE : paolo "` | `CARE:paolo` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-009 | `X-LH-Actor` = `"ADMINISTRATOR:x"` | `ANALYST:x` — AMBIGUO | docs/06 §3 · US-E08-06 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-010 | `X-LH-Actor` = `"LEGAL"` | `LEGAL:anonymous` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-011 | `X-LH-Actor` = `"CARE:"` | `ANALYST:anonymous` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-012 | `X-LH-Actor` = `"CARE: "` | `CARE:anonymous` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-013 | `X-LH-Actor` = `":paolo"` | `ANALYST:anonymous` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |
| TB-GOV-ACT-014 | `X-LH-Actor` = `"ADMIN:marta:extra"` | `ADMIN:marta:extra` — AMBIGUO | docs/06 §3 | `TestbookGovActorTest#parse` |

### 3.2 Guardia `@RequiresRole` (`RequiresRoleInterceptor`)
Domini: variante dell'annotazione (assente, vuota, un ruolo, due ruoli, solo ADMIN, sulla classe) × ruolo (5). Prodotto
6 × 5 = 30 ≤ 64 ⇒ **tabella completa**.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-GRD-001 | nessuna annotazione, attore ADMIN | passa | docs/06 §3 (letture libere) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-002 | nessuna annotazione, attore MARKETING | passa | docs/06 §3 (letture libere) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-003 | nessuna annotazione, attore LEGAL | passa | docs/06 §3 (letture libere) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-004 | nessuna annotazione, attore CARE | passa | docs/06 §3 (letture libere) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-005 | nessuna annotazione, attore ANALYST | passa | docs/06 §3 (letture libere) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-006 | `@RequiresRole` vuota (regola «scrittura»), attore ADMIN | passa | docs/06 §3 · docs/08 §2 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-007 | `@RequiresRole` vuota (regola «scrittura»), attore MARKETING | passa | docs/06 §3 · docs/08 §2 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-008 | `@RequiresRole` vuota (regola «scrittura»), attore LEGAL | passa | docs/06 §3 · docs/08 §2 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-009 | `@RequiresRole` vuota (regola «scrittura»), attore CARE | passa | docs/06 §3 · docs/08 §2 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-010 | `@RequiresRole` vuota (regola «scrittura»), attore ANALYST | 403 `FORBIDDEN_ROLE` | docs/06 §3 · docs/08 §2 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-011 | `@RequiresRole(MARKETING)`, attore ADMIN | passa | docs/08 §2 (ADMIN ✓ ovunque) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-012 | `@RequiresRole(MARKETING)`, attore MARKETING | passa | docs/08 §2 (ADMIN ✓ ovunque) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-013 | `@RequiresRole(MARKETING)`, attore LEGAL | 403 `FORBIDDEN_ROLE` | docs/08 §2 (ADMIN ✓ ovunque) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-014 | `@RequiresRole(MARKETING)`, attore CARE | 403 `FORBIDDEN_ROLE` | docs/08 §2 (ADMIN ✓ ovunque) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-015 | `@RequiresRole(MARKETING)`, attore ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 (ADMIN ✓ ovunque) | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-016 | `@RequiresRole({ADMIN, CARE})`, attore ADMIN | passa | docs/08 §2 `member.write` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-017 | `@RequiresRole({ADMIN, CARE})`, attore MARKETING | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-018 | `@RequiresRole({ADMIN, CARE})`, attore LEGAL | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-019 | `@RequiresRole({ADMIN, CARE})`, attore CARE | passa | docs/08 §2 `member.write` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-020 | `@RequiresRole({ADMIN, CARE})`, attore ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-021 | `@RequiresRole(ADMIN)`, attore ADMIN | passa | docs/08 §2 `member.anonymize` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-022 | `@RequiresRole(ADMIN)`, attore MARKETING | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-023 | `@RequiresRole(ADMIN)`, attore LEGAL | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-024 | `@RequiresRole(ADMIN)`, attore CARE | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-025 | `@RequiresRole(ADMIN)`, attore ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-026 | `@RequiresRole(LEGAL)` sulla classe, attore ADMIN | passa | docs/06 §3 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-027 | `@RequiresRole(LEGAL)` sulla classe, attore MARKETING | 403 `FORBIDDEN_ROLE` | docs/06 §3 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-028 | `@RequiresRole(LEGAL)` sulla classe, attore LEGAL | passa | docs/06 §3 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-029 | `@RequiresRole(LEGAL)` sulla classe, attore CARE | 403 `FORBIDDEN_ROLE` | docs/06 §3 | `TestbookGovActorTest#guard` |
| TB-GOV-GRD-030 | `@RequiresRole(LEGAL)` sulla classe, attore ANALYST | 403 `FORBIDDEN_ROLE` | docs/06 §3 | `TestbookGovActorTest#guard` |

## 4. Azioni del ciclo di vita (`GovernedTransitions.parse`)
Domini: ogni valore dell'enum (8) + classi non valide (minuscolo, maiuscole miste, spazi, `ACTIVATE`, sconosciuta, quasi
valida, vuota, assente). La specifica (docs/06 §2) vuole enum `UPPER_SNAKE` e per i parametri errati `400 bad-request`; non
dice se l'azione si legge in modo permissivo né quale codice dare a un'azione sconosciuta: righe 009…016 AMBIGUO (oggi
lettura permissiva e 422 `INVALID_ACTION`).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-PRS-001 | `action` = `SUBMIT` | `SUBMIT` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-002 | `action` = `APPROVE` | `APPROVE` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-003 | `action` = `REJECT` | `REJECT` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-004 | `action` = `PUBLISH` | `PUBLISH` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-005 | `action` = `PAUSE` | `PAUSE` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-006 | `action` = `RESUME` | `RESUME` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-007 | `action` = `END` | `END` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-008 | `action` = `ARCHIVE` | `ARCHIVE` | docs/03 §3.6 · docs/06 §2 | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-009 | `action` = `"submit"` | `SUBMIT` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-010 | `action` = `"Approve"` | `APPROVE` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-011 | `action` = `" PUBLISH "` | `PUBLISH` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-012 | `action` = `"ACTIVATE"` | 422 `INVALID_ACTION` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-013 | `action` = `"FOO"` | 422 `INVALID_ACTION` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-014 | `action` = `"SUBMIT_"` | 422 `INVALID_ACTION` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-015 | `action` = `""` | 422 `INVALID_ACTION` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |
| TB-GOV-PRS-016 | `action` = assente | 422 `INVALID_ACTION` — AMBIGUO | docs/06 §2 (enum UPPER_SNAKE; 400 «parametri errati») | `TestbookGovTransitionsTest#parse` |

## 5. Macchina a stati degli oggetti governati

Domini: stato di partenza (7) × azione (8) × configurazione di policy (ON con approvazione richiesta, ON senza, OFF).
Strategia:
- **SMR** — tabella **completa** 7 × 8 = 56 con policy ON e approvazione richiesta (concorsi, premi, campagne sopra soglia).
- **SMN** — policy ON senza approvazione (campagna sotto soglia): la regola entra solo nel ramo `PUBLISH` da `DRAFT`
  (`ApprovalStateMachine.java:38`) e nella scelta dell'approvatore: si provano `PUBLISH` × 7 stati più `SUBMIT` da DRAFT,
  `APPROVE`/`REJECT` da IN_REVIEW (10 righe); le altre 46 celle coincidono con SMR (riduzione dichiarata, ramo non letto).
- **SMF** — tabella **completa** 7 × 8 = 56 con `loyaltyhub.approval.enabled=false`.
- Il **ruolo** è un controllo indipendente e precedente (§6): la tabella combinata stato × azione × ruolo × policy
  (7 × 8 × 5 × 2 = 560) si riduce a stato × azione (qui) + ruolo × azione dallo stato valido (ROL) + le precedenze
  (ROL-056…060).
- Il **tipo di oggetto** (campagna, premio, concorso) entra solo attraverso la regola REQ/NONE: le tre implementazioni
  delegano a `GovernedTransitions`; il cablaggio per tipo è provato nell'hub (§12.1, area ENT), il contenuto (macchina
  propria dell'engagement) solo lì.

Atteso (docs/03 §3.6): `DRAFT —SUBMIT→ IN_REVIEW —APPROVE→ APPROVED —PUBLISH→ LIVE`, `IN_REVIEW —REJECT→ DRAFT`,
`DRAFT —PUBLISH→ LIVE` solo senza approvazione, `LIVE ⇄ PAUSED` (`PAUSE`/`RESUME`), `LIVE|PAUSED —END→ ENDED`,
`ENDED|DRAFT —ARCHIVE→ ARCHIVED`; ogni altra coppia 409 `conflict` (`INVALID_TRANSITION`); `PUBLISH` da DRAFT con
approvazione richiesta 409 `APPROVAL_REQUIRED`.

### 5.1 Policy ON, approvazione richiesta (SMR)

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-SMR-001 | DRAFT + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `IN_REVIEW` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-002 | DRAFT + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-003 | DRAFT + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-004 | DRAFT + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `APPROVAL_REQUIRED` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-005 | DRAFT + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-006 | DRAFT + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-007 | DRAFT + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-008 | DRAFT + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `ARCHIVED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-009 | IN_REVIEW + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-010 | IN_REVIEW + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | `APPROVED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-011 | IN_REVIEW + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | `DRAFT` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-012 | IN_REVIEW + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-013 | IN_REVIEW + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-014 | IN_REVIEW + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-015 | IN_REVIEW + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-016 | IN_REVIEW + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-017 | APPROVED + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-018 | APPROVED + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-019 | APPROVED + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-020 | APPROVED + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `LIVE` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-021 | APPROVED + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-022 | APPROVED + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-023 | APPROVED + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-024 | APPROVED + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-025 | LIVE + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-026 | LIVE + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-027 | LIVE + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-028 | LIVE + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-029 | LIVE + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `PAUSED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-030 | LIVE + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-031 | LIVE + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `ENDED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-032 | LIVE + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-033 | PAUSED + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-034 | PAUSED + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-035 | PAUSED + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-036 | PAUSED + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-037 | PAUSED + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-038 | PAUSED + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `LIVE` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-039 | PAUSED + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `ENDED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-040 | PAUSED + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-041 | ENDED + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-042 | ENDED + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-043 | ENDED + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-044 | ENDED + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-045 | ENDED + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-046 | ENDED + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-047 | ENDED + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-048 | ENDED + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | `ARCHIVED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-049 | ARCHIVED + SUBMIT (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-050 | ARCHIVED + APPROVE (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-051 | ARCHIVED + REJECT (LEGAL, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-052 | ARCHIVED + PUBLISH (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-053 | ARCHIVED + PAUSE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-054 | ARCHIVED + RESUME (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-055 | ARCHIVED + END (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMR-056 | ARCHIVED + ARCHIVE (MARKETING, policy ON, approvazione richiesta (LEGAL)) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |

### 5.2 Policy ON, nessuna approvazione (SMN)

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-SMN-001 | DRAFT + PUBLISH (MARKETING, policy ON, nessuna approvazione) | `LIVE` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-002 | IN_REVIEW + PUBLISH (MARKETING, policy ON, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-003 | APPROVED + PUBLISH (MARKETING, policy ON, nessuna approvazione) | `LIVE` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-004 | LIVE + PUBLISH (MARKETING, policy ON, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-005 | PAUSED + PUBLISH (MARKETING, policy ON, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-006 | ENDED + PUBLISH (MARKETING, policy ON, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-007 | ARCHIVED + PUBLISH (MARKETING, policy ON, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-008 | DRAFT + SUBMIT (MARKETING, policy ON, nessuna approvazione) | `IN_REVIEW` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-009 | IN_REVIEW + APPROVE (LEGAL, policy ON, nessuna approvazione) | `APPROVED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMN-010 | IN_REVIEW + REJECT (LEGAL, policy ON, nessuna approvazione) | `DRAFT` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |

### 5.3 Policy spenta (SMF)
docs/06 §7: «`loyaltyhub.approval.enabled=false` consente `DRAFT → LIVE` diretto per tutti». Che anche `SUBMIT` da DRAFT
porti direttamente a LIVE (invece di IN_REVIEW) non è scritto: SMF-001 AMBIGUO.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-SMF-001 | DRAFT + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | `LIVE` — AMBIGUO | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-002 | DRAFT + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-003 | DRAFT + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-004 | DRAFT + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | `LIVE` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-005 | DRAFT + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-006 | DRAFT + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-007 | DRAFT + END (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-008 | DRAFT + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | `ARCHIVED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-009 | IN_REVIEW + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-010 | IN_REVIEW + APPROVE (LEGAL, policy OFF, nessuna approvazione) | `APPROVED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-011 | IN_REVIEW + REJECT (LEGAL, policy OFF, nessuna approvazione) | `DRAFT` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-012 | IN_REVIEW + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-013 | IN_REVIEW + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-014 | IN_REVIEW + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-015 | IN_REVIEW + END (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-016 | IN_REVIEW + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-017 | APPROVED + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-018 | APPROVED + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-019 | APPROVED + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-020 | APPROVED + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | `LIVE` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-021 | APPROVED + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-022 | APPROVED + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-023 | APPROVED + END (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-024 | APPROVED + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-025 | LIVE + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-026 | LIVE + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-027 | LIVE + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-028 | LIVE + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-029 | LIVE + PAUSE (MARKETING, policy OFF, nessuna approvazione) | `PAUSED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-030 | LIVE + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-031 | LIVE + END (MARKETING, policy OFF, nessuna approvazione) | `ENDED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-032 | LIVE + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-033 | PAUSED + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-034 | PAUSED + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-035 | PAUSED + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-036 | PAUSED + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-037 | PAUSED + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-038 | PAUSED + RESUME (MARKETING, policy OFF, nessuna approvazione) | `LIVE` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-039 | PAUSED + END (MARKETING, policy OFF, nessuna approvazione) | `ENDED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-040 | PAUSED + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-041 | ENDED + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-042 | ENDED + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-043 | ENDED + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-044 | ENDED + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-045 | ENDED + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-046 | ENDED + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-047 | ENDED + END (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-048 | ENDED + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | `ARCHIVED` | docs/03 §3.6 · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-049 | ARCHIVED + SUBMIT (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-050 | ARCHIVED + APPROVE (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-051 | ARCHIVED + REJECT (LEGAL, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-052 | ARCHIVED + PUBLISH (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-053 | ARCHIVED + PAUSE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-054 | ARCHIVED + RESUME (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-055 | ARCHIVED + END (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-SMF-056 | ARCHIVED + ARCHIVE (MARKETING, policy OFF, nessuna approvazione) | 409 `INVALID_TRANSITION` | docs/03 §3.6 · docs/06 §7 · docs/06 §2 (409) | `TestbookGovTransitionsTest#stateMachine` |

## 6. Ruoli, override e commento del rifiuto

### 6.1 Ruolo × azione (ROL)
Domini: azione (8, ciascuna dal suo stato di partenza valido) × ruolo (5), policy ON e approvazione richiesta: 40 righe,
**completa**. Più: decisioni con regola senza approvatore × ruolo (10, completa), `SUBMIT` a policy spenta × ruolo (5,
completa), e le precedenze tra controlli (5): la specifica non fissa l'ordine tra 403, 409 e 422 ⇒ AMBIGUO (oggi prima il
ruolo, poi lo stato, poi il commento).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ROL-001 | SUBMIT da DRAFT, ruolo ADMIN (policy ON, LEGAL richiesto) | `IN_REVIEW` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-002 | SUBMIT da DRAFT, ruolo MARKETING (policy ON, LEGAL richiesto) | `IN_REVIEW` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-003 | SUBMIT da DRAFT, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-004 | SUBMIT da DRAFT, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-005 | SUBMIT da DRAFT, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-006 | APPROVE da IN_REVIEW, ruolo ADMIN (policy ON, LEGAL richiesto) | `APPROVED` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-007 | APPROVE da IN_REVIEW, ruolo MARKETING (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-008 | APPROVE da IN_REVIEW, ruolo LEGAL (policy ON, LEGAL richiesto) | `APPROVED` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-009 | APPROVE da IN_REVIEW, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-010 | APPROVE da IN_REVIEW, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-011 | REJECT da IN_REVIEW, ruolo ADMIN (policy ON, LEGAL richiesto) | `DRAFT` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-012 | REJECT da IN_REVIEW, ruolo MARKETING (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-013 | REJECT da IN_REVIEW, ruolo LEGAL (policy ON, LEGAL richiesto) | `DRAFT` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-014 | REJECT da IN_REVIEW, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-015 | REJECT da IN_REVIEW, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-016 | PUBLISH da APPROVED, ruolo ADMIN (policy ON, LEGAL richiesto) | `LIVE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-017 | PUBLISH da APPROVED, ruolo MARKETING (policy ON, LEGAL richiesto) | `LIVE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-018 | PUBLISH da APPROVED, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-019 | PUBLISH da APPROVED, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-020 | PUBLISH da APPROVED, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-021 | PAUSE da LIVE, ruolo ADMIN (policy ON, LEGAL richiesto) | `PAUSED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-022 | PAUSE da LIVE, ruolo MARKETING (policy ON, LEGAL richiesto) | `PAUSED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-023 | PAUSE da LIVE, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-024 | PAUSE da LIVE, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-025 | PAUSE da LIVE, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-026 | RESUME da PAUSED, ruolo ADMIN (policy ON, LEGAL richiesto) | `LIVE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-027 | RESUME da PAUSED, ruolo MARKETING (policy ON, LEGAL richiesto) | `LIVE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-028 | RESUME da PAUSED, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-029 | RESUME da PAUSED, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-030 | RESUME da PAUSED, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-031 | END da LIVE, ruolo ADMIN (policy ON, LEGAL richiesto) | `ENDED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-032 | END da LIVE, ruolo MARKETING (policy ON, LEGAL richiesto) | `ENDED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-033 | END da LIVE, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-034 | END da LIVE, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-035 | END da LIVE, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-036 | ARCHIVE da ENDED, ruolo ADMIN (policy ON, LEGAL richiesto) | `ARCHIVED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-037 | ARCHIVE da ENDED, ruolo MARKETING (policy ON, LEGAL richiesto) | `ARCHIVED` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-038 | ARCHIVE da ENDED, ruolo LEGAL (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-039 | ARCHIVE da ENDED, ruolo CARE (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-040 | ARCHIVE da ENDED, ruolo ANALYST (policy ON, LEGAL richiesto) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.edit` · docs/06 §3, §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-041 | APPROVE da IN_REVIEW, ruolo ADMIN, regola senza approvatore (campagna sotto soglia) | `APPROVED` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-042 | APPROVE da IN_REVIEW, ruolo MARKETING, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-043 | APPROVE da IN_REVIEW, ruolo LEGAL, regola senza approvatore (campagna sotto soglia) | `APPROVED` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-044 | APPROVE da IN_REVIEW, ruolo CARE, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-045 | APPROVE da IN_REVIEW, ruolo ANALYST, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-046 | REJECT da IN_REVIEW, ruolo ADMIN, regola senza approvatore (campagna sotto soglia) | `DRAFT` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-047 | REJECT da IN_REVIEW, ruolo MARKETING, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-048 | REJECT da IN_REVIEW, ruolo LEGAL, regola senza approvatore (campagna sotto soglia) | `DRAFT` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-049 | REJECT da IN_REVIEW, ruolo CARE, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-050 | REJECT da IN_REVIEW, ruolo ANALYST, regola senza approvatore (campagna sotto soglia) | 403 `FORBIDDEN_ROLE` | docs/08 §2 `object.approve` · docs/06 §7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-051 | SUBMIT da DRAFT, ruolo ADMIN, policy OFF | `LIVE` — AMBIGUO (LIVE) | docs/06 §7 · docs/08 §2 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-052 | SUBMIT da DRAFT, ruolo MARKETING, policy OFF | `LIVE` — AMBIGUO (LIVE) | docs/06 §7 · docs/08 §2 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-053 | SUBMIT da DRAFT, ruolo LEGAL, policy OFF | 403 `FORBIDDEN_ROLE` | docs/06 §7 · docs/08 §2 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-054 | SUBMIT da DRAFT, ruolo CARE, policy OFF | 403 `FORBIDDEN_ROLE` | docs/06 §7 · docs/08 §2 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-055 | SUBMIT da DRAFT, ruolo ANALYST, policy OFF | 403 `FORBIDDEN_ROLE` | docs/06 §7 · docs/08 §2 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-056 | MARKETING approva da DRAFT (ruolo e stato vietati) | 403 `FORBIDDEN_ROLE` — AMBIGUO (precedenza) | docs/06 §2 (nessuna precedenza tra 403/409/422) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-057 | LEGAL pubblica da ARCHIVED (ruolo e stato vietati) | 403 `FORBIDDEN_ROLE` — AMBIGUO (precedenza) | docs/06 §2 (nessuna precedenza tra 403/409/422) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-058 | ANALYST rifiuta senza commento (ruolo e commento vietati) | 403 `FORBIDDEN_ROLE` — AMBIGUO (precedenza) | docs/06 §2 (nessuna precedenza tra 403/409/422) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-059 | CARE pubblica da DRAFT con LEGAL richiesto | 403 `FORBIDDEN_ROLE` — AMBIGUO (precedenza) | docs/06 §2 (nessuna precedenza tra 403/409/422) | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-ROL-060 | LEGAL rifiuta da DRAFT senza commento (stato e commento vietati) | 409 `INVALID_TRANSITION` — AMBIGUO (precedenza) | docs/06 §2 (nessuna precedenza tra 403/409/422) | `TestbookGovTransitionsTest#stateMachine` |

### 6.2 Override di ADMIN (OVR)
Domini: decisione (`APPROVE`, `REJECT`) × regola (LEGAL, senza approvatore) × ruolo (5) = 20, **completa**; più due azioni
non decisionali di ADMIN. Con regola senza approvatore (campagna sotto soglia o policy spenta) la matrice di docs/08 §2
dice comunque «ADMIN ✓ (override)», ma non c'è un approvatore di policy da scavalcare: OVR-006, OVR-016 AMBIGUO (oggi
non marcato).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-OVR-001 | APPROVE, regola LEGAL, ruolo ADMIN | override marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-002 | APPROVE, regola LEGAL, ruolo MARKETING | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-003 | APPROVE, regola LEGAL, ruolo LEGAL | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-004 | APPROVE, regola LEGAL, ruolo CARE | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-005 | APPROVE, regola LEGAL, ruolo ANALYST | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-006 | APPROVE, regola senza approvatore, ruolo ADMIN | non marcato — AMBIGUO | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-007 | APPROVE, regola senza approvatore, ruolo MARKETING | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-008 | APPROVE, regola senza approvatore, ruolo LEGAL | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-009 | APPROVE, regola senza approvatore, ruolo CARE | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-010 | APPROVE, regola senza approvatore, ruolo ANALYST | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-011 | REJECT, regola LEGAL, ruolo ADMIN | override marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-012 | REJECT, regola LEGAL, ruolo MARKETING | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-013 | REJECT, regola LEGAL, ruolo LEGAL | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-014 | REJECT, regola LEGAL, ruolo CARE | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-015 | REJECT, regola LEGAL, ruolo ANALYST | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-016 | REJECT, regola senza approvatore, ruolo ADMIN | non marcato — AMBIGUO | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-017 | REJECT, regola senza approvatore, ruolo MARKETING | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-018 | REJECT, regola senza approvatore, ruolo LEGAL | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-019 | REJECT, regola senza approvatore, ruolo CARE | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-020 | REJECT, regola senza approvatore, ruolo ANALYST | non marcato | docs/08 §2 `object.approve` «ADMIN ✓ (override, marcato in audit)» | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-021 | PUBLISH di ADMIN (non è una decisione) | non marcato | docs/08 §2 (`object.edit`: ADMIN ✓ senza override) | `TestbookGovTransitionsTest#override` |
| TB-GOV-OVR-022 | SUBMIT di ADMIN (non è una decisione) | non marcato | docs/08 §2 (`object.edit`: ADMIN ✓ senza override) | `TestbookGovTransitionsTest#override` |

### 6.3 Commento del rifiuto (CMT)
Domini del commento: assente, vuoto, uno spazio, più spazi, tabulazione e a capo, spazio non separabile, 1 carattere,
testo con spazi ai bordi, 2000 caratteri, unicode; per `REJECT` (LEGAL e ADMIN) e `APPROVE` (commento facoltativo), più
un rifiuto a policy spenta. Un commento di soli U+00A0 non è «vuoto» per `String.isBlank()`: la specifica non definisce
«commento» ⇒ CMT-006 AMBIGUO.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-CMT-001 | REJECT da IN_REVIEW (LEGAL), commento assente | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-002 | REJECT da IN_REVIEW (LEGAL), commento vuoto | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-003 | REJECT da IN_REVIEW (LEGAL), un solo spazio | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-004 | REJECT da IN_REVIEW (LEGAL), solo spazi | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-005 | REJECT da IN_REVIEW (LEGAL), tabulazione e a capo | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-006 | REJECT da IN_REVIEW (LEGAL), solo spazio non separabile (U+00A0) | `DRAFT` — AMBIGUO | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-007 | REJECT da IN_REVIEW (LEGAL), un carattere | `DRAFT` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-008 | REJECT da IN_REVIEW (LEGAL), testo con spazi ai bordi | `DRAFT` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-009 | REJECT da IN_REVIEW (LEGAL), 2000 caratteri | `DRAFT` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-010 | REJECT da IN_REVIEW (LEGAL), unicode | `DRAFT` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-011 | REJECT da IN_REVIEW (ADMIN), ADMIN (override) senza commento | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-012 | APPROVE da IN_REVIEW (LEGAL), APPROVE senza commento | `APPROVED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-013 | APPROVE da IN_REVIEW (LEGAL), APPROVE con commento vuoto | `APPROVED` | docs/03 §3.6 «REJECT (commento obbligatorio)» · docs/12 M7 | `TestbookGovTransitionsTest#stateMachine` |
| TB-GOV-CMT-014 | REJECT da IN_REVIEW (LEGAL), policy OFF, commento assente | 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 | `TestbookGovTransitionsTest#stateMachine` |

## 7. Policy delle approvazioni (POL)
Domini: tipo di oggetto (4) × policy (ON/OFF); per `CAMPAIGN` anche `requiresLegal` (2) × budget (assente, 0, −1,
soglia − 1, soglia, soglia + 1, `Long.MAX_VALUE`) ⇒ 2 × 2 × 7 = 28, **completa**; soglia configurata (limiti 50 000 /
50 001); scheda policy di BO-21 per ogni tipo × ON/OFF (8, completa).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-POL-001 | CAMPAIGN, policy ON, `requiresLegal`=false, budget assente | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-002 | CAMPAIGN, policy ON, `requiresLegal`=false, budget 0 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-003 | CAMPAIGN, policy ON, `requiresLegal`=false, budget -1 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-004 | CAMPAIGN, policy ON, `requiresLegal`=false, budget 99999 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-005 | CAMPAIGN, policy ON, `requiresLegal`=false, budget 100000 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-006 | CAMPAIGN, policy ON, `requiresLegal`=false, budget 100001 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-007 | CAMPAIGN, policy ON, `requiresLegal`=false, budget Long.MAX_VALUE | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-008 | CAMPAIGN, policy ON, `requiresLegal`=true, budget assente | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-009 | CAMPAIGN, policy ON, `requiresLegal`=true, budget 0 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-010 | CAMPAIGN, policy ON, `requiresLegal`=true, budget -1 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-011 | CAMPAIGN, policy ON, `requiresLegal`=true, budget 99999 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-012 | CAMPAIGN, policy ON, `requiresLegal`=true, budget 100000 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-013 | CAMPAIGN, policy ON, `requiresLegal`=true, budget 100001 | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-014 | CAMPAIGN, policy ON, `requiresLegal`=true, budget Long.MAX_VALUE | richiesta, LEGAL | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-015 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget assente | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-016 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget 0 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-017 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget -1 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-018 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget 99999 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-019 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget 100000 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-020 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget 100001 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-021 | CAMPAIGN, policy OFF, `requiresLegal`=false, budget Long.MAX_VALUE | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-022 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget assente | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-023 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget 0 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-024 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget -1 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-025 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget 99999 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-026 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget 100000 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-027 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget 100001 | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-028 | CAMPAIGN, policy OFF, `requiresLegal`=true, budget Long.MAX_VALUE | non richiesta | docs/06 §7 · F-APR-02 · Q-08 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-029 | CONTEST, policy ON | richiesta, LEGAL | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-030 | CONTEST, policy OFF | non richiesta | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-031 | REWARD, policy ON | richiesta, LEGAL | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-032 | REWARD, policy OFF | non richiesta | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-033 | CONTENT, policy ON | non richiesta | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-034 | CONTENT, policy OFF | non richiesta | docs/06 §7 · F-APR-02 | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-035 | CAMPAIGN, soglia configurata a 50 000, budget 50000 | non richiesta | docs/06 §7 (configurazione `loyaltyhub.approval.policy`) | `TestbookGovPolicyTest#rule` |
| TB-GOV-POL-036 | CAMPAIGN, soglia configurata a 50 000, budget 50001 | richiesta, LEGAL | docs/06 §7 (configurazione `loyaltyhub.approval.policy`) | `TestbookGovPolicyTest#rule` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-POL-037 | `/v1/approvals/policy`: riga CONTEST, policy ON | approvatore LEGAL | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-038 | `/v1/approvals/policy`: riga REWARD, policy ON | approvatore LEGAL | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-039 | `/v1/approvals/policy`: riga CAMPAIGN, policy ON | approvatore LEGAL | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-040 | `/v1/approvals/policy`: riga CONTENT, policy ON | approvatore — | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-041 | `/v1/approvals/policy`: riga CONTEST, policy OFF | approvatore — | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-042 | `/v1/approvals/policy`: riga REWARD, policy OFF | approvatore — | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-043 | `/v1/approvals/policy`: riga CAMPAIGN, policy OFF | approvatore — | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |
| TB-GOV-POL-044 | `/v1/approvals/policy`: riga CONTENT, policy OFF | approvatore — | docs/06 §7 · BO-21 · Q-96 | `TestbookGovPolicyTest#rows` |

## 8. Stati del membro e guardie di member-service

### 8.1 Cambio di stato (MST)
Domini: stato di partenza (`ACTIVE, INACTIVE, BLOCKED, ANONYMIZED`) × destinazione (i 3 ammessi da member §3, `ANONYMIZED`,
`CLOSED` — presente solo nell'enum del codice e nei contratti —, un valore sconosciuto, assente, minuscolo) = 4 × 8 = 32
≤ 64 ⇒ **tabella completa**, attore ADMIN, motivo compilato. Esito: HTTP, stato risultante (o `code`) e numero di fatti
`member.status.changed` scritti. Poi motivo (Q-138), audit, contratto (Q-139), membro inesistente, corpo assente, filtri
dell'elenco. Q-137 riguarda solo l'interfaccia: l'API ammette ogni coppia tra i tre stati.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-MST-001 | membro ACTIVE, `POST …/status` con `status` = `ACTIVE` (ADMIN) | 200, stato `ACTIVE`, 0 fatto `member.status.changed` — AMBIGUO (stesso stato: nessun fatto) | F-MBR-04 · member §3 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-002 | membro ACTIVE, `POST …/status` con `status` = `INACTIVE` (ADMIN) | 200, stato `INACTIVE`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-003 | membro ACTIVE, `POST …/status` con `status` = `BLOCKED` (ADMIN) | 200, stato `BLOCKED`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-004 | membro ACTIVE, `POST …/status` con `status` = `ANONYMIZED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) · F-MBR-05 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-005 | membro ACTIVE, `POST …/status` con `status` = `CLOSED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-006 | membro ACTIVE, `POST …/status` con `status` = `SOSPESO` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-007 | membro ACTIVE, `POST …/status` con `status` = assente (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-008 | membro ACTIVE, `POST …/status` con `status` = `blocked` (ADMIN) | 200, stato `BLOCKED`, 1 fatto `member.status.changed` — AMBIGUO (valore in minuscolo) | docs/06 §2 (enum UPPER_SNAKE) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-009 | membro INACTIVE, `POST …/status` con `status` = `ACTIVE` (ADMIN) | 200, stato `ACTIVE`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-010 | membro INACTIVE, `POST …/status` con `status` = `INACTIVE` (ADMIN) | 200, stato `INACTIVE`, 0 fatto `member.status.changed` — AMBIGUO (stesso stato: nessun fatto) | F-MBR-04 · member §3 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-011 | membro INACTIVE, `POST …/status` con `status` = `BLOCKED` (ADMIN) | 200, stato `BLOCKED`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-012 | membro INACTIVE, `POST …/status` con `status` = `ANONYMIZED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) · F-MBR-05 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-013 | membro INACTIVE, `POST …/status` con `status` = `CLOSED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-014 | membro INACTIVE, `POST …/status` con `status` = `SOSPESO` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-015 | membro INACTIVE, `POST …/status` con `status` = assente (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-016 | membro INACTIVE, `POST …/status` con `status` = `blocked` (ADMIN) | 200, stato `BLOCKED`, 1 fatto `member.status.changed` — AMBIGUO (valore in minuscolo) | docs/06 §2 (enum UPPER_SNAKE) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-017 | membro BLOCKED, `POST …/status` con `status` = `ACTIVE` (ADMIN) | 200, stato `ACTIVE`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-018 | membro BLOCKED, `POST …/status` con `status` = `INACTIVE` (ADMIN) | 200, stato `INACTIVE`, 1 fatto `member.status.changed` | F-MBR-04 · member §3, §4 · Q-137 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-019 | membro BLOCKED, `POST …/status` con `status` = `BLOCKED` (ADMIN) | 200, stato `BLOCKED`, 0 fatto `member.status.changed` — AMBIGUO (stesso stato: nessun fatto) | F-MBR-04 · member §3 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-020 | membro BLOCKED, `POST …/status` con `status` = `ANONYMIZED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) · F-MBR-05 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-021 | membro BLOCKED, `POST …/status` con `status` = `CLOSED` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-022 | membro BLOCKED, `POST …/status` con `status` = `SOSPESO` (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-023 | membro BLOCKED, `POST …/status` con `status` = assente (ADMIN) | 400 `BAD_REQUEST`, 0 fatti | member §3 (`BLOCKED/INACTIVE/ACTIVE`) · docs/06 §2 (400) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-024 | membro BLOCKED, `POST …/status` con `status` = `blocked` (ADMIN) | 200, stato `BLOCKED`, 0 fatto `member.status.changed` — AMBIGUO (valore in minuscolo) | docs/06 §2 (enum UPPER_SNAKE) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-025 | membro ANONYMIZED, `POST …/status` con `status` = `ACTIVE` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-026 | membro ANONYMIZED, `POST …/status` con `status` = `INACTIVE` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-027 | membro ANONYMIZED, `POST …/status` con `status` = `BLOCKED` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-028 | membro ANONYMIZED, `POST …/status` con `status` = `ANONYMIZED` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti — AMBIGUO (precedenza 409/400) | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-029 | membro ANONYMIZED, `POST …/status` con `status` = `CLOSED` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti — AMBIGUO (precedenza 409/400) | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-030 | membro ANONYMIZED, `POST …/status` con `status` = `SOSPESO` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti — AMBIGUO (precedenza 409/400) | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-031 | membro ANONYMIZED, `POST …/status` con `status` = assente (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti — AMBIGUO (precedenza 409/400) | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-032 | membro ANONYMIZED, `POST …/status` con `status` = `blocked` (ADMIN) | 409 `MEMBER_ANONYMIZED`, 0 fatti | docs/03 §2 (ANONYMIZED irreversibile) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-033 | motivo assente → fatto con motivo vuoto | `vuoto` | Q-138 · contratto `member.status.changed` | `TestbookGovMemberIT#status` |
| TB-GOV-MST-034 | motivo compilato → nel fatto e nell'audit | `Sospetta frode\|audit` | Q-138 · F-AUD-01 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-035 | audit TRANSITION con prima/dopo e attore | `TRANSITION\|ACTIVE\|BLOCKED\|CARE:paolo.care` | F-AUD-01 · docs/06 §3 (audit con l'attore) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-036 | fatto conforme al contratto (INACTIVE) | `valido` | docs/05 · contracts/events/fact/member.status.changed · Q-139 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-037 | membro inesistente | `404:NOT_FOUND` | docs/06 §2 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-038 | corpo assente | `400:BAD_REQUEST` | docs/06 §2 (JSON malformato) | `TestbookGovMemberIT#status` |
| TB-GOV-MST-039 | filtro elenco status=SOSPESO | `400:BAD_REQUEST` | F-MBR-01 · docs/06 §2 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-040 | filtro elenco status=CLOSED | `200` — AMBIGUO (CLOSED: nei contratti ma non in docs/03) | docs/03 §2 (4 stati) · contratti (CLOSED ammesso) · Q-139 | `TestbookGovMemberIT#status` |
| TB-GOV-MST-041 | filtro elenco status=BLOCKED | `200:MBR-000008` | F-MBR-01 · docs/10 §2 (Roberto) | `TestbookGovMemberIT#status` |

### 8.2 Guardie per ruolo degli endpoint (MRL)
Domini: endpoint di member-service (12, raggruppati per capacità di docs/08 §2) × attore (5 ruoli + intestazione assente)
= 72, **completa**; più due intestazioni non canoniche. Le celle «—» senza ● di ruoli diversi da ANALYST (es. MARKETING su
`member.write`) non hanno un rifiuto imposto dal backend (docs/08 §2: la UI nasconde, il 403 è obbligatorio solo con ● e
per ogni scrittura di ANALYST): sono AMBIGUO e asseriscono il 403 attuale. `POST /v1/members` resta aperto per la scelta
registrata Q-157 (serve anche alla registrazione dal portale).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-MRL-001 | `POST /v1/members/{id}/status` (member.write), ADMIN | accettata (2xx) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-002 | `POST /v1/members/{id}/status` (member.write), MARKETING | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-003 | `POST /v1/members/{id}/status` (member.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-004 | `POST /v1/members/{id}/status` (member.write), CARE | accettata (2xx) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-005 | `POST /v1/members/{id}/status` (member.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-006 | `POST /v1/members/{id}/status` (member.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-007 | `PATCH /v1/members/{id}` (member.write), ADMIN | accettata (2xx) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-008 | `PATCH /v1/members/{id}` (member.write), MARKETING | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-009 | `PATCH /v1/members/{id}` (member.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-010 | `PATCH /v1/members/{id}` (member.write), CARE | accettata (2xx) | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-011 | `PATCH /v1/members/{id}` (member.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-012 | `PATCH /v1/members/{id}` (member.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `member.write` · docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-013 | `POST /v1/members` (member.write, anche portale), ADMIN | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-014 | `POST /v1/members` (member.write, anche portale), MARKETING | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-015 | `POST /v1/members` (member.write, anche portale), LEGAL | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-016 | `POST /v1/members` (member.write, anche portale), CARE | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-017 | `POST /v1/members` (member.write, anche portale), ANALYST | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-018 | `POST /v1/members` (member.write, anche portale), intestazione assente | accettata (2xx) | Q-157 (endpoint aperto) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-019 | `PUT /v1/attribute-definitions` (segment.write), ADMIN | accettata (2xx) | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-020 | `PUT /v1/attribute-definitions` (segment.write), MARKETING | accettata (2xx) | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-021 | `PUT /v1/attribute-definitions` (segment.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-022 | `PUT /v1/attribute-definitions` (segment.write), CARE | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-023 | `PUT /v1/attribute-definitions` (segment.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-024 | `PUT /v1/attribute-definitions` (segment.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` · Q-94 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-025 | `POST /v1/segments` (segment.write), ADMIN | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-026 | `POST /v1/segments` (segment.write), MARKETING | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-027 | `POST /v1/segments` (segment.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-028 | `POST /v1/segments` (segment.write), CARE | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-029 | `POST /v1/segments` (segment.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-030 | `POST /v1/segments` (segment.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-031 | `PUT /v1/segments/{id}` (segment.write), ADMIN | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-032 | `PUT /v1/segments/{id}` (segment.write), MARKETING | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-033 | `PUT /v1/segments/{id}` (segment.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-034 | `PUT /v1/segments/{id}` (segment.write), CARE | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-035 | `PUT /v1/segments/{id}` (segment.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-036 | `PUT /v1/segments/{id}` (segment.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-037 | `POST /v1/segments/{id}/refresh` (segment.write), ADMIN | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-038 | `POST /v1/segments/{id}/refresh` (segment.write), MARKETING | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-039 | `POST /v1/segments/{id}/refresh` (segment.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-040 | `POST /v1/segments/{id}/refresh` (segment.write), CARE | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-041 | `POST /v1/segments/{id}/refresh` (segment.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-042 | `POST /v1/segments/{id}/refresh` (segment.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-043 | `PUT /v1/segments/{id}/members` (segment.write), ADMIN | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-044 | `PUT /v1/segments/{id}/members` (segment.write), MARKETING | accettata (2xx) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-045 | `PUT /v1/segments/{id}/members` (segment.write), LEGAL | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-046 | `PUT /v1/segments/{id}/members` (segment.write), CARE | 403 `FORBIDDEN_ROLE` — AMBIGUO (rifiuto non imposto senza ●) | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-047 | `PUT /v1/segments/{id}/members` (segment.write), ANALYST | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-048 | `PUT /v1/segments/{id}/members` (segment.write), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/08 §2 `segment.write` | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-049 | `POST /v1/segments/preview` (lettura), ADMIN | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-050 | `POST /v1/segments/preview` (lettura), MARKETING | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-051 | `POST /v1/segments/preview` (lettura), LEGAL | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-052 | `POST /v1/segments/preview` (lettura), CARE | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-053 | `POST /v1/segments/preview` (lettura), ANALYST | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-054 | `POST /v1/segments/preview` (lettura), intestazione assente | accettata (2xx) | member §3 (anteprima senza salvare) · docs/08 §2 (lettura) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-055 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), ADMIN | accettata (2xx) | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-056 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), MARKETING | 403 `FORBIDDEN_ROLE` | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-057 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), LEGAL | 403 `FORBIDDEN_ROLE` | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-058 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), CARE | 403 `FORBIDDEN_ROLE` | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-059 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), ANALYST | 403 `FORBIDDEN_ROLE` | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-060 | `POST /v1/demo/jobs/refresh-segments` (demo.admin ●), intestazione assente | 403 `FORBIDDEN_ROLE` | docs/06 §3 (`/v1/demo/**` ⇒ ADMIN) · docs/08 §2 `demo.admin` ● | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-061 | `GET /v1/members` (lettura), ADMIN | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-062 | `GET /v1/members` (lettura), MARKETING | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-063 | `GET /v1/members` (lettura), LEGAL | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-064 | `GET /v1/members` (lettura), CARE | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-065 | `GET /v1/members` (lettura), ANALYST | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-066 | `GET /v1/members` (lettura), intestazione assente | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-067 | `GET /v1/members/{id}` (lettura), ADMIN | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-068 | `GET /v1/members/{id}` (lettura), MARKETING | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-069 | `GET /v1/members/{id}` (lettura), LEGAL | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-070 | `GET /v1/members/{id}` (lettura), CARE | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-071 | `GET /v1/members/{id}` (lettura), ANALYST | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-072 | `GET /v1/members/{id}` (lettura), intestazione assente | accettata (2xx) | docs/08 §2 (lettura per tutti) | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-073 | `POST /v1/members/{id}/status`, `X-LH-Actor: ADMINISTRATOR:x` | 403 `FORBIDDEN_ROLE` — AMBIGUO (ruolo sconosciuto) | docs/06 §3 | `TestbookGovMemberIT#roles` |
| TB-GOV-MRL-074 | `POST /v1/members/{id}/status`, `X-LH-Actor: care:paolo.care` | accettata (2xx) — AMBIGUO (ruolo in minuscolo) | docs/06 §3 | `TestbookGovMemberIT#roles` |

## 9. Anonimizzazione (ANO)
Domini: conferma (uguale all'id, con spazi, minuscola, altro id, vuota, assente, nessun corpo) × stato di partenza
(ACTIVE, INACTIVE, BLOCKED, già ANONYMIZED) × ruolo (5 + assente) — ogni classe provata da sola (guasto singolo) sul
caso valido; membro inesistente (anche con conferma errata: precedenza AMBIGUO). Poi **campo per campo** su un membro
completo (nome, cognome, e-mail, telefono, nascita, genere, città, id esterno, attributi, consensi, avatar, etichette,
invito, referral, iscrizione, proiezione dei saldi) anonimizzato una volta: cosa si cancella (docs/03 §2, Q-120, Q-121,
Q-122) e cosa resta; fatti e audit senza dati personali e conformi ai contratti; ricerca, portale, e-mail di nuovo
libera, irreversibilità, codice invito (Q-61). La propagazione negli altri servizi è in §12.4 (ANX).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ANO-001 | conferma = id | `200:ANONYMIZED` | F-MBR-05 · member §3 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-002 | conferma con spazi ai bordi | `200:ANONYMIZED` — AMBIGUO (conferma con spazi) | member §3 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-003 | conferma in minuscolo | `422:CONFIRM_MISMATCH` | member §3 · docs/08 §3.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-004 | conferma con l'id di un altro membro | `422:CONFIRM_MISMATCH` | member §3 · docs/08 §3.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-005 | conferma vuota | `422:CONFIRM_MISMATCH` | member §3 · docs/08 §3.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-006 | corpo senza conferma | `422:CONFIRM_MISMATCH` | member §3 · docs/08 §3.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-007 | nessun corpo | `422:CONFIRM_MISMATCH` | member §3 · docs/08 §3.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-008 | seconda anonimizzazione | `409:MEMBER_ANONYMIZED` | docs/03 §2 (irreversibile) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-009 | membro inesistente | `404:NOT_FOUND` | docs/06 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-010 | membro inesistente e conferma errata | `404:NOT_FOUND` — AMBIGUO (precedenza 404/422) | docs/06 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-011 | da INACTIVE | `200:ANONYMIZED:INACTIVE` | F-MBR-05 · member §4 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-012 | da BLOCKED | `200:ANONYMIZED:BLOCKED` | F-MBR-05 · member §4 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-013 | ruolo MARKETING | `403:FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` ● | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-014 | ruolo LEGAL | `403:FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` ● | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-015 | ruolo CARE | `403:FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` ● | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-016 | ruolo ANALYST | `403:FORBIDDEN_ROLE` | docs/08 §2 `member.anonymize` ● | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-017 | intestazione assente | `403:FORBIDDEN_ROLE` | docs/06 §3 · docs/08 §2 ● | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-018 | membro del seed già anonimizzato (MBR-000012) | `409:MEMBER_ANONYMIZED` | docs/10 §2 · docs/03 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-019 | nickname → segnaposto | `Membro anonimo` | docs/03 §2 · Q-120 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-020 | nome → null | `null` | Q-120 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-021 | cognome → null | `null` | Q-120 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-022 | e-mail → null | `null` | docs/03 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-023 | telefono → null | `null` | docs/03 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-024 | data di nascita → null | `null` | Q-122 (`birthDate`) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-025 | genere → null | `null` | Q-122 (`gender`) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-026 | città → null | `null` | Q-122 (`city`) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-027 | identificativo esterno → null | `null` | Q-122 (`externalId`) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-028 | attributi cancellati | `{}` | Q-121 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-029 | consensi cancellati | `{}` | Q-121 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-030 | seme dell'avatar → null | `null` | Q-122 (`avatarSeed`) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-031 | id conservato | `uguale` | docs/03 §2 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-032 | etichette conservate | `[vip]` | Q-121 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-033 | codice invito conservato | `uguale` | Q-121 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-034 | legame di referral conservato | `uguale` | Q-121 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-035 | iscrizione e canale conservati | `uguale` | docs/03 §2 (statistiche) · Q-122 (non personali) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-036 | proiezione dei saldi conservata | `1234` | docs/03 §2 (movimenti e statistiche restano) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-037 | fatto member.status.changed | `ACTIVE→ANONYMIZED` | member §4 · docs/12 M7.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-038 | fatto member.updated ripulito dopo lo stato | `ANONYMIZED\|senza dati personali` | member §5 · docs/12 M7.5 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-039 | audit senza dati personali | `TRANSITION\|senza dati personali` | F-AUD-01 · docs/12 M7 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-040 | fatti conformi ai contratti | `validi` | docs/05 · contracts/events/fact | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-041 | ricerca per nome | `0` | docs/12 M7 (nessuna API espone il nome) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-042 | ricerca per e-mail | `0` | docs/12 M7 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-043 | ricerca per id | `1:Membro anonimo` | F-MBR-01 · Q-120 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-044 | profilo del portale | `senza dati personali` | docs/12 M7 | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-045 | e-mail di nuovo libera | `201` | docs/03 §2 (e-mail univoca se presente) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-046 | modifica dopo l'anonimizzazione | `409:MEMBER_ANONYMIZED` | docs/03 §2 (irreversibile) | `TestbookGovMemberIT#anonymize` |
| TB-GOV-ANO-047 | codice invito di un anonimizzato | `422:REFERRAL_CODE_INVALID` | Q-61 | `TestbookGovMemberIT#anonymize` |

## 10. Attributi personalizzati

### 10.1 Valore × tipo (ATV)
Domini: tipo della definizione (4) × classe del valore (16: testo, vuoto, spazi, 200/201 caratteri, intero, decimale,
negativo, booleano, booleano come testo, data, 29 febbraio bisestile e non, data con ora, `null`, elenco) = 64 ⇒
**tabella completa**; più opzioni (ammessa, minuscola, fuori elenco), chiave non definita, chiave interna, `attributes`
non oggetto. La specifica fissa solo i quattro tipi: vuoto, lunghezza massima, data con ora e `null` che rimuove sono
AMBIGUO.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ATV-001 | attributo STRING, valore `"Milano"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-002 | attributo STRING, valore `""` | 422 `MEMBER_INVALID` sul campo — AMBIGUO (testo vuoto) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-003 | attributo STRING, valore `"   "` | 422 `MEMBER_INVALID` sul campo — AMBIGUO (testo vuoto) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-004 | attributo STRING, valore 200 caratteri | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-005 | attributo STRING, valore 201 caratteri | 422 `MEMBER_INVALID` sul campo — AMBIGUO (lunghezza massima) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-006 | attributo STRING, valore `3` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-007 | attributo STRING, valore `2.5` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-008 | attributo STRING, valore `-1` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-009 | attributo STRING, valore `true` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-010 | attributo STRING, valore `"true"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-011 | attributo STRING, valore `"2026-02-28"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-012 | attributo STRING, valore `"2028-02-29"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-013 | attributo STRING, valore `"2026-02-29"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-014 | attributo STRING, valore `"2026-02-28T10:00:00Z"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-015 | attributo STRING, valore `null` | ammesso — AMBIGUO (null rimuove la chiave) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-016 | attributo STRING, valore `[1]` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-017 | attributo NUMBER, valore `"Milano"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-018 | attributo NUMBER, valore `""` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-019 | attributo NUMBER, valore `"   "` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-020 | attributo NUMBER, valore 200 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-021 | attributo NUMBER, valore 201 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-022 | attributo NUMBER, valore `3` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-023 | attributo NUMBER, valore `2.5` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-024 | attributo NUMBER, valore `-1` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-025 | attributo NUMBER, valore `true` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-026 | attributo NUMBER, valore `"true"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-027 | attributo NUMBER, valore `"2026-02-28"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-028 | attributo NUMBER, valore `"2028-02-29"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-029 | attributo NUMBER, valore `"2026-02-29"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-030 | attributo NUMBER, valore `"2026-02-28T10:00:00Z"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-031 | attributo NUMBER, valore `null` | ammesso — AMBIGUO (null rimuove la chiave) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-032 | attributo NUMBER, valore `[1]` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-033 | attributo BOOLEAN, valore `"Milano"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-034 | attributo BOOLEAN, valore `""` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-035 | attributo BOOLEAN, valore `"   "` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-036 | attributo BOOLEAN, valore 200 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-037 | attributo BOOLEAN, valore 201 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-038 | attributo BOOLEAN, valore `3` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-039 | attributo BOOLEAN, valore `2.5` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-040 | attributo BOOLEAN, valore `-1` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-041 | attributo BOOLEAN, valore `true` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-042 | attributo BOOLEAN, valore `"true"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-043 | attributo BOOLEAN, valore `"2026-02-28"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-044 | attributo BOOLEAN, valore `"2028-02-29"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-045 | attributo BOOLEAN, valore `"2026-02-29"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-046 | attributo BOOLEAN, valore `"2026-02-28T10:00:00Z"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-047 | attributo BOOLEAN, valore `null` | ammesso — AMBIGUO (null rimuove la chiave) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-048 | attributo BOOLEAN, valore `[1]` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-049 | attributo DATE, valore `"Milano"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-050 | attributo DATE, valore `""` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-051 | attributo DATE, valore `"   "` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-052 | attributo DATE, valore 200 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-053 | attributo DATE, valore 201 caratteri | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-054 | attributo DATE, valore `3` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-055 | attributo DATE, valore `2.5` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-056 | attributo DATE, valore `-1` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-057 | attributo DATE, valore `true` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-058 | attributo DATE, valore `"true"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-059 | attributo DATE, valore `"2026-02-28"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-060 | attributo DATE, valore `"2028-02-29"` | ammesso | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-061 | attributo DATE, valore `"2026-02-29"` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-062 | attributo DATE, valore `"2026-02-28T10:00:00Z"` | 422 `MEMBER_INVALID` sul campo — AMBIGUO (data con ora) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-063 | attributo DATE, valore `null` | ammesso — AMBIGUO (null rimuove la chiave) | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-064 | attributo DATE, valore `[1]` | 422 `MEMBER_INVALID` sul campo | docs/03 §2 (string, number, boolean, date) · F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-065 | STRING con opzioni: valore ammesso: `"APP"` | ammesso | member §2 `options` | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-066 | STRING con opzioni: minuscolo: `"app"` | 422 `MEMBER_INVALID` | member §2 `options` | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-067 | STRING con opzioni: fuori elenco: `"TV"` | 422 `MEMBER_INVALID` | member §2 `options` | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-068 | chiave non definita: `"x"` | 422 `MEMBER_INVALID` | F-MBR-03 (coppie tipizzate) | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-069 | chiave interna story: `"x"` | 422 `MEMBER_INVALID` — AMBIGUO (chiavi interne della demo) | F-MBR-03 | `TestbookGovAttributesTest#value` |
| TB-GOV-ATV-070 | attributes non oggetto: `[1]` | 422 `MEMBER_INVALID` | F-MBR-03 | `TestbookGovAttributesTest#value` |

### 10.2 Definizioni (ATD)
Domini: chiave (valida, 1/2/40/41 caratteri, maiuscola, `_`, cifra iniziale, `story`, assente, duplicata), etichetta
(assente, vuota, spazi, 60/61), tipo (4 ammessi, minuscolo, sconosciuto, assente), opzioni (per STRING, per NUMBER, vuota,
duplicata), numero di definizioni (30/31): ogni classe da sola sul resto valido. member §2 fissa `key` come chiave
primaria e i quattro tipi; formati, lunghezze e tetti sono del codice (AMBIGUO).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ATD-001 | chiave `householdSize`, etichetta Componenti, tipo NUMBER | valida | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-002 | chiave `a`, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-003 | chiave `ab`, etichetta Etichetta, tipo STRING | valida — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-004 | chiave 40 caratteri, etichetta Etichetta, tipo STRING | valida — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-005 | chiave 41 caratteri, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-006 | chiave `Household`, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-007 | chiave `house_size`, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-008 | chiave `1abc`, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (formato della chiave) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-009 | chiave `story`, etichetta Storia, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` — AMBIGUO (chiavi interne della demo) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-010 | chiave assente, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` | member §2 (`key` PK) | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-011 | chiave `tDup` due volte, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `key` | member §2 (`key` PK) | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-012 | chiave `tLabel`, etichetta NULL, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `label` — AMBIGUO (etichetta obbligatoria) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-013 | chiave `tLabel`, etichetta EMPTY, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `label` — AMBIGUO (etichetta obbligatoria) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-014 | chiave `tLabel`, etichetta SPACES, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `label` — AMBIGUO (etichetta obbligatoria) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-015 | chiave `tLabel`, etichetta L60, tipo STRING | valida — AMBIGUO (lunghezza dell'etichetta) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-016 | chiave `tLabel`, etichetta L61, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `label` — AMBIGUO (lunghezza dell'etichetta) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-017 | chiave `tType`, etichetta Tipo, tipo STRING | valida | member §2 · docs/03 §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-018 | chiave `tType`, etichetta Tipo, tipo NUMBER | valida | member §2 · docs/03 §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-019 | chiave `tType`, etichetta Tipo, tipo BOOLEAN | valida | member §2 · docs/03 §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-020 | chiave `tType`, etichetta Tipo, tipo DATE | valida | member §2 · docs/03 §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-021 | chiave `tType`, etichetta Tipo, tipo string | 422 `ATTRIBUTE_DEFINITION_INVALID` su `type` | docs/06 §2 (enum UPPER_SNAKE) | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-022 | chiave `tType`, etichetta Tipo, tipo TEXT | 422 `ATTRIBUTE_DEFINITION_INVALID` su `type` | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-023 | chiave `tType`, etichetta Tipo, tipo NULL | 422 `ATTRIBUTE_DEFINITION_INVALID` su `type` | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-024 | chiave `tOpt`, etichetta Opzioni, tipo STRING, opzioni A\|B | valida | member §2 `options` | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-025 | chiave `tOpt`, etichetta Opzioni, tipo NUMBER, opzioni 1\|2 | 422 `ATTRIBUTE_DEFINITION_INVALID` su `options` — AMBIGUO (opzioni solo per STRING) | member §2 `options` | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-026 | chiave `tOpt`, etichetta Opzioni, tipo STRING, opzioni A\|EMPTY | 422 `ATTRIBUTE_DEFINITION_INVALID` su `options` — AMBIGUO (opzione vuota) | member §2 `options` | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-027 | chiave `tOpt`, etichetta Opzioni, tipo STRING, opzioni A\|A | 422 `ATTRIBUTE_DEFINITION_INVALID` su `options` — AMBIGUO (opzioni duplicate) | member §2 `options` | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-028 | chiave 30 chiavi valide, etichetta Etichetta, tipo STRING | valida — AMBIGUO (numero massimo) | member §2 | `TestbookGovAttributesTest#definition` |
| TB-GOV-ATD-029 | chiave 31 chiavi valide, etichetta Etichetta, tipo STRING | 422 `ATTRIBUTE_DEFINITION_INVALID` su `definitions` — AMBIGUO (numero massimo) | member §2 | `TestbookGovAttributesTest#definition` |

### 10.3 Chiavi in uso e API (ATU)
Q-93: una chiave con valori sui membri non si toglie né cambia tipo (409 `ATTRIBUTE_IN_USE`). Domini: operazione
(togliere, cambiare tipo, cambiare etichetta, restringere le opzioni) × uso (con valori, senza, valori azzerati, solo
su un anonimizzato) — le celle significative; poi gli esiti via API della validazione (422, audit, `MEMBER_INVALID`).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-ATU-001 | togliere una chiave con valori | `409:ATTRIBUTE_IN_USE` | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-002 | cambiare tipo a una chiave con valori | `409:ATTRIBUTE_IN_USE` | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-003 | togliere una chiave senza valori | `200` | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-004 | cambiare tipo a una chiave senza valori | `200` | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-005 | cambiare etichetta a una chiave con valori | `200` | Q-93 (solo rimozione e tipo) | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-006 | restringere le opzioni escludendo un valore presente | `200` — AMBIGUO (valori fuori dalle nuove opzioni) | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-007 | togliere dopo aver azzerato i valori | `200` | Q-93 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-008 | togliere una chiave usata solo da un anonimizzato | `200` | Q-93 · Q-121 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-009 | chiave con spazi ai bordi | `200:tbTrim` — AMBIGUO (spazi tolti in silenzio) | F-MBR-03 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-010 | definizione non valida via API | `422:ATTRIBUTE_DEFINITION_INVALID` | F-MBR-03 · docs/06 §2 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-011 | definizione senza tipo via API | `422:ATTRIBUTE_DEFINITION_INVALID` | F-MBR-03 · docs/06 §2 (422, non 500) | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-012 | audit della sostituzione | `UPDATE` | F-AUD-01 · docs/06 §3 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-013 | valore del tipo sbagliato via API | `422:MEMBER_INVALID:attributes.tbNum` | F-MBR-03 · docs/06 §2 | `TestbookGovMemberIT#attributes` |
| TB-GOV-ATU-014 | chiave non definita via API | `422:MEMBER_INVALID:attributes.tbNope` | F-MBR-03 | `TestbookGovMemberIT#attributes` |

## 11. Segmenti

### 11.1 Criteri: comparatori × tipi e campi (CRT)
Domini: comparatore (14) × tipo dell'attributo (STRING, NUMBER, BOOLEAN, DATE) = 56 ≤ 64 ⇒ **tabella completa**, un
valore per cella scelto per rendere la foglia vera quando i tipi sono compatibili; negazioni con tipo incompatibile (4);
attributo assente × 14 comparatori; liste (etichette, anche vuote); ogni campo dello spazio esteso di docs/03 §10 con i
limiti (soglia, mezzanotte di Roma, cambio d'ora del 29/03/2026, 29 febbraio); gruppi `all/any/not` e annidati; criteri
vuoti o `null`. Oracolo: docs/03 §3.3 «campo assente → falsa (tranne `nexists`); tipi incompatibili → falsa»; sulle date
Q-91 (solo `eq`), su `not` Q-90.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-CRT-001 | `{"field":"member.attributes.tStr","cmp":"eq","value":"APP"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-002 | `{"field":"member.attributes.tStr","cmp":"neq","value":"WEB"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-003 | `{"field":"member.attributes.tStr","cmp":"gt","value":1}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-004 | `{"field":"member.attributes.tStr","cmp":"gte","value":1}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-005 | `{"field":"member.attributes.tStr","cmp":"lt","value":1}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-006 | `{"field":"member.attributes.tStr","cmp":"lte","value":1}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-007 | `{"field":"member.attributes.tStr","cmp":"in","value":["APP","WEB"]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-008 | `{"field":"member.attributes.tStr","cmp":"nin","value":["WEB"]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-009 | `{"field":"member.attributes.tStr","cmp":"contains","value":"PP"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-010 | `{"field":"member.attributes.tStr","cmp":"ncontains","value":"X"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-011 | `{"field":"member.attributes.tStr","cmp":"exists"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-012 | `{"field":"member.attributes.tStr","cmp":"nexists"}` | falso | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-013 | `{"field":"member.attributes.tStr","cmp":"between","value":[1,5]}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-014 | `{"field":"member.attributes.tStr","cmp":"startsWith","value":"AP"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-015 | `{"field":"member.attributes.tNum","cmp":"eq","value":3}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-016 | `{"field":"member.attributes.tNum","cmp":"neq","value":4}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-017 | `{"field":"member.attributes.tNum","cmp":"gt","value":2}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-018 | `{"field":"member.attributes.tNum","cmp":"gte","value":3}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-019 | `{"field":"member.attributes.tNum","cmp":"lt","value":4}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-020 | `{"field":"member.attributes.tNum","cmp":"lte","value":3}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-021 | `{"field":"member.attributes.tNum","cmp":"in","value":[1,3]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-022 | `{"field":"member.attributes.tNum","cmp":"nin","value":[1,2]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-023 | `{"field":"member.attributes.tNum","cmp":"contains","value":3}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-024 | `{"field":"member.attributes.tNum","cmp":"ncontains","value":3}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-025 | `{"field":"member.attributes.tNum","cmp":"exists"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-026 | `{"field":"member.attributes.tNum","cmp":"nexists"}` | falso | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-027 | `{"field":"member.attributes.tNum","cmp":"between","value":[1,5]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-028 | `{"field":"member.attributes.tNum","cmp":"startsWith","value":"3"}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-029 | `{"field":"member.attributes.tBool","cmp":"eq","value":true}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-030 | `{"field":"member.attributes.tBool","cmp":"neq","value":false}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-031 | `{"field":"member.attributes.tBool","cmp":"gt","value":0}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-032 | `{"field":"member.attributes.tBool","cmp":"gte","value":0}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-033 | `{"field":"member.attributes.tBool","cmp":"lt","value":2}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-034 | `{"field":"member.attributes.tBool","cmp":"lte","value":2}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-035 | `{"field":"member.attributes.tBool","cmp":"in","value":[true]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-036 | `{"field":"member.attributes.tBool","cmp":"nin","value":[false]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-037 | `{"field":"member.attributes.tBool","cmp":"contains","value":true}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-038 | `{"field":"member.attributes.tBool","cmp":"ncontains","value":true}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-039 | `{"field":"member.attributes.tBool","cmp":"exists"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-040 | `{"field":"member.attributes.tBool","cmp":"nexists"}` | falso | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-041 | `{"field":"member.attributes.tBool","cmp":"between","value":[0,1]}` | falso | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-042 | `{"field":"member.attributes.tBool","cmp":"startsWith","value":"t"}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-043 | `{"field":"member.attributes.tDate","cmp":"eq","value":"2026-02-28"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-044 | `{"field":"member.attributes.tDate","cmp":"neq","value":"2026-03-01"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-045 | `{"field":"member.attributes.tDate","cmp":"gt","value":"2026-01-01"}` | falso — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-046 | `{"field":"member.attributes.tDate","cmp":"gte","value":"2026-02-28"}` | falso — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-047 | `{"field":"member.attributes.tDate","cmp":"lt","value":"2026-12-31"}` | falso — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-048 | `{"field":"member.attributes.tDate","cmp":"lte","value":"2026-02-28"}` | falso — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-049 | `{"field":"member.attributes.tDate","cmp":"in","value":["2026-02-28"]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-050 | `{"field":"member.attributes.tDate","cmp":"nin","value":["2026-03-01"]}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-051 | `{"field":"member.attributes.tDate","cmp":"contains","value":"2026-02"}` | vero — AMBIGUO (data trattata come testo) | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-052 | `{"field":"member.attributes.tDate","cmp":"ncontains","value":"X"}` | vero — AMBIGUO (data trattata come testo) | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-053 | `{"field":"member.attributes.tDate","cmp":"exists"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-054 | `{"field":"member.attributes.tDate","cmp":"nexists"}` | falso | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-055 | `{"field":"member.attributes.tDate","cmp":"between","value":["2026-01-01","2026-12-31"]}` | falso — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-056 | `{"field":"member.attributes.tDate","cmp":"startsWith","value":"2026"}` | vero — AMBIGUO (data trattata come testo) | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-057 | `{"field":"member.attributes.tNum","cmp":"neq","value":"tre"}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-058 | `{"field":"member.attributes.tNum","cmp":"nin","value":["tre"]}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-059 | `{"field":"member.attributes.tBool","cmp":"neq","value":"vero"}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-060 | `{"field":"member.attributes.tStr","cmp":"neq","value":5}` | falso — **DIVERGENZA** | docs/03 §3.3 «tipi incompatibili → falsa» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-061 | `{"field":"member.attributes.tMissing","cmp":"eq","value":"x"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-062 | `{"field":"member.attributes.tMissing","cmp":"neq","value":"x"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-063 | `{"field":"member.attributes.tMissing","cmp":"gt","value":1}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-064 | `{"field":"member.attributes.tMissing","cmp":"gte","value":1}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-065 | `{"field":"member.attributes.tMissing","cmp":"lt","value":1}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-066 | `{"field":"member.attributes.tMissing","cmp":"lte","value":1}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-067 | `{"field":"member.attributes.tMissing","cmp":"in","value":["x"]}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-068 | `{"field":"member.attributes.tMissing","cmp":"nin","value":["x"]}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-069 | `{"field":"member.attributes.tMissing","cmp":"contains","value":"x"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-070 | `{"field":"member.attributes.tMissing","cmp":"ncontains","value":"x"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-071 | `{"field":"member.attributes.tMissing","cmp":"exists"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-072 | `{"field":"member.attributes.tMissing","cmp":"nexists"}` | vero | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-073 | `{"field":"member.attributes.tMissing","cmp":"between","value":[1,5]}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-074 | `{"field":"member.attributes.tMissing","cmp":"startsWith","value":"x"}` | falso | docs/03 §3.3 «campo assente → falsa (tranne nexists)» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-075 | `{"field":"member.labels","cmp":"eq","value":"ebill"}` | vero | docs/03 §3.3 (su array: vero se almeno un elemento soddisfa) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-076 | `{"field":"member.labels","cmp":"contains","value":"ebill"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-077 | `{"field":"member.labels","cmp":"contains","value":"vip"}` | falso | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-078 | `{"field":"member.labels","cmp":"ncontains","value":"vip"}` | vero | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-079 | `{"field":"member.labels","cmp":"in","value":["vip","ebill"]}` | vero | docs/03 §3.3 (su array: vero se almeno un elemento soddisfa) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-080 | `{"field":"member.labels","cmp":"nin","value":["vip"]}` | vero | docs/03 §3.3 (su array: vero se almeno un elemento soddisfa) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-081 | `{"field":"member.labels","cmp":"nin","value":["ebill"]}` | falso — AMBIGUO (lista: «almeno un elemento» darebbe vero) | docs/03 §3.3 (su array: vero se almeno un elemento soddisfa) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-082 | `{"field":"member.labels","cmp":"exists"}` · labels= | falso — AMBIGUO (lista vuota) | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-083 | `{"field":"member.labels","cmp":"nexists"}` · labels= | vero — AMBIGUO (lista vuota) | docs/03 §3.3, §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-084 | `{"field":"member.tier","cmp":"eq","value":"GOLD"}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-085 | `{"field":"member.status","cmp":"eq","value":"ACTIVE"}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-086 | `{"field":"member.status","cmp":"eq","value":"ACTIVE"}` · status=BLOCKED | falso | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-087 | `{"field":"city","cmp":"eq","value":"Torino"}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-088 | `{"field":"member.city","cmp":"eq","value":"Torino"}` | vero — AMBIGUO (prefisso member. facoltativo) | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-089 | `{"field":"member.age","cmp":"eq","value":26}` | vero | docs/03 §3.3 `age` | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-090 | `{"field":"member.age","cmp":"eq","value":26}` · asOf=2026-09-23T22:30:00Z | vero | docs/03 (fuso Europe/Rome) §3.3 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-091 | `{"field":"member.age","cmp":"eq","value":25}` · asOf=2026-09-23T21:30:00Z | vero | docs/03 (fuso Europe/Rome) §3.3 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-092 | `{"field":"member.age","cmp":"eq","value":25}` · birthDate=2000-02-29;asOf=2026-02-28T12:00:00Z | vero — AMBIGUO (compleanno del 29 febbraio) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-093 | `{"field":"member.age","cmp":"eq","value":26}` · birthDate=2000-02-29;asOf=2026-03-01T12:00:00Z | vero | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-094 | `{"field":"member.age","cmp":"gte","value":0}` · birthDate= | falso | docs/03 §3.3 (campo assente) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-095 | `{"field":"member.registeredDaysAgo","cmp":"eq","value":30}` | vero | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-096 | `{"field":"member.registeredDaysAgo","cmp":"eq","value":0}` · registeredAt=2026-09-23T21:30:00Z;asOf=2026-09-24T06:00:00Z | vero — AMBIGUO (giorni di calendario di Roma o blocchi di 24 h) | docs/03 (fuso Europe/Rome) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-097 | `{"field":"member.registeredDaysAgo","cmp":"eq","value":1}` · registeredAt=2026-03-28T12:00:00Z;asOf=2026-03-30T11:30:00Z | vero — AMBIGUO (giorni di calendario di Roma o blocchi di 24 h) | docs/03 (fuso Europe/Rome) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-098 | `{"field":"balance.PTS","cmp":"gte","value":1500}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-099 | `{"field":"balance.PTS","cmp":"gt","value":1500}` | falso | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-100 | `{"field":"lifetimeEarned.PTS","cmp":"gt","value":4999}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-101 | `{"field":"lastActivityDaysAgo","cmp":"gt","value":45}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-102 | `{"field":"lastActivityDaysAgo","cmp":"gt","value":45}` · lastActivityAt=2026-08-10T10:00:00Z | falso | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-103 | `{"field":"lastActivityDaysAgo","cmp":"gt","value":45}` · lastActivityAt= | falso | docs/03 §3.3 (campo assente) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-104 | `{"field":"actions.purchase.completed.count30d","cmp":"gte","value":2}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-105 | `{"field":"actions.purchase.completed.total","cmp":"eq","value":7}` | vero | docs/03 §10 · Q-87 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-106 | `{"field":"actions.app.login.daily.count30d","cmp":"eq","value":0}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-107 | `{"field":"purchases.amount90d","cmp":"eq","value":107.4}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-108 | `{"field":"purchases.amount90d","cmp":"gt","value":100}` | vero | docs/03 §10 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-109 | `{"op":"all","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"},{"field":"member.tier","cmp":"eq","value":"GOLD"}]}` | vero | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-110 | `{"op":"all","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"},{"field":"member.tier","cmp":"eq","value":"BASE"}]}` | falso | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-111 | `{"op":"any","rules":[{"field":"member.tier","cmp":"eq","value":"BASE"},{"field":"member.tier","cmp":"eq","value":"GOLD"}]}` | vero | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-112 | `{"op":"any","rules":[{"field":"member.tier","cmp":"eq","value":"BASE"},{"field":"member.tier","cmp":"eq","value":"BASE"}]}` | falso | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-113 | `{"op":"not","rules":[{"field":"member.tier","cmp":"eq","value":"BASE"}]}` | vero | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-114 | `{"op":"not","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"}]}` | falso | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-115 | `{"op":"not","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"},{"field":"member.tier","cmp":"eq","value":"BASE"}]}` | vero — AMBIGUO (Q-90: «NESSUNA» darebbe falso) | docs/03 §3.3 · docs/08 BO-06 «NESSUNA» · Q-90 | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-116 | `{"op":"all","rules":[{"op":"any","rules":[{"field":"member.tier","cmp":"eq","value":"BASE"},{"field":"member.tier","cmp":"eq","value":"GOLD"}]},{"op":"not","rules":[{"field":"member.tier","cmp":"eq","value":"BASE"}]}]}` | vero | docs/03 §3.3 (gruppi all/any/not) | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-117 | `{}` | falso | Q-87 «criteri vuoti non includono nessuno» | `TestbookGovSegmentCriteriaTest#matches` |
| TB-GOV-CRT-118 | `null` | falso | Q-87 | `TestbookGovSegmentCriteriaTest#matches` |

### 11.2 Validazione dei criteri (CRV)

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-CRV-001 | `null` | non valido su `criteria` (422 `INVALID_CRITERIA` via API) | Q-87 · member §3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-002 | `{}` | non valido su `criteria` (422 `INVALID_CRITERIA` via API) | Q-87 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-003 | `{"field":"member.tier","cmp":"eq","value":"GOLD"}` | valido | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-004 | `{"op":"all","rules":[{"op":"any","rules":[{"op":"not","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"}]}]}]}` | valido | docs/03 §3.3 · docs/08 BO-06 (3 livelli) | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-005 | `{"op":"xor","rules":[{"field":"member.tier","cmp":"eq","value":"GOLD"}]}` | non valido su `criteria.op` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 (all/any/not) | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-006 | `{"op":"all","rules":[]}` | non valido su `criteria.rules` (422 `INVALID_CRITERIA` via API) — AMBIGUO (gruppo vuoto) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-007 | `{"cmp":"eq","value":"GOLD"}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-008 | `{"field":"member.shoeSize","cmp":"eq","value":42}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §10 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-009 | `{"field":"data.amount","cmp":"gt","value":5}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §10 (solo member.* esteso) | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-010 | `{"field":"context.hour","cmp":"eq","value":9}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §10 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-011 | `{"field":"member.segments","cmp":"contains","value":"SEG-VIP"}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) — AMBIGUO (segmenti nei criteri di un segmento) | docs/03 §3.3 (`segments` in member.*) · F-SEG-02 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-012 | `{"field":"member.attributes.","cmp":"exists"}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-013 | `{"field":"actions.purchase.completed","cmp":"gt","value":1}` | non valido su `criteria.field` (422 `INVALID_CRITERIA` via API) | docs/03 §10 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-014 | `{"field":"actions.purchase.completed.total","cmp":"gt","value":1}` | valido | Q-87 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-015 | `{"field":"tier","cmp":"eq","value":"GOLD"}` | valido — AMBIGUO (prefisso facoltativo) | docs/03 §10 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-016 | `{"field":"member.tier","cmp":"like","value":"GO"}` | non valido su `criteria.cmp` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 (14 comparatori) | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-017 | `{"field":"member.tier","value":"GOLD"}` | non valido su `criteria.cmp` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-018 | `{"field":"member.tier","cmp":"eq"}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-019 | `{"field":"member.tier","cmp":"eq","value":null}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-020 | `{"field":"member.city","cmp":"exists"}` | valido | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-021 | `{"field":"member.tier","cmp":"in","value":"GOLD"}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-022 | `{"field":"member.tier","cmp":"nin","value":"GOLD"}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-023 | `{"field":"balance.PTS","cmp":"between","value":[1]}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-024 | `{"field":"balance.PTS","cmp":"between","value":[1,2,3]}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-025 | `{"field":"member.attributes.contractDate","cmp":"gt","value":"2026-01-01"}` | non valido su `criteria.value` (422 `INVALID_CRITERIA` via API) — AMBIGUO (Q-91) | docs/03 §3.3 · Q-91 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-026 | `{"field":"balance.PTS","cmp":"gte","value":100}` | valido | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |
| TB-GOV-CRV-027 | `{"op":"all","rules":["tier"]}` | non valido su `criteria.rules[0]` (422 `INVALID_CRITERIA` via API) | docs/03 §3.3 | `TestbookGovSegmentCriteriaTest#validate` |

### 11.3 Segmenti via API (SEG)
Domini: anteprima (seed, criteri non validi, vuoti, nessuna scrittura), creazione (codice 2/3/40/41 caratteri e
minuscolo — docs/06 §2 `^[A-Z][A-Z0-9-]{2,39}$` —, nome, tipo, codice duplicato, statici con membri inesistenti o
anonimizzati), modifica (tipo, codice, stato, criteri, versione), archiviazione e ricalcolo; stati del membro nei
dinamici (Q-85: tutti tranne ANONYMIZED, una riga per stato).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-GOV-SEG-001 | anteprima tier in [GOLD, PLATINUM] | `4` | member §7 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-002 | anteprima con criteri non validi | `422:INVALID_CRITERIA` | member §3 · docs/06 §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-003 | anteprima con criteri vuoti | `422:INVALID_CRITERIA` — AMBIGUO (criteri vuoti: 422 o 0 membri) | Q-87 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-004 | anteprima senza scritture | `0 fatti` | member §3 (senza salvare) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-005 | creazione di un dinamico | `201:1` | F-SEG-02 · member §3 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-006 | fatto entered alla creazione | `entered` | F-SEG-03 · docs/03 §10 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-007 | codice di 2 caratteri | `422:INVALID_CODE` | docs/06 §2 (`^[A-Z][A-Z0-9-]{2,39}$`) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-008 | codice di 3 caratteri (minimo) | `201` | docs/06 §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-009 | codice di 40 caratteri (massimo) | `201` | docs/06 §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-010 | codice di 41 caratteri | `422:INVALID_CODE` | docs/06 §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-011 | codice in minuscolo | `201:maiuscolo` — AMBIGUO (normalizzato in maiuscolo) | docs/06 §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-012 | nome vuoto | `422:NAME_REQUIRED` — AMBIGUO (nome obbligatorio) | member §2 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-013 | tipo sconosciuto | `422:INVALID_TYPE` | member §2 (`STATIC`/`DYNAMIC`) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-014 | codice duplicato | `409:CODE_TAKEN` | docs/06 §2 (409 codice duplicato) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-015 | statico con membro inesistente | `422:MEMBER_NOT_FOUND` — AMBIGUO (codice dell'errore) | F-SEG-01 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-016 | statico con membro anonimizzato | `422:MEMBER_NOT_FOUND` — AMBIGUO (anonimizzati non selezionabili) | F-SEG-01 · Q-85 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-017 | elenco manuale su un dinamico | `409:SEGMENT_NOT_STATIC` — AMBIGUO (codice dell'errore) | member §3 (solo STATIC) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-018 | cambio di tipo | `409:SEGMENT_IMMUTABLE_FIELD` | Q-88 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-019 | cambio di codice | `409:SEGMENT_IMMUTABLE_FIELD` | Q-88 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-020 | archiviazione: escono tutti | `left` | Q-88 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-021 | ricalcolo di un archiviato | `409:SEGMENT_ARCHIVED` — AMBIGUO (codice dell'errore) | Q-88 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-022 | stato sconosciuto | `422:INVALID_STATUS` | member §2 (`ACTIVE`/`ARCHIVED`) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-023 | secondo ricalcolo senza variazioni | `0:0` | F-SEG-03 · docs/03 §10 (solo differenze) | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-024 | criteri modificati: chi non soddisfa esce | `left` | F-SEG-03 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-025 | statico: sostituzione dell'elenco | `1:1` | F-SEG-01 · member §3 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-026 | dinamico: membro ACTIVE incluso | `dentro` | Q-85 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-027 | dinamico: membro INACTIVE incluso | `dentro` | Q-85 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-028 | dinamico: membro BLOCKED incluso | `dentro` | Q-85 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-029 | dinamico: membro ANONYMIZED escluso | `fuori` | Q-85 | `TestbookGovMemberIT#segments` |
| TB-GOV-SEG-030 | versione superata | `409:VERSION_CONFLICT` — AMBIGUO (Q-112 riguarda campagne, premi, concorsi) | docs/06 §4 · Q-112 | `TestbookGovMemberIT#segments` |

## 12. Flussi tra servizi (hub)

_In arrivo (hub: tipi di oggetto, matrice capacità × ruolo, effetti degli stati del membro, anonimizzazione propagata, casella approvazioni)._

## 13. Ambiguità (da registrare in `docs/15`)

Righe che asseriscono il comportamento attuale perché la specifica tace e `docs/15` non registra una scelta
(`// TESTBOOK: ambiguo, vedi …` nel test). Proposta: registrarle come domande Q-nnn.

| Righe | Punto aperto | Comportamento attuale asserito |
|---|---|---|
| ACT-007…014 | Formati non canonici di `X-LH-Actor` (minuscole, spazi, ruolo sconosciuto, senza `:`, `:` in coda, più `:`) | lettura permissiva di ruolo e username; ruolo sconosciuto o `RUOLO:` ⇒ ANALYST |
| PRS-009…016 | Azione in minuscolo/con spazi; codice di un'azione sconosciuta (docs/06 §2 direbbe 400 «parametri errati») | accettate in modo permissivo; sconosciuta ⇒ 422 `INVALID_ACTION` |
| SMF-001, ROL-051, ROL-052 | Con la policy spenta `SUBMIT` da DRAFT porta a LIVE (docs/06 §7 dice solo «DRAFT → LIVE diretto») | `LIVE` |
| ROL-056…060 | Precedenza tra ruolo vietato (403), transizione vietata (409), commento mancante (422) | ruolo, poi stato, poi commento |
| OVR-006, OVR-016 | ADMIN che approva/rifiuta un oggetto senza approvatore di policy: è un override da marcare? | non marcato |
| CMT-006 | Commento di soli spazi non separabili (U+00A0) | accettato come commento |
| MST-001, MST-010, MST-019 | Cambio verso lo stesso stato | 200 senza fatto né audit |
| MST-008, MST-016, MST-024 | Stato in minuscolo (`blocked`) | accettato |
| MST-028…031 | Anonimizzato con destinazione non valida: 409 o 400? | 409 `MEMBER_ANONYMIZED` |
| MST-040 | `CLOSED` come filtro dell'elenco: i contratti lo ammettono (Q-139), docs/03 §2 e F-MBR-04 no | 200 (0 membri) |
| MRL-002, 003, 008, 009, 021, 022, 027, 028, 033, 034, 039, 040, 045, 046 | Celle «—» senza ● per ruoli diversi da ANALYST: il backend deve rifiutare? (docs/08 §2 lo impone solo con ●) | 403 `FORBIDDEN_ROLE` |
| MRL-073, MRL-074 | `X-LH-Actor` con ruolo sconosciuto o in minuscolo | ANALYST (403) / ruolo riconosciuto |
| ANO-002 | Conferma con spazi ai bordi | accettata (`trim`) |
| ANO-010 | Membro inesistente con conferma errata: 404 o 422? | 404 |
| ATV-002, 003, 005 | Testo vuoto o di soli spazi; lunghezza massima 200 | rifiutati (422) |
| ATV-015, 031, 047, 063 | `null` in un PATCH di attributi | rimuove la chiave |
| ATV-062 | Data con ora per un attributo DATE | rifiutata |
| ATV-069 | Chiave interna della demo (`story`) nel PATCH | rifiutata |
| ATD-002…009, 012…016, 028, 029 | Formato della chiave (camelCase, 2–40), etichetta obbligatoria ≤ 60, tetto di 30 definizioni | come scritto nelle righe |
| ATU-006 | Restringere le opzioni lasciando valori fuori elenco (Q-93 non lo dice) | ammesso (200) |
| ATU-009 | Chiave con spazi ai bordi | normalizzata in silenzio |
| CRT-045…048, 055, CRV-025 | Confronti d'ordine e `between` sulle date ISO (Q-91 aperta) | falsi; `gt` con testo rifiutato in validazione |
| CRT-051, 052, 056 | `contains`, `ncontains`, `startsWith` su un attributo DATE | la data vale come testo |
| CRT-081 | `nin` su una lista: «almeno un elemento» (docs/03 §3.3, per `data.*`) o intersezione vuota? | intersezione vuota |
| CRT-082, CRT-083 | `exists`/`nexists` su una lista vuota | lista vuota = assente |
| CRT-088, CRV-015 | Prefisso `member.` facoltativo | accettato con e senza |
| CRT-092 | Età di chi è nato il 29 febbraio, il 28 febbraio di un anno non bisestile | compie gli anni il 1° marzo |
| CRT-096, CRT-097 | `registeredDaysAgo` a cavallo della mezzanotte di Roma e del cambio d'ora | blocchi di 24 h (non giorni di calendario di Roma) |
| CRT-115 | `not` con più regole (Q-90 aperta: «NESSUNA» o «non tutte») | «non tutte» |
| CRV-006 | Gruppo senza regole | non valido |
| CRV-011 | `member.segments` nei criteri di un segmento (docs/03 §3.3 lo elenca in `member.*`) | campo non disponibile |
| SEG-003 | Anteprima con criteri vuoti: 422 o 0 membri (Q-87) | 422 `INVALID_CRITERIA` |
| SEG-011 | Codice in minuscolo | normalizzato in maiuscolo |
| SEG-012 | Nome del segmento obbligatorio | 422 `NAME_REQUIRED` |
| SEG-015, 016 | Statico con membri inesistenti o anonimizzati | 422 `MEMBER_NOT_FOUND` |
| SEG-017, SEG-021 | Codici d'errore di «solo STATIC» e «archiviato» | 409 `SEGMENT_NOT_STATIC`, 409 `SEGMENT_ARCHIVED` |
| SEG-030 | Blocco ottimistico dei segmenti (Q-112 cita solo campagne, premi, concorsi) | 409 `VERSION_CONFLICT` |

## 14. Divergenze

Il test asserisce la specifica e fallisce finché il codice non è corretto o `docs/15` non registra una scelta diversa.
Nessuna divergenza nelle aree di `lh-common` a logica pura (ACT, GRD, PRS, SMR, SMN, SMF, ROL, CMT, OVR, POL).

| # | Righe | Specifica | Osservato | Causa (file:riga) |
|---|---|---|---|---|
| D-01 | CRT-024, CRT-028, CRT-038, CRT-042, CRT-057…060 | docs/03 §3.3: «Tipi incompatibili → falsa, mai eccezione» | `neq`, `nin`, `ncontains` sono **veri** quando i tipi non sono confrontabili (numero contro testo, booleano contro testo, testo contro numero); `startsWith` è vero su numeri e booleani (`3.0` inizia per `3`, `true` per `t`) | `services/member-service/…/domain/SegmentCriteria.java:258, 264, 266` (negazione del confronto fallito) e `:268` (`actual.toString()`) |
| D-02 | ATD-023, ATU-011 | docs/06 §2: regola violata ⇒ 422 con `code` (F-MBR-03: definizione non valida) | una definizione senza `type` provoca `NullPointerException` ⇒ 500 `INTERNAL_ERROR` | `services/member-service/…/domain/MemberAttributes.java:165` (`List.of(...).contains(null)`) |
| D-03 | MST-038 | docs/06 §2: «400 `bad-request` — JSON malformato, parametri errati» | corpo assente su `POST /v1/members/{id}/status` ⇒ 500 `INTERNAL_ERROR` | `libs/lh-common/…/web/GlobalExceptionHandler.java:66` (nessun gestore per `HttpMessageNotReadableException`, finisce nel ramo generico) |

## 15. Copertura

| Misura | Valore |
|---|---|
| Righe (finora) | 772 |
| di cui AMBIGUO | 110 |
