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

## 8–11. Membri: stati, anonimizzazione, attributi, segmenti

_In arrivo (member-service: `TestbookGovMember*`)._

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

## 14. Divergenze

Nessuna divergenza nelle aree di `lh-common` (ACT, GRD, PRS, SMR, SMN, SMF, ROL, CMT, OVR, POL).

## 15. Copertura

| Misura | Valore |
|---|---|
| Righe (finora) | 322 |
| di cui AMBIGUO | 27 |
