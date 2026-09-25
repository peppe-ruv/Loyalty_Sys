# 7. TB-GAM — Gioco

Testbook per il dominio Gamification (concorso instant win, obiettivi, badge, classifiche, referral).
Servizio: `gamification-service` (port: 8086).
Documentazione: `docs/servizi/gamification-service.md`, `docs/03-MODELLO-DI-DOMINIO.md`, `docs/02-CATALOGO-FUNZIONALE.md` (F-IW-*, F-ACH-*, F-LDB-*, F-REF-*).

## 7.1. Concorso e Giocata Instant Win (Play)

La giocata dipende dallo stato del concorso, dai limiti giornalieri/totali, dal tipo di credito e dallo stato del membro (F-IW-04, F-IW-05, BO-14).
*Ambiguità / Spec Gap:*
* Q-56: meccanica `BOX` in API ma `GIFT` in spec/UI. Viene testata la logica di fallback del concorso (in `Contest.java` sono supportati `WHEEL, SCRATCH, BOX`).
* Q-62: piantare un istante usa l'ultimo `OPEN` e non sposta il montepremi.
* Q-113: copia concorso con `-COPY-n`.

**Domini degli input (Equivalence partitioning):**
- `Contest.status`: {LIVE, DRAFT, IN_REVIEW, APPROVED, PAUSED, ENDED, ARCHIVED} → Classi valide: `LIVE`. Invalide: tutte le altre.
- `Now vs Period`: {before start, at start, in period, at end, after end}. Valide: in period, at start, at end-1. Invalide: before start, after end, at end (escluso).
- `Member.status`: {ACTIVE, INACTIVE, BLOCKED, ANONYMIZED, unknown}. Valido: `ACTIVE`. Invalido: gli altri.
- `FreePlay`: concorso ha `freePlayDaily` (true/false) e membro lo ha usato (true/false).
- `Credits`: {0, 1, n}.
- `Daily Limit`: {reached, not reached, null}.
- `Wins Limit`: {reached, not reached, null}.

**Combinazioni (Play)**: All-pairs con classi invalide.
| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-GAM-PLY-001 | Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=false, credits=0, limits=null | 200 WIN o LOSE, consuma free | F-IW-04 | `TestbookGamPlayIT#playFreeSuccess` |
| TB-GAM-PLY-002 | Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=true, credits=1, limits=null | 200, consuma credit | F-IW-04 | `TestbookGamPlayIT#playCreditSuccess` |
| TB-GAM-PLY-003 | Status LIVE, in period, ACTIVE, freePlay=false, freeUsed=false, credits=0, limits=null | 422 NO_PLAYS_AVAILABLE | F-IW-05 | `TestbookGamPlayIT#playNoCredits` |
| TB-GAM-PLY-004 | Status DRAFT, in period, ACTIVE, credits=1 | 422 CONTEST_NOT_LIVE | F-IW-04 | `TestbookGamPlayIT#playNotLive` |
| TB-GAM-PLY-005 | Status LIVE, before start, ACTIVE, credits=1 | 422 CONTEST_NOT_LIVE | F-IW-04 | `TestbookGamPlayIT#playBeforeStart` |
| TB-GAM-PLY-006 | Status LIVE, after end, ACTIVE, credits=1 | 422 CONTEST_NOT_LIVE | F-IW-04 | `TestbookGamPlayIT#playAfterEnd` |
| TB-GAM-PLY-007 | Status LIVE, in period, INACTIVE, credits=1 | 422 MEMBER_NOT_ACTIVE | F-IW-04 | `TestbookGamPlayIT#playMemberInactive` |
| TB-GAM-PLY-008 | Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=false, dailyLimit=reached | 422 DAILY_LIMIT_REACHED | F-IW-05 | `TestbookGamPlayIT#playDailyLimit` |
| TB-GAM-PLY-009 | Status LIVE, in period, ACTIVE, credits=1, winsLimit=reached (instant available) | 200 LOSE (vincita bloccata dal cap) | F-IW-04 | `TestbookGamPlayIT#playWinsLimit` |
| TB-GAM-PLY-010 | Giocata concorrente con 1 istante scaduto, 50 thread | Esattamente 1 WIN, 49 LOSE | F-IW-04 | `TestbookGamPlayIT#playConcurrency` |

## 7.2. Generazione e Claim Istanti

**Generator (F-IW-03):**
- Distribuzione `UNIFORM` (tutto l'orario) o `BUSINESS_HOURS` (solo 08-22 Europe/Rome).
- Tipi premio: {POINTS, COUPON, PHYSICAL}.
- `plantLast` per demo (Q-62).
- Istanti OPEN dop fine diventano VOID.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-GAM-INST-001 | UNIFORM, quantity=100 | 100 istanti nel periodo, anche notturni | F-IW-03 | `TestbookGamInstantTest#generatorUniform` |
| TB-GAM-INST-002 | BUSINESS_HOURS, quantity=100 | 100 istanti, tutti 08:00 - 21:59 | F-IW-03 | `TestbookGamInstantTest#generatorBusinessHours` |
| TB-GAM-INST-003 | Stesso seed, stessi parametri | Stessi identici istanti | F-IW-03 | `TestbookGamInstantTest#generatorSeedDeterminism` |
| TB-GAM-INST-004 | Planted instant (Q-62) e claim | Crea istante OPEN a now-1s dal primo disponibile; claim lo vince | Q-62, F-IW-08 | `TestbookGamPlayIT#plantAndClaim` |
| TB-GAM-INST-005 | Fine concorso | Istanti OPEN → VOID | docs/03 | `TestbookGamContestIT#endContestVoidsInstants` |

## 7.3. Contest Lifecycle & Approvals

Il concorso deve passare da DRAFT -> IN_REVIEW -> APPROVED (da LEGAL) -> LIVE (con istanti generati).
Se LIVE_LOCKED, non può modificare regole o premi.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-GAM-LFC-001 | Publish senza istanti generati | 422 INSTANTS_NOT_GENERATED | F-IW-01 | `TestbookGamContestIT#publishNoInstants` |
| TB-GAM-LFC-002 | Transition APPROVED -> LIVE con istanti | OK, lock premi | F-IW-01 | `TestbookGamContestIT#publishSuccess` |
| TB-GAM-LFC-003 | Genera istanti su LIVE | 409 CONFLICT | F-IW-03 | `TestbookGamContestIT#generateOnLive` |
| TB-GAM-LFC-004 | Admin legge /instants | 200 OK | F-IW-07 | `TestbookGamContestIT#readInstantsAdmin` |
| TB-GAM-LFC-005 | Marketing legge /instants | 403 FORBIDDEN | F-IW-07 | `TestbookGamContestIT#readInstantsMarketing` |

## 7.4. Obiettivi (Achievements) e Badge

(F-ACH-01, F-ACH-02, F-ACH-03, Q-59)
Metrica: {COUNT, SUM, DISTINCT_TYPES, STREAK}. Periodi: {EVER, MONTH, EDITION}.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-GAM-ACH-001 | Metrica COUNT, target=3, 3 azioni | completed=true, emette achievement.completed | F-ACH-01 | `TestbookGamAchievementIT#metricCount` |
| TB-GAM-ACH-002 | Metrica SUM, target=100, field data.amount, {50, 60} | completed=true con 110 | F-ACH-01 | `TestbookGamAchievementIT#metricSum` |
| TB-GAM-ACH-003 | Metrica DISTINCT_TYPES, {a, b, a, c}, target=3 | completed=true (3 tipi) | F-ACH-01 | `TestbookGamAchievementIT#metricDistinct` |
| TB-GAM-ACH-004 | Metrica STREAK, DAY, {giorno1, giorno2, giorno4} | buco al giorno 3 azzera; progress = 1 al g4 | F-ACH-01 | `TestbookGamAchievementTest#metricStreakGaps` |
| TB-GAM-ACH-005 | Filtro: action con field errato | Progresso ignorato | F-ACH-01 | `TestbookGamAchievementTest#filterMismatch` |
| TB-GAM-ACH-006 | Period MONTH, {31 ago 23:30Z, 1 set 01:00Z} | Progresso separato per 2026-08 e 2026-09 (Rome) | F-ACH-01 | `TestbookGamAchievementTest#periodMonthBoundary` |
| TB-GAM-ACH-007 | Badge collegato, achievement completed | Emette badge.awarded | F-ACH-03 | `TestbookGamAchievementIT#badgeAwarding` |
| TB-GAM-ACH-008 | Repeatable=false, completato 2 volte | Nessuna seconda emissione | F-ACH-01 | `TestbookGamAchievementIT#notRepeatable` |

## 7.5. Classifiche (Leaderboards)

(F-LDB-01, Q-60)
Metriche: {PTS_EARNED, STS_EARNED, ACTION_COUNT}. Solo ACTIVE.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-GAM-LDB-001 | PTS_EARNED, wallet.points.earned | Score = amount PTS | F-LDB-01 | `TestbookGamLeaderboardIT#ptsEarned` |
| TB-GAM-LDB-002 | Member INACTIVE / BLOCKED | Escluso dal ranking GET | F-LDB-01 | `TestbookGamLeaderboardIT#excludeInactive` |
| TB-GAM-LDB-003 | Parimerito | Chi ha raggiunto il punteggio prima vince rank superiore | F-LDB-01 | `TestbookGamLeaderboardIT#tieBreakerTime` |
| TB-GAM-LDB-004 | ACTION_COUNT con filtro tipi | Conta solo le azioni matchanti | F-LDB-01 | `TestbookGamLeaderboardIT#actionCountFilter` |

## 7.6. Referral (Completamento)

(F-REF-02, Q-61) Il completamento avviene su member-service, ma qui è la logica trasversale (member/gam/cmp). gamification valuta referral_completed? No, gamification non consuma questo ma lo consuma campaign. Nessun test di dominio gamification qui, ma mappiamo i test in member-service/campaign altrove, oppure segniamo la riga per test hub. (Coperto da member-service in TB-MBR/TB-CMP, qui escluso da implementazione backend GAM).

## Copertura
- Regole analizzate: 25
- Righe eseguite in questo testbook: 27
- Divergenze/Ambiguità tracciate: Q-56, Q-62, Q-113.
