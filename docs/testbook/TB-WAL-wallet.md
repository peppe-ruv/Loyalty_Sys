# TB-WAL — Testbook funzionale: wallet e livelli

Dominio **wallet** del testbook funzionale (`docs/16`): valute `PTS`/`STS`, libro mastro, lotti e scadenze, punti in attesa, spesa FIFO e rimborso, job (scadenze, preavvisi, rilascio), livelli (salita immediata, chiusura di edizione con discesa morbida), edizioni, valute, rettifiche manuali, viste e passività. Servizio: `services/wallet-service`.

- **Oracolo**: `docs/03 §2, §3.1, §3.4, §4` · `docs/servizi/wallet-service.md` · `docs/02` (`F-WAL-*`, `F-TIER-*`, `F-DEMO-06`) · `docs/05` + `contracts/events/` · `docs/06 §2–3` · `docs/08` (BO-03, BO-07, BO-08, BO-30) · `docs/09` (PT-01, PT-07, PT-08) · `seed/tiers.json`, `currencies.json`, `editions.json` (letti a runtime) · scelte registrate in `docs/15` (Q-45, Q-46, Q-47, Q-54, Q-127). Mai «quello che il codice fa oggi».
- **AMBIGUO**: la specifica tace e `docs/15` non registra una scelta → la riga asserisce il comportamento attuale (commento `// TESTBOOK: ambiguo, vedi <ID>` nel test) ed è elencata in §19.
- **Divergenza**: il test asserisce la specifica e fallisce; registro in §20.

## 0. Esecuzione

| Classe | Tipo | Dati | Righe |
|---|---|---|---|
| `TestbookWalPolicyTest` | unit, logica pura di `ExpiryPolicy` | `testbook/wal/policy.csv` | POL |
| `TestbookWalCloseRuleTest` | unit, logica pura di `EditionCloseRule` | `testbook/wal/close-rule.csv` | CLR |
| `TestbookWalAccrualIT` | integrazione (Spring, Postgres embedded, profilo `demo`) | `grants.csv`, `tier-upgrade.csv`, `release.csv`, `wallet-view.csv`, `keep-warning.csv`, `job-roles.csv`, `expiry-job.csv`, `warnings.csv` | GRT, TUP, REL, WVW, API, MBR, LIA, JOB, EXP, WRN |
| `TestbookWalSpendIT` | integrazione | `spend.csv`, `adjustments.csv` | SPD, REF, ADJ |
| `TestbookWalAdminIT` | integrazione (stato globale: valute, livelli, edizioni) | `currency-policy.csv`, `tier-admin.csv`, `edition-crud.csv`, `close-roles.csv` | CUR, TAD, EDN, ECL |

I file sono in `services/wallet-service/src/test/{java/io/loyaltyhub/wallet/testbook, resources/testbook/wal}`. Ogni caso ha nome `[<ID>] <descrizione>`; una riga = un caso eseguito.

Convenzioni dei test d'integrazione:
- **Orologio fisso** sostituito al `Clock` del servizio: «oggi» = **24/09/2026 10:00** (Roma); gli accrediti hanno per data di business **T0 = 18/09/2026 12:15** (Roma, esempio di `docs/05 §2`). Le righe di calendario spostano l'orologio.
- **Membri freschi** (`MBR-TB-…`) per ogni riga: nessuna dipendenza dallo stato mutabile dei dati demo; solo ECL-002 legge un membro del seed (Stefano, in sola lettura).
- **Valori dal seed a runtime**: soglie STS `BASE 0 · SILVER 1 000 · GOLD 3 000 · PLATINUM 7 000`, moltiplicatori `1,00 · 1,25 · 1,50 · 2,00`, policy `PTS = ROLLING_MONTHS 12`, `STS = EDITION`, edizioni `ED-2025 CLOSED · ED-2026 ACTIVE · ED-2027 PLANNED`. Nelle tabelle i valori sono scritti per esteso; nei CSV come espressioni (`GOLD-1`, `PLATINUM+1`).
- **Fatti e audit** letti dall'outbox (stessa transazione della scrittura, `docs/06 §1`), senza attese né `sleep`.
- **Preparazione**: lotti e saldo scritti insieme (invariante «saldo attivo = Σ lotti ACTIVE»); scadenze preparate alle 23:59:59 di Roma.
- **Rapporto** (`scripts/testbook.sh`): i report JUnit del modulo usano i nomi visualizzati (`usePhrasedTestCaseMethodName` in `services/wallet-service/pom.xml`); `scripts/testbook-report.mjs` accetta il prefisso `metodo(…)` che Surefire antepone ai casi parametrizzati. Surefire esclude `**/*IT.java` (un `Testbook…IT` inizia per «Test» e sarebbe eseguito anche come unit test da `./mvnw verify`); con `-Dtest='Testbook*'` l'esclusione non vale e gli IT girano sia in Surefire sia in Failsafe.

## 1. Inventario delle regole

| Regola | Enunciato (sintesi) | Fonte | Righe |
|---|---|---|---|
| R-01 | Doppia valuta: `PTS` spendibile, `STS` non spendibile, saldi separati | F-WAL-01 · docs/03 §4.1 | GRT, SPD-019, ADJ-018 |
| R-02 | Ogni variazione è un movimento immutabile (importo sempre positivo, direzione) con causale, origine, campagna, azione, attore | F-WAL-02 · wallet §2 | GRT, API-005, ADJ, ADJ-023/024 |
| R-03 | Ogni `EARN`/`ADJUST_CREDIT`/`REFUND` crea un lotto; `pendingDays > 0` → `PENDING` fino ad `availableAt` | docs/03 §4.2 · F-WAL-03/05 | GRT, ADJ, REF-003…006 |
| R-04 | `ROLLING_MONTHS(n)`: scadenza all'ultimo istante del mese di `earned_at + n mesi`, Europe/Rome | docs/03 §4.1 · wallet §5 | POL-001…014, GRT, GRT-064, EXP-001…010, LIA-006 |
| R-05 | `END_OF_EDITION_PLUS_GRACE`: scadenza a `redemptionGraceUntil` dell'edizione; nessuna edizione → non scade; senza grace → `endDate + graceDays` | docs/03 §4.1 · Q-47 | POL-015…020, CUR-003 |
| R-06 | `NEVER` e `STS` (contatore per edizione): nessuna scadenza | docs/03 §4.1 | POL-021/022, CUR-004, EXP-016, WRN-007 |
| R-07 | Saldo attivo = Σ `remaining` dei lotti `ACTIVE`, in attesa = Σ `PENDING`; saldo attivo ≥ 0 | docs/03 §4.2 · wallet §6 | SPD, ADJ, REF-007/011, LIA-003 |
| R-08 | Moltiplicatore di livello: con `tierMultiplierApplies` e `PTS` → `floor(base × multiplier)`; metadata `{baseAmount, tierCode, tierMultiplier, campaignMultiplier}` | docs/03 §4.2 · F-TIER-03 · wallet §7 | GRT-001…058, GRT-063, TUP-018, TAD-017 |
| R-09 | Idempotenza su `effect_id`; wallet e livello `BASE` creati al volo | wallet §5, §7 | GRT-059/060 |
| R-10 | Effetto con risultato ≤ 0 scartato | docs/03 §3.4 | GRT-056/057 |
| R-11 | La data di business (scadenze, movimento) è il `time` dell'azione; i fatti derivati ne conservano il `time` | docs/03 §3.1 · docs/05 §2 | GRT-062 |
| R-12 | Rilascio dei pending da job: lotto `ACTIVE`, movimento `RELEASE` informativo, `wallet.points.released` | F-WAL-05 · docs/03 §4.2 · wallet §5 · EVT-FACT-27 | REL |
| R-13 | Spesa FIFO: lotti `ACTIVE` per `expiresAt` crescente (null per ultimi), poi `earnedAt`; tutto o niente; rifiuto `wallet.spend.rejected` con `available` | docs/03 §4.2 · F-WAL-04 · wallet §7 | SPD-001…014, SPD-024 |
| R-14 | Saga: spesa sempre in `PTS`, idempotente su `redemption_id`; membro non `ACTIVE` → `MEMBER_NOT_ACTIVE` | wallet §5 · EVT-FACT-21/22 · docs/03 §2 | SPD-015…023 |
| R-15 | Rimborso: **nuovo lotto** con `expiresAt = max(scadenza più lontana tra i lotti consumati, oggi + 30 g)`, movimento `REFUND`, `wallet.points.refunded` | docs/03 §4.2 · F-WAL-08 · EVT-FACT-23 (Q-54 in contrasto, §20) | REF |
| R-16 | Scadenza (job): lotti `ACTIVE` con `expiresAt ≤ asOf` → `EXPIRED`, **un** movimento `EXPIRE` per membro/valuta col totale, `wallet.points.expired` | docs/03 §4.2 · F-WAL-06 · EVT-FACT-24 | EXP |
| R-17 | Preavviso a 30 giorni, una volta per lotto (`wallet.points.expiring`) | F-WAL-06 · wallet §5 · EVT-FACT-25 | WRN |
| R-18 | Job su richiesta con data di riferimento `asOf`, solo `ADMIN` | F-DEMO-06 · wallet §3 · docs/06 §3 · docs/08 §2 | JOB, REL, EXP, WRN |
| R-19 | Rettifica: `CREDIT`/`DEBIT`, importo > 0, motivo tra `GOODWILL, CORRECTION, COMPLAINT, TEST` (Q-45), nota ≥ 10 caratteri (`NOTE_TOO_SHORT`), solo `CARE`/`ADMIN`, addebito non sotto zero (`INSUFFICIENT_BALANCE`), solo `PTS` (Q-46), anonimizzato → 409 (Q-127) | F-WAL-07 · wallet §3 · docs/03 §4.2 · docs/06 §3 · BO-03 | ADJ |
| R-20 | Livelli: soglie STS strettamente crescenti col rank (`TIER_THRESHOLDS_NOT_MONOTONIC`), `BASE` a 0 fissa, modifica con `program.config` (ADMIN) | F-TIER-01 · wallet §3 · docs/08 §2, BO-07 | TAD |
| R-21 | Salita immediata dopo ogni accredito `STS`: al livello più alto raggiunto, `tier.upgraded {previousTier, newTier, periodSts}`, storico `UPGRADE` | F-TIER-02 · docs/03 §4.3 · wallet §5 · EVT-FACT-28 | TUP |
| R-22 | Chiusura: `earned` = livello più alto con soglia ≤ `periodSts`; `floor` = rank − 1; nuovo = max; mai salita in chiusura; `periodSts = 0`; solo membri `ACTIVE` | F-TIER-04 · docs/03 §4.3 | CLR, ECL-001…004, ECL-013…015 |
| R-23 | Anteprima `dryRun` senza scritture; applicazione solo `ADMIN`; un fatto per membro + `edition.closed`; edizione `CLOSED`, **la successiva** `ACTIVE`, una sola `ACTIVE` | F-TIER-05 · wallet §3, §5 · docs/03 §4.3–4.4 · BO-08 | ECL |
| R-24 | Edizioni senza sovrapposizioni (periodi contigui); creazione e modifica con `program.config` | docs/03 §4.4 · wallet §3 · docs/08 §2 | EDN |
| R-25 | Policy di scadenza per valuta (`ROLLING_MONTHS`, `END_OF_EDITION_PLUS_GRACE`, `NEVER`): vale per i nuovi lotti; `program.config` | wallet §3 · docs/03 §4.1 · BO-08 | CUR |
| R-26 | Vista wallet: saldi per valuta, `expiringSoon` entro 30 giorni, livello con `next {code, threshold, missing}`, moltiplicatore, `keepWarning {tier, missing}` da ottobre se `periodSts` < soglia attuale | wallet §3, §5 · docs/03 §4.3 · PT-01 | WVW |
| R-27 | Libro mastro: filtri `currency, type, from, to`, ordine `occurredAt desc`; lotti non esauriti per scadenza | wallet §3 · BO-03 | API-001…006 |
| R-28 | Portale: scala `{code, name, threshold, multiplier, benefits[], color}`; attività `{…, pending, expiresAt?, breakdown?}` | wallet §3 · PT-07 · PT-08 | API-007…010 |
| R-29 | Distribuzione dei membri per livello; storico livelli per membro | wallet §3 · F-TIER-06 · BO-07 | API-011, TUP-019 |
| R-30 | Passività per valuta: `outstanding`, `pending`, `byExpiryMonth[]` | F-WAL-09 · wallet §3 · BO-08 | LIA |
| R-31 | `member.registered` crea i 2 wallet + `member_tier BASE`; `member.status.changed` aggiorna lo stato | wallet §4 | MBR |
| R-32 | Audit su `lh.audit.v1`: rettifiche, modifiche a livelli/valute/edizioni, **job** | wallet §4 · docs/05 §6 · F-WAL-07 | ADJ, CUR-014, TAD-018, EDN-016, ECL-019, JOB-013 |

## 2. Rami del codice mappati sulle regole

Percorsi relativi a `services/wallet-service/src/main/java/io/loyaltyhub/wallet/`. «Senza specifica» = ramo che nessuna fonte decide; «non raggiungibile» = protetto da un controllo precedente.

| Ramo | Punto (file:riga) | Regola | Righe |
|---|---|---|---|
| B-01 | `application/WalletService.java:108` valuta ≠ PTS → 422 `CURRENCY_NOT_ADJUSTABLE` | R-19 (Q-46) | ADJ-018…020 |
| B-02 | `WalletService.java:112` importo ≤ 0 → 422 | R-02, R-19 | ADJ-023/024 |
| B-03 | `WalletService.java:115` nota nulla o < 10 caratteri dopo `trim` → 422 `NOTE_TOO_SHORT` | R-19; il `trim` è senza specifica | ADJ-028…032 |
| B-04 | `WalletService.java:118` motivo fuori elenco → 422 | R-19 (Q-45) | ADJ-025…027 |
| B-05 | `WalletService.java:122` direzione ≠ CREDIT/DEBIT → 422 | R-19 | ADJ-021/022 |
| B-06 | `WalletService.java:127` membro `ANONYMIZED` → 409 `MEMBER_ANONYMIZED` | R-19 (Q-127) | ADJ-033/034 |
| B-07 | `WalletService.java:131` wallet assente → creato | senza specifica | ADJ-042 |
| B-08 | `WalletService.java:137` attore assente → `SYSTEM` | senza specifica, non raggiungibile via HTTP | — |
| B-09 | `WalletService.java:140` accredito: lotto + `ADJUST_CREDIT` | R-03, R-19 | ADJ (esito 200) |
| B-10 | `WalletService.java:153` addebito oltre il saldo attivo → 422 `INSUFFICIENT_BALANCE` | R-19 | ADJ-035, 038, 039 |
| B-11 | `WalletService.java:157-163` addebito: `ADJUST_DEBIT` + consumo dei lotti | R-07; ordine di consumo senza specifica | ADJ (esito 200), ADJ-043 |
| B-12 | `WalletService.java:174-180` fatto `wallet.points.adjusted` + audit `ADJUST` | R-19, R-32 | ADJ (esito 200) |
| B-13 | `WalletService.java:190` effetto senza `effectId` → ignorato | senza specifica (contratto) | GRT-061 |
| B-14 | `WalletService.java:194` `effectId` già registrato → ignorato | R-09 | GRT-059 |
| B-15 | `WalletService.java:198` subject non di membro → ignorato | senza specifica | GRT-065 |
| B-16 | `WalletService.java:202` valuta assente → `PTS` | senza specifica (contratto la richiede) | — |
| B-17 | `WalletService.java:205-207` wallet e `BASE` al volo | R-09 | GRT-060 |
| B-18 | `WalletService.java:211` livello sconosciuto → moltiplicatore 1 | senza specifica, non raggiungibile coi dati del servizio | — |
| B-19 | `WalletService.java:214-217` moltiplicatore solo con `applies` e `PTS`, per difetto | R-08 | GRT-001…058 |
| B-20 | `WalletService.java:218` importo finale ≤ 0 → nessun movimento | R-10 | GRT-056/057 |
| B-21 | `WalletService.java:224` `time` assente → orologio | senza specifica (envelope lo richiede) | — |
| B-22 | `WalletService.java:226-233` `pendingDays > 0` → lotto `PENDING`, saldo in attesa | R-03 | GRT (pendingDays 1/7) |
| B-23 | `WalletService.java:236-238` `STS` attivo → `periodSts` + salita | R-21 | TUP |
| B-24 | `WalletService.java:243-253` `EARN`, lotto, `wallet.points.earned` | R-02, R-03, R-11 | GRT |
| B-25 | `WalletService.java:269-296` rilascio dei lotti dovuti | R-12 | REL |
| B-26 | `WalletService.java:277-280` rilascio `STS` → `periodSts` + salita | R-21 (tempistica senza specifica) | TUP-016/017 |
| B-27 | `WalletService.java:313-332` un `EXPIRE` **per lotto** | R-16 (divergenza) | EXP |
| B-28 | `WalletService.java:344-360` preavviso nella finestra `(asOf, asOf+30 g]`, flag `warned` | R-17 | WRN |
| B-29 | `WalletService.java:355` lotto senza scadenza nel preavviso | non raggiungibile (la query esclude i null) | — |
| B-30 | `WalletService.java:377` `member_tier` assente → nessuna salita | non raggiungibile (`ensureBase` prima) | — |
| B-31 | `WalletService.java:385` livello guadagnato ≤ attuale → nulla | R-21 | TUP-001/004/007/012/013/015 |
| B-32 | `WalletService.java:388-399` salita al più alto, storico, fatto | R-21 | TUP-002/003/005/006/008…011/014 |
| B-33 | `application/RedemptionPayments.java:74` richiesta incompleta → ignorata | senza specifica | SPD-021/022 |
| B-34 | `RedemptionPayments.java:78-80` wallet creato al volo nella saga | senza specifica | SPD-023 |
| B-35 | `RedemptionPayments.java:81` `SPEND` già registrato → nulla | R-14 | SPD-020 |
| B-36 | `RedemptionPayments.java:86` stato ≠ ACTIVE → `MEMBER_NOT_ACTIVE` | R-14 | SPD-015…018 |
| B-37 | `RedemptionPayments.java:90` disponibile < costo → `INSUFFICIENT_BALANCE` | R-13 | SPD-007/008/012/019 |
| B-38 | `RedemptionPayments.java:95-112` spesa FIFO, `lot_consumption`, `wallet.points.spent` | R-13 | SPD-001…006, 009…011, 013, 014, 024 |
| B-39 | `RedemptionPayments.java:122` `refund` ≠ true → nulla | R-15 | REF-009 |
| B-40 | `RedemptionPayments.java:126` `redemptionId` assente → nulla | senza specifica | — |
| B-41 | `RedemptionPayments.java:130` nessuna spesa registrata → nulla | R-15 | REF-010 |
| B-42 | `RedemptionPayments.java:136` `REFUND` già registrato → nulla | R-15 | REF-008 |
| B-43 | `RedemptionPayments.java:143-149` punti restituiti ai lotti d'origine non scaduti | Q-54, contro R-15 | REF-003, REF-005 |
| B-44 | `RedemptionPayments.java:153-158` punti di lotti scaduti in un lotto con la policy di oggi | Q-54, contro R-15 | REF-004, REF-006 |
| B-45 | `RedemptionPayments.java:159-170` saldo, `REFUND`, `wallet.points.refunded` | R-15 | REF-001/002/007 |
| B-46 | `domain/ExpiryPolicy.java:44` policy nulla → non scade | senza specifica | POL-023 |
| B-47 | `ExpiryPolicy.java:48-51` `ROLLING_MONTHS` (senza `months` → 12) | R-04; default senza specifica | POL-001…014 |
| B-48 | `ExpiryPolicy.java:53-64` `END_OF_EDITION_PLUS_GRACE` | R-05 (Q-47) | POL-015…020 |
| B-49 | `ExpiryPolicy.java:66` altri tipi → non scade | R-06; tipo sconosciuto senza specifica | POL-021…024 |
| B-50 | `domain/EditionCloseRule.java:24-27` livello attuale sconosciuto → primo della scala | senza specifica | CLR-033 |
| B-51 | `EditionCloseRule.java:31-33` livello guadagnato | R-22 | CLR |
| B-52 | `EditionCloseRule.java:35-36` pavimento = rank − 1 | R-22 | CLR |
| B-53 | `EditionCloseRule.java:38` nuovo = max(guadagnato, pavimento) | R-22 | CLR |
| B-54 | `EditionCloseRule.java:41` nessuna salita in chiusura | R-22 | CLR-003…008, 013…016, 023, 024 |
| B-55 | `EditionCloseRule.java:45` esito `RETAINED`/`DOWNGRADED` | R-22 | CLR |
| B-56 | `application/EditionCloseBatchService.java:89` edizione assente → 404 | R-23 (docs/06 §2) | ECL-023 |
| B-57 | `EditionCloseBatchService.java:90` già `CLOSED` → 422 | R-23 | ECL-020/021 |
| B-58 | `EditionCloseBatchService.java:93` non `ACTIVE` → 422 | R-23 | ECL-022 |
| B-59 | `EditionCloseBatchService.java:104` solo membri `ACTIVE` | R-22 | ECL-004, ECL-015 |
| B-60 | `EditionCloseBatchService.java:134` anteprima senza scritture | R-23 | ECL-005 |
| B-61 | `EditionCloseBatchService.java:135-154` livello, `periodSts = 0`, storico, fatto per esito | R-22, R-23 | ECL-013/014 |
| B-62 | `EditionCloseBatchService.java:163-170` attiva la `PLANNED` con inizio minimo | R-23 (divergenza) | ECL-016/017 |
| B-63 | `EditionCloseBatchService.java:172-179` `edition.closed` + audit `TRANSITION` | R-23, R-32 | ECL-018/019 |
| B-64 | `api/EditionsController.java:51-54` applicazione non ADMIN → 403; anteprima per ogni ruolo | R-23; ruolo dell'anteprima senza specifica | ECL-006…011 |
| B-65 | `application/EditionService.java:62-72` campi obbligatori → 422 | R-24 | EDN-008/009 |
| B-66 | `EditionService.java:74` codice già esistente → 409 | senza specifica | EDN-010 |
| B-67 | `EditionService.java:79` nuova edizione `PLANNED` | R-24 | EDN-001/005 |
| B-68 | `EditionService.java:88` modifica di edizione assente → 404 | docs/06 §2 | EDN-015 |
| B-69 | `EditionService.java:109` inizio dopo la fine → 422 | R-24 | EDN-006 |
| B-70 | `EditionService.java:114` sovrapposizione → 422 | R-24 | EDN-002…004, 014 |
| B-71 | `application/TierAdminService.java:63` livello assente → 404 | docs/06 §2 | TAD-014 |
| B-72 | `TierAdminService.java:66` `BASE` con soglia ≠ 0 → 422 | R-20 | TAD-010/011 |
| B-73 | `TierAdminService.java:70` moltiplicatore ≤ 0 → 422 | senza specifica | TAD-015 |
| B-74 | `TierAdminService.java:96` soglie non strettamente crescenti → 422 | R-20 | TAD-001…009 |
| B-75 | `TierAdminService.java:84` audit `UPDATE` | R-32 | TAD-018 |
| B-76 | `application/CurrencyService.java:37` valuta assente → 404 | docs/06 §2 | CUR-012 |
| B-77 | `CurrencyService.java:39` policy assente nel corpo → nessuna modifica | senza specifica | — |
| B-78 | `CurrencyService.java:57` `months` fuori da 1…60 → 422 | limiti senza specifica | CUR-006…009 |
| B-79 | `CurrencyService.java:62` `graceDays` negativo → 422 | R-25 | CUR-013 |
| B-80 | `CurrencyService.java:65` tipo sconosciuto → 422 | R-25 | CUR-005 |
| B-81 | `CurrencyService.java:72` JSON non valido → 422 | senza specifica | — |
| B-82 | `CurrencyService.java:43` audit `UPDATE` | R-32 | CUR-014 |
| B-83 | `application/WalletQueryService.java:48` nessun wallet → 404 | docs/06 §2 | WVW-016 |
| B-84 | `WalletQueryService.java:57-62` `expiringSoon` su `(adesso, adesso+30 g]` | R-26 | WVW-005…008 |
| B-85 | `WalletQueryService.java:67` `member_tier` assente → `BASE` | senza specifica | — |
| B-86 | `WalletQueryService.java:77-83` prossimo livello, mancanti, `progressPct` | R-26; formula di `progressPct` senza specifica | WVW-001…004 |
| B-87 | `api/LiabilityController.java:47` valuta assente → 404 | docs/06 §2 | LIA-005 |
| B-88 | `infra/LiabilityRepository.java:58-78` totali e mese di scadenza (Roma) | R-30 | LIA-001…004, 006, 007 |
| B-89 | `api/WalletJobsController.java:60` `asOf` assente → adesso | R-18 | JOB-012 |
| B-90 | `WalletJobsController.java:63` `asOf` istante ISO | R-18 | REL, EXP, WRN |
| B-91 | `WalletJobsController.java:66` `asOf` data pura → fine giornata a Roma | senza specifica | JOB-014 |
| B-92 | `api/PortalActivityController.java:49` `pending` sempre `false` | R-28 (divergenza) | API-008 |
| B-93 | `PortalActivityController.java:73-95` scomposizione | R-28 | API-009 |
| B-94 | `PortalActivityController.java:42`, `api/WalletsController.java:55` limiti di pagina | senza specifica | — |
| B-95 | `messaging/MemberLifecycleHandler.java:33` evento senza membro → nulla | senza specifica | — |
| B-96 | `MemberLifecycleHandler.java:36` `member.registered` → 2 wallet + `BASE` | R-31 | MBR-001/002 |
| B-97 | `MemberLifecycleHandler.java:38-41` `member.status.changed` (senza `newStatus` → `ACTIVE`) | R-31; default senza specifica | MBR-003 |

**Regole senza codice** (o implementate in modo diverso): `keepWarning` (R-26) · filtri `type`/`from`/`to` del libro mastro (R-27) · azione e attore esposti dal libro mastro (R-02/R-27) · `pending` ed `expiresAt` nell'attività del portale (R-28) · campo `threshold` della scala del portale (R-28) · audit dei job (R-32) · un solo `EXPIRE` per membro/valuta (R-16) · nuovo lotto di rimborso (R-15, sostituito da Q-54) · «la successiva» edizione `ACTIVE` (R-23) · «periodi contigui» (R-24, non imposto: EDN-007) · «solo i membri ACTIVE accumulano» (docs/03 §2) non applicato dal wallet (GRT-049…051).

## 3. Accredito (`points.grant`)

**Regole**: R-03, R-04, R-08, R-09, R-10, R-11 — docs/03 §4.2 («Ogni `EARN` … crea un lotto; `pendingDays > 0` → `PENDING`»; «se l'effetto ha `tierMultiplierApplies` e valuta `PTS`, importo finale = `floor(base × tier.multiplier)`»), wallet §5 («unicità su `effect_id`»; «se il membro non ha wallet → crea wallet e tier BASE on the fly»), wallet §7 (130 PTS SILVER → 162, `metadata.baseAmount = 130`).

| Ingresso | Classi valide | Classi non valide / speciali | Limiti |
|---|---|---|---|
| valuta | `PTS`, `STS` | — (enum del contratto) | — |
| `pendingDays` | 0, 1, n = 7 | — (contratto: ≥ 0) | 0 / 1 |
| `tierMultiplierApplies` | sì, no | — | — |
| livello del membro | `BASE`, `SILVER`, `GOLD`, `PLATINUM` | — | — |
| stato del membro | `ACTIVE` | `BLOCKED`, `INACTIVE`, `ANONYMIZED` | — |
| importo | 130 | 0, −10 | 1, 3, 4 (arrotondamento ×1,25), 1 (×1,5), 1 000 000 000 |
| `effectId` | nuovo | ripetuto, assente | — |
| `subject` | `member:<id>` | non di membro | — |

**Strategia**: tabella decisionale **completa** valuta × `pendingDays` × moltiplicatore × livello = 2 × 3 × 2 × 4 = **48 ≤ 64** (GRT-001…048, membro `ACTIVE`, 130 punti, `periodSts` = soglia del livello così nessun accredito fa salire). Lo stato del membro raddoppierebbe oltre 64: ogni stato non `ACTIVE` è provato **da solo** (guasto singolo, GRT-049…051) sul caso più ricco (PTS, 0, sì, SILVER). Gli importi limite sono provati da soli (GRT-052…058). La scadenza esatta del lotto salvato è provata una volta (GRT-064): nelle righe della tabella si verifica la scadenza portata dal fatto `wallet.points.earned` (valore calcolato), così un difetto di persistenza non fa fallire 24 righe per una sola causa.

Atteso comune alle righe con accredito: un movimento `EARN` con importo atteso, `occurredAt = T0`, `metadata.baseAmount`; un lotto (`PENDING` con `availableAt = T0 + pendingDays` e saldo in attesa, altrimenti `ACTIVE` e saldo attivo); un fatto `wallet.points.earned` con `amount`, `currency`, `pending`; scadenza `PTS` = ultimo istante del **30/09/2027** (Roma), `STS` senza scadenza.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-GRT-001 | PTS · pendingDays 0 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 = floor(130 × 1,00); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-002 | PTS · pendingDays 0 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 162 = floor(130 × 1,25); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-003 | PTS · pendingDays 0 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 195 = floor(130 × 1,50); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-004 | PTS · pendingDays 0 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 260 = floor(130 × 2,00); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-005 | PTS · pendingDays 0 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-006 | PTS · pendingDays 0 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-007 | PTS · pendingDays 0 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-008 | PTS · pendingDays 0 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto ACTIVE, saldo attivo; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-009 | PTS · pendingDays 1 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 = floor(130 × 1,00); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-010 | PTS · pendingDays 1 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 162 = floor(130 × 1,25); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-011 | PTS · pendingDays 1 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 195 = floor(130 × 1,50); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-012 | PTS · pendingDays 1 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 260 = floor(130 × 2,00); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-013 | PTS · pendingDays 1 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-014 | PTS · pendingDays 1 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-015 | PTS · pendingDays 1 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-016 | PTS · pendingDays 1 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 1 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-017 | PTS · pendingDays 7 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 = floor(130 × 1,00); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-018 | PTS · pendingDays 7 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 162 = floor(130 × 1,25); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-019 | PTS · pendingDays 7 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 195 = floor(130 × 1,50); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-020 | PTS · pendingDays 7 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 260 = floor(130 × 2,00); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-021 | PTS · pendingDays 7 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-022 | PTS · pendingDays 7 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-023 | PTS · pendingDays 7 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-024 | PTS · pendingDays 7 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (moltiplicatore non applicato); lotto PENDING, availableAt = time + 7 g, saldo in attesa; scadenza (nel fatto) ultimo istante del 30/09/2027; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-025 | STS · pendingDays 0 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-026 | STS · pendingDays 0 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-027 | STS · pendingDays 0 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-028 | STS · pendingDays 0 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-029 | STS · pendingDays 0 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-030 | STS · pendingDays 0 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-031 | STS · pendingDays 0 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-032 | STS · pendingDays 0 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto ACTIVE, saldo attivo; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-033 | STS · pendingDays 1 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-034 | STS · pendingDays 1 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-035 | STS · pendingDays 1 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-036 | STS · pendingDays 1 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-037 | STS · pendingDays 1 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-038 | STS · pendingDays 1 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-039 | STS · pendingDays 1 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-040 | STS · pendingDays 1 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 1 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-041 | STS · pendingDays 7 · tierMultiplierApplies true · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-042 | STS · pendingDays 7 · tierMultiplierApplies true · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-043 | STS · pendingDays 7 · tierMultiplierApplies true · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-044 | STS · pendingDays 7 · tierMultiplierApplies true · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-045 | STS · pendingDays 7 · tierMultiplierApplies false · BASE · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-046 | STS · pendingDays 7 · tierMultiplierApplies false · SILVER · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-047 | STS · pendingDays 7 · tierMultiplierApplies false · GOLD · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-048 | STS · pendingDays 7 · tierMultiplierApplies false · PLATINUM · ACTIVE | EARN 130 (STS mai moltiplicati); lotto PENDING, availableAt = time + 7 g, saldo in attesa; nessuna scadenza; fatto `wallet.points.earned` coerente | docs/03 §4.2 · F-WAL-03/05 · F-TIER-03 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-049 | PTS · 0 · sì · SILVER · membro `BLOCKED` | AMBIGUO — accreditato 162 come per `ACTIVE` (il wallet non filtra per stato; il filtro è del motore, docs/03 §3.5) | docs/03 §2, §3.5 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-050 | PTS · 0 · sì · SILVER · membro `INACTIVE` | AMBIGUO — accreditato 162 | docs/03 §2, §3.5 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-051 | PTS · 0 · sì · SILVER · membro `ANONYMIZED` | AMBIGUO — accreditato 162 | docs/03 §2, §3.5 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-052 | PTS · sì · SILVER · importo 1 | EARN 1 = floor(1,25) | docs/03 §4.2 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-053 | PTS · sì · SILVER · importo 3 | EARN 3 = floor(3,75) | docs/03 §4.2 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-054 | PTS · sì · SILVER · importo 4 | EARN 5 (prodotto esatto) | docs/03 §4.2 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-055 | PTS · sì · GOLD · importo 1 | EARN 1 = floor(1,5) | docs/03 §4.2 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-056 | PTS · sì · SILVER · importo 0 | nessun movimento, nessun lotto, nessun fatto | docs/03 §3.4 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-057 | PTS · no · BASE · importo −10 | nessun movimento, nessun lotto, nessun fatto | docs/03 §3.4 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-058 | PTS · sì · PLATINUM · importo 1 000 000 000 | EARN 2 000 000 000 | docs/03 §4.2 | `TestbookWalAccrualIT.grants` |
| TB-WAL-GRT-059 | stesso `effectId` applicato due volte | un solo `EARN`, un lotto, un fatto; saldo accreditato una volta | wallet §5, §7 | `TestbookWalAccrualIT.grantIdempotent` |
| TB-WAL-GRT-060 | membro senza wallet né livello | wallet `PTS` creato con 100, livello `BASE`, `periodSts` 0 | wallet §5 | `TestbookWalAccrualIT.grantCreatesWalletOnTheFly` |
| TB-WAL-GRT-061 | effetto senza `effectId` | AMBIGUO — ignorato: nessun movimento, saldo 0 | contracts `effect.points.grant` | `TestbookWalAccrualIT.grantWithoutEffectIdIgnored` |
| TB-WAL-GRT-062 | `time` dell'effetto 10/12/2025 09:00 con «oggi» 24/09/2026 | `earned_at` e `occurredAt` = 10/12/2025; scadenza ultimo istante 31/12/2026; `time` del fatto = data di business | docs/03 §3.1 · docs/05 §2 | `TestbookWalAccrualIT.grantUsesBusinessTime` |
| TB-WAL-GRT-063 | SILVER, `baseAmount` 65, `campaignMultiplier` 2,0, `amount` 130, moltiplicatore sì | EARN 162; metadata `{baseAmount 65, tierCode SILVER, tierMultiplier 1,25, campaignMultiplier 2,0}` | docs/03 §4.2 | `TestbookWalAccrualIT.grantMetadataWithCampaignMultiplier` |
| TB-WAL-GRT-064 | lotto PTS guadagnato a T0, letto da `GET /v1/wallets/{id}/lots` | `expiresAt` = ultimo istante del 30/09/2027 (Roma) | docs/03 §4.1 · wallet §5 | `TestbookWalAccrualIT.persistedLotExpiryIsLastInstantOfMonth` |
| TB-WAL-GRT-065 | effetto con `subject` `campaign:…` | AMBIGUO — ignorato, nessun movimento | docs/05 §1 | `TestbookWalAccrualIT.grantWithoutMemberSubjectIgnored` |

## 4. Calcolo della scadenza (logica pura)

**Regole**: R-04, R-05, R-06 — docs/03 §4.1 (`PTS` `ROLLING_MONTHS 12` «fine mese»; `END_OF_EDITION_PLUS_GRACE` «scadono a `edition.redemptionGraceUntil`»; `NEVER`; `STS` nessuna), wallet §5 («ultimo istante del mese di `earned_at + n mesi`, in `Europe/Rome`»), Q-47. «Ultimo istante» è verificato come «cade nel giorno atteso ed entro il suo ultimo secondo» (tollera la rappresentazione a secondi di `docs/05 §2`, `2027-09-30T21:59:59Z`).

| Ingresso | Classi | Limiti e valori speciali |
|---|---|---|
| tipo di policy | `ROLLING_MONTHS`, `END_OF_EDITION_PLUS_GRACE`, `NEVER`, `EDITION` (STS) | policy assente, tipo sconosciuto |
| `months` | 1, 12 | assente |
| istante di guadagno (Roma) | metà mese | 00:00 del primo e 23:59:59 dell'ultimo giorno del mese (≠ mese UTC), giorno del cambio d'ora di marzo e ottobre, 31 gennaio (+1 mese), 29 febbraio, 31 dicembre / 1 gennaio |
| edizioni | seed con `redemptionGraceUntil` | senza grace (con e senza `graceDays`), data non coperta |

**Strategia**: partizione per tipo di policy; per `ROLLING_MONTHS` ogni valore limite di calendario provato da solo; per `END_OF_EDITION_PLUS_GRACE` estremi dell'edizione e varianti di Q-47. 24 righe.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-POL-001 | ROLLING 12 · 18/09/2026 12:15 | ultimo istante del 30/09/2027 | wallet §5 · docs/05 §2 | `TestbookWalPolicyTest` |
| TB-WAL-POL-002 | ROLLING 12 · 15/01/2026 | 31/01/2027 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-003 | ROLLING 12 · 01/09/2026 00:00 Roma (31/08 in UTC) | 30/09/2027 (mese di Roma) | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-004 | ROLLING 12 · 31/08/2026 23:59:59 Roma | 31/08/2027 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-005 | ROLLING 12 · 29/03/2026 03:30 (cambio d'ora) | 31/03/2027, in CEST | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-006 | ROLLING 12 · 26/10/2025 12:00 (cambio d'ora) | 31/10/2026, in CET | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-007 | ROLLING 12 · 10/02/2027 | 29/02/2028 (bisestile) | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-008 | ROLLING 12 · 29/02/2028 | 28/02/2029 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-009 | ROLLING 1 · 31/01/2026 | 28/02/2026 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-010 | ROLLING 1 · 31/01/2028 | 29/02/2028 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-011 | ROLLING 12 · 31/12/2026 23:59:59 Roma | 31/12/2027 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-012 | ROLLING 12 · 01/01/2027 00:00 Roma | 31/01/2028 | wallet §5 | `TestbookWalPolicyTest` |
| TB-WAL-POL-013 | ROLLING senza `months` · 05/05/2026 | AMBIGUO — 12 mesi: 31/05/2027 | — | `TestbookWalPolicyTest` |
| TB-WAL-POL-014 | policy `PTS` del seed · 24/09/2026 | 30/09/2027 (BO-08: «guadagnati oggi → scadono il 30 set 2027») | seed · BO-08 | `TestbookWalPolicyTest` |
| TB-WAL-POL-015 | END_OF_EDITION_PLUS_GRACE · 15/06/2026 | fine di `redemptionGraceUntil` di ED-2026: 31/01/2027 | docs/03 §4.1 | `TestbookWalPolicyTest` |
| TB-WAL-POL-016 | END_OF_EDITION_PLUS_GRACE · 31/12/2026 23:59:59 Roma | ED-2026 → 31/01/2027 | docs/03 §4.1 | `TestbookWalPolicyTest` |
| TB-WAL-POL-017 | END_OF_EDITION_PLUS_GRACE · 01/01/2027 00:00 Roma | ED-2027 → 31/01/2028 | docs/03 §4.1 | `TestbookWalPolicyTest` |
| TB-WAL-POL-018 | END_OF_EDITION_PLUS_GRACE · 01/06/2024 (nessuna edizione) | non scade | Q-47 | `TestbookWalPolicyTest` |
| TB-WAL-POL-019 | END_OF_EDITION_PLUS_GRACE `graceDays` 10, edizioni senza grace · 15/06/2026 | `endDate` + 10 = 10/01/2027 | Q-47 | `TestbookWalPolicyTest` |
| TB-WAL-POL-020 | END_OF_EDITION_PLUS_GRACE senza `graceDays`, edizioni senza grace | `endDate` = 31/12/2026 | Q-47 | `TestbookWalPolicyTest` |
| TB-WAL-POL-021 | `NEVER` | non scade | docs/03 §4.1 | `TestbookWalPolicyTest` |
| TB-WAL-POL-022 | policy `STS` del seed (`EDITION`) | non scade | docs/03 §4.1 | `TestbookWalPolicyTest` |
| TB-WAL-POL-023 | valuta senza policy | AMBIGUO — non scade | — | `TestbookWalPolicyTest` |
| TB-WAL-POL-024 | tipo sconosciuto `WEEKLY` | AMBIGUO — non scade | — | `TestbookWalPolicyTest` |

## 5. Job di scadenza

**Regole**: R-16, R-04 — docs/03 §4.2 («lotti `ACTIVE` con `expiresAt ≤ asOf` → `EXPIRED`, un movimento `EXPIRE` per membro/valuta con il totale»), F-WAL-06, EVT-FACT-24 (`currency, amount, balanceAfter`). «Ultimo istante del mese» ⇒ alle 23:59:59 dell'ultimo giorno il lotto è ancora valido, alle 00:00 del giorno dopo è scaduto.

| Ingresso | Classi | Limiti |
|---|---|---|
| `asOf` rispetto alla scadenza | prima, dopo | 23:59:59 dell'ultimo giorno, 00:00 del giorno dopo |
| mese di scadenza | CET (ottobre), CEST (marzo), febbraio bisestile, mese di Roma ≠ mese UTC | 00:00 del 29/02 |
| stato del lotto | `ACTIVE` | `EXHAUSTED`, `PENDING`, consumato in parte, senza scadenza (`STS`) |
| lotti dello stesso membro | 1 | 2 scaduti insieme |
| esecuzioni | 1 | ripetuta con lo stesso `asOf` |

**Strategia**: ogni confine di calendario da solo (coppie 23:59:59 / 00:00), ogni stato del lotto da solo, più la ripetizione. Il lotto delle righe 001–010 nasce da un vero accredito (policy del seed) e il job è chiamato via `POST /v1/demo/jobs/expire-points?asOf=` con `ADMIN`. Nelle righe con scadenza: lotto `EXPIRED`, un `EXPIRE` «−» di 100 con `balanceAfter` 0, `lifetime_expired` 100, fatto `wallet.points.expired`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-EXP-001 | guadagnato 15/10/2025 (scade fine ottobre 2026, CET) · job 31/10/2026 23:59:59 | non scade | docs/03 §4.2 · wallet §5 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-002 | idem · job 01/11/2026 00:00 | scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-003 | guadagnato 10/03/2026 (fine marzo 2027, CEST) · job 31/03/2027 23:59:59 | non scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-004 | idem · job 01/04/2027 00:00 | scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-005 | guadagnato 10/02/2027 (scade 29/02/2028) · job 29/02/2028 00:00 | non scade (non è il 28) | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-006 | idem · job 29/02/2028 23:59:59 | non scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-007 | idem · job 01/03/2028 00:00 | scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-008 | guadagnato 01/09/2026 00:30 Roma (31/08 UTC) · job 01/09/2027 00:00 | non scade (mese di Roma: settembre) | wallet §5 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-009 | idem · job 01/10/2027 00:00 | scade | wallet §5 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-010 | guadagnato 18/09/2026 · job 31/12/2026 | non scade | docs/03 §4.2 | `TestbookWalAccrualIT.expiryBoundary` |
| TB-WAL-EXP-011 | stesso membro, lotti 100 (scad. 10/10) e 200 (scad. 20/10) · job 21/10/2026 | **un** movimento `EXPIRE` di 300; saldo 0 | docs/03 §4.2 | `TestbookWalAccrualIT.expiryOneMovementPerMember` |
| TB-WAL-EXP-012 | lotto 500 di cui 200 spesi · job dopo la scadenza | `EXPIRE` 300; saldo 0; `lifetime_expired` 300 | docs/03 §4.2 | `TestbookWalAccrualIT.expiryOfPartiallySpentLot` |
| TB-WAL-EXP-013 | lotto esaurito dalla spesa · job dopo la scadenza | resta `EXHAUSTED`, nessun `EXPIRE` | docs/03 §4.2 | `TestbookWalAccrualIT.expiryIgnoresExhausted` |
| TB-WAL-EXP-014 | lotto `PENDING` con scadenza passata | resta `PENDING` (solo gli `ACTIVE` scadono), saldo in attesa invariato | docs/03 §4.2 | `TestbookWalAccrualIT.expiryIgnoresPending` |
| TB-WAL-EXP-015 | job ripetuto con lo stesso `asOf` | un solo `EXPIRE`, `lifetime_expired` 100 | F-WAL-06 | `TestbookWalAccrualIT.expiryIdempotent` |
| TB-WAL-EXP-016 | accredito `STS` · job al 01/01/2035 | lotto `ACTIVE`, saldo STS invariato | docs/03 §4.1 | `TestbookWalAccrualIT.stsNeverExpires` |

## 6. Job: ruoli, data di riferimento, audit

**Regole**: R-18, R-32 — wallet §3 («Demo (ruolo `ADMIN`) … eseguono i job con data di riferimento»), docs/06 §3 («`/v1/demo/**` ⇒ `ADMIN`»; header assente → `ANALYST:anonymous`), docs/08 §2 (`demo.admin` solo ADMIN), wallet §4 («Produce `lh.audit.v1` | … job»).

| Ingresso | Classi valide | Classi non valide |
|---|---|---|
| ruolo | `ADMIN` | `CARE`, `MARKETING`, `LEGAL`, `ANALYST`, header assente, header non valido |
| job | `expire-points`, `release-pending`, `expiry-warnings` | — |
| `asOf` | istante ISO | assente, data pura |

**Strategia**: tutti i ruoli sul job delle scadenze (`asOf` nel 2000: nessun effetto sui dati); per gli altri due job `ADMIN` più un ruolo negato (coppie ruolo × job). 403 con codice `FORBIDDEN_ROLE` (docs/06 §2).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-JOB-001 | scadenze · `ADMIN` | 200 | wallet §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-002 | scadenze · `CARE` | 403 `FORBIDDEN_ROLE` | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-003 | scadenze · `MARKETING` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-004 | scadenze · `LEGAL` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-005 | scadenze · `ANALYST` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-006 | scadenze · senza `X-LH-Actor` | 403 (anonimo = ANALYST) | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-007 | scadenze · `X-LH-Actor: PIRATE:jack` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-008 | rilascio · `ADMIN` | 200 | wallet §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-009 | rilascio · `MARKETING` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-010 | preavvisi · `ADMIN` | 200 | wallet §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-011 | preavvisi · `CARE` | 403 | docs/06 §3 | `TestbookWalAccrualIT.jobRoles` |
| TB-WAL-JOB-012 | scadenze senza `asOf`; lotti in scadenza 1 s prima e 1 s dopo «adesso» | scade solo il primo; saldo 40 | F-DEMO-06 | `TestbookWalAccrualIT.jobWithoutAsOfUsesNow` |
| TB-WAL-JOB-013 | esecuzione di un job | una voce di audit con `action = JOB` | wallet §4 · docs/05 §6 | `TestbookWalAccrualIT.jobIsAudited` |
| TB-WAL-JOB-014 | `asOf=2026-10-31` (data pura); lotto che scade il 31/10 23:59:59 | AMBIGUO — fine giornata a Roma: il lotto scade | — | `TestbookWalAccrualIT.jobAsOfDateOnly` |

## 7. Preavvisi di scadenza

**Regole**: R-17 — F-WAL-06 («preavviso a 30 giorni (fatto `wallet.points.expiring`)»), wallet §5 («una volta per lotto: flag su `points_lot`»), EVT-FACT-25 (`currency, amount, expiresAt`). Finestra: lotti `ACTIVE` che scadono dopo `asOf` ed entro `asOf + 30 giorni`.

| Ingresso | Classi | Limiti |
|---|---|---|
| scadenza − `asOf` | dentro la finestra, fuori | 0, +1 s, +30 g, +30 g +1 s, −1 g |
| stato / valuta | `ACTIVE PTS` | `PENDING`, `STS` senza scadenza |
| esecuzioni | 1 | 2 |

**Strategia**: ogni limite da solo con `asOf = 01/10/2026 09:00` (Roma); stati non validi da soli; ripetizione e contenuto del fatto.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-WRN-001 | scadenza = `asOf` + 30 g | preavvisato | F-WAL-06 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-002 | scadenza = `asOf` + 30 g + 1 s | non preavvisato | F-WAL-06 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-003 | scadenza = `asOf` + 1 s | preavvisato | F-WAL-06 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-004 | scadenza = `asOf` (già dovuto) | non preavvisato | F-WAL-06 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-005 | scadenza = `asOf` − 1 g, non ancora spazzato | non preavvisato | F-WAL-06 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-006 | lotto `PENDING` con scadenza a +10 g | non preavvisato | F-WAL-06 · docs/03 §4.2 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-007 | lotto `STS` senza scadenza | non preavvisato | docs/03 §4.1 | `TestbookWalAccrualIT.warningsWindow` |
| TB-WAL-WRN-008 | due esecuzioni (`asOf` e `asOf` + 1 g) | un solo preavviso | wallet §5 | `TestbookWalAccrualIT.warningOncePerLot` |
| TB-WAL-WRN-009 | lotto 500 di cui 150 spesi, scadenza +10 g | fatto `{currency PTS, amount 350, expiresAt}` | EVT-FACT-25 | `TestbookWalAccrualIT.warningFactContent` |

## 8. Rilascio dei punti in attesa

**Regole**: R-12 — F-WAL-05, docs/03 §4.2 (`RELEASE` «pending → attivo, importo informativo»), wallet §5 («rilascio pending ogni ora»), EVT-FACT-27.

| Ingresso | Classi | Limiti |
|---|---|---|
| `asOf` rispetto ad `availableAt` | prima, dopo | −1 s, 0, +1 s |
| esecuzioni | 1 | 2 |

**Strategia**: i tre limiti da soli (accredito PTS 100 con `pendingDays` 2, job via HTTP), più effetti e ripetizione.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-REL-001 | `asOf` = `availableAt` − 1 s | resta `PENDING`: attivo 0, in attesa 100, nessun `RELEASE` | docs/03 §4.2 | `TestbookWalAccrualIT.releaseBoundary` |
| TB-WAL-REL-002 | `asOf` = `availableAt` | `ACTIVE`: attivo 100, in attesa 0, un `RELEASE` | docs/03 §4.2 | `TestbookWalAccrualIT.releaseBoundary` |
| TB-WAL-REL-003 | `asOf` = `availableAt` + 1 s | come REL-002 | docs/03 §4.2 | `TestbookWalAccrualIT.releaseBoundary` |
| TB-WAL-REL-004 | rilascio di 100 | `RELEASE` «+» 100 con `balanceAfter` 100; `lifetimeEarned` resta 100; fatto `{currency, amount 100, balanceAfter 100}` | docs/03 §4.2 · EVT-FACT-27 | `TestbookWalAccrualIT.releaseEffects` |
| TB-WAL-REL-005 | rilascio ripetuto con lo stesso `asOf` | un solo `RELEASE`, attivo 100 | F-WAL-05 | `TestbookWalAccrualIT.releaseIdempotent` |

## 9. Salita immediata di livello

**Regole**: R-21, R-08 — docs/03 §4.3 («dopo ogni accredito `STS`, se `periodSts` ≥ soglia di un tier di rank superiore → nuovo tier (il più alto raggiunto), fatto `tier.upgraded`»), F-TIER-02, wallet §5 («verifica salita nello stesso commit»), wallet §7 (Giulia SILVER 2 880 + 130 → GOLD), F-TIER-06.

| Ingresso | Classi | Limiti |
|---|---|---|
| livello di partenza | BASE, SILVER, GOLD, PLATINUM | — |
| `periodSts` finale | sotto soglia, in soglia, sopra | soglia − 1, soglia, soglia + 1 (1 000 / 3 000 / 7 000) |
| salto | un livello | due, tre livelli in un accredito |
| valuta | STS | PTS (non fa salire) |
| stato del lotto STS | attivo | in attesa |

**Strategia**: per ogni soglia la terna soglia − 1 / soglia / soglia + 1 dal livello immediatamente inferiore (9 righe); salti multipli, livello massimo, livello «alto con STS bassi» (dopo una chiusura), incremento minimo di 1, PTS: ciascuno da solo. Interazione con il moltiplicatore (TUP-018) e con il rilascio (TUP-016/017) in righe proprie. Atteso con salita: un solo `tier.upgraded {previousTier, newTier, periodSts}` e una voce `UPGRADE` nello storico.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-TUP-001 | BASE, 0 → 999 STS | resta BASE, nessun fatto | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-002 | BASE, 0 → 1 000 | SILVER; `tier.upgraded BASE→SILVER, periodSts 1000` | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-003 | BASE, 0 → 1 001 | SILVER | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-004 | SILVER, 1 000 → 2 999 | resta SILVER | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-005 | SILVER, 1 000 → 3 000 | GOLD; `SILVER→GOLD` | docs/03 §4.3 · wallet §7 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-006 | SILVER, 1 000 → 3 001 | GOLD | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-007 | GOLD, 3 000 → 6 999 | resta GOLD | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-008 | GOLD, 3 000 → 7 000 | PLATINUM; `GOLD→PLATINUM` | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-009 | GOLD, 3 000 → 7 001 | PLATINUM | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-010 | BASE, 0 → 3 000 in un accredito | GOLD direttamente; un solo fatto `BASE→GOLD` | docs/03 §4.3 («il più alto raggiunto») | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-011 | BASE, 0 → 7 001 in un accredito | PLATINUM; un solo fatto `BASE→PLATINUM` | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-012 | PLATINUM, 7 000 → 12 000 | resta PLATINUM, nessun fatto | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-013 | GOLD con `periodSts` 0 → 1 000 | resta GOLD (nessuna discesa né salita fuori chiusura) | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-014 | BASE, 999 + 1 STS | SILVER | docs/03 §4.3 | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-015 | BASE, 999 STS + 100 000 PTS | resta BASE, `periodSts` 999 | docs/03 §4.3 (solo accrediti `STS`) | `TestbookWalAccrualIT.tierUpgrade` |
| TB-WAL-TUP-016 | BASE · 1 000 STS con `pendingDays` 3 | AMBIGUO — nessuna salita finché `PENDING`, `periodSts` 0 | docs/03 §4.3 | `TestbookWalAccrualIT.pendingStsDoesNotUpgrade` |
| TB-WAL-TUP-017 | idem, poi rilascio | AMBIGUO — al rilascio `periodSts` 1 000 e salita a SILVER | docs/03 §4.3 | `TestbookWalAccrualIT.releasedStsUpgrades` |
| TB-WAL-TUP-018 | BASE → 3 000 STS (sale a GOLD), poi 130 PTS con moltiplicatore | 195 = floor(130 × 1,50) | docs/03 §4.2–4.3 · F-TIER-03 | `TestbookWalAccrualIT.pointsAfterUpgradeUseNewMultiplier` |
| TB-WAL-TUP-019 | salita BASE → SILVER, `GET /v1/members/{id}/tier-history` | una voce `UPGRADE` da BASE a SILVER | F-TIER-06 | `TestbookWalAccrualIT.tierHistoryShowsUpgrade` |

## 10. Spesa FIFO (saga della richiesta premio)

**Regole**: R-13, R-14, R-07 — docs/03 §4.2 («consuma i lotti `ACTIVE` per `expiresAt` crescente (null per ultimi), poi `earnedAt`. Tutto-o-niente: saldo insufficiente → nessun movimento, fatto `wallet.spend.rejected`»), wallet §5 («valuta sempre `PTS`; idempotenza su `redemption_id`»), wallet §7 (1 500 su [500 ott, 800 dic, 900 mar] → 500 + 800 + 200), EVT-FACT-21/22, docs/03 §2 (solo `ACTIVE` spendono).

| Ingresso | Classi valide | Non valide / speciali | Limiti |
|---|---|---|---|
| costo vs saldo attivo | < saldo, = saldo | > saldo | 1; = primo lotto; primo lotto + 1; saldo; saldo + 1; molto oltre |
| lotti | scadenze diverse | stessa scadenza (parità), senza scadenza, `PENDING`, `EXHAUSTED`, `EXPIRED`, già scaduto non spazzato, solo `STS` | ordine d'inserimento e di guadagno opposti alla scadenza |
| stato del membro | `ACTIVE` | `BLOCKED`, `INACTIVE`, `ANONYMIZED` | — |
| richiesta | nuova | ripetuta, senza `redemptionId`, costo 0, membro senza wallet | — |

**Strategia**: tabella decisionale sul costo con i lotti di accettazione (limiti del costo, SPD-001…008); ogni configurazione di lotti che mette alla prova l'ordine FIFO da sola (SPD-009…014, 024); ogni stato non `ACTIVE` da solo, più l'interazione stato × saldo (SPD-018); richieste anomale da sole. Atteso di una spesa: un `SPEND` «−» `PTS` con `redemption_id` e `balanceAfter`; residui dei lotti come indicato; `lot_consumption` identico; saldo = Σ lotti attivi; fatto `wallet.points.spent {ledgerEntryId, currency, amount, balanceAfter, redemptionId}`. Atteso di un rifiuto: fatto `wallet.spend.rejected {redemptionId, reason, requested, available}`, nessun movimento, lotti e saldo invariati.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-SPD-001 | lotti A 500 (scad. 31/10/26), B 800 (31/12/26), C 900 (31/03/27) · costo 1 500 | A 500, B 800, C 200; saldo 700 | wallet §7 · docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-002 | stessi lotti inseriti in ordine C, B, A · 1 500 | A 500, B 800, C 200 | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-003 | lotti di SPD-001 · costo 1 | A 1 | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-004 | · costo 500 | A 500 (`EXHAUSTED`), B intatto | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-005 | · costo 501 | A 500, B 1 | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-006 | · costo 2 200 (= saldo) | tutti esauriti, saldo 0 | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-007 | · costo 2 201 | rifiuto `INSUFFICIENT_BALANCE`, `available` 2 200 | docs/03 §4.2 · EVT-FACT-22 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-008 | · costo 10 000 | rifiuto `INSUFFICIENT_BALANCE`, `available` 2 200 | wallet §7 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-009 | X 300 (guad. 10/01/26) e Y 300 (guad. 05/12/25), stessa scadenza 31/12/26 · 400 | Y 300, X 100 (parità → `earnedAt`) | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-010 | N 1 000 senza scadenza (guad. 2025) e A 500 (31/10/26) · 600 | A 500, N 100 (null per ultimi) | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-011 | P 1 000 `PENDING` (scad. 15/10/26) e A 500 `ACTIVE` · 400 | A 400; P intatto | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-012 | idem · 501 | rifiuto `INSUFFICIENT_BALANCE`, `available` 500 | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-013 | E 200 `ACTIVE` scaduto il 01/09/26 non spazzato, A 500 · 300 | E 200, A 100 (è ancora `ACTIVE`) | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-014 | X `EXHAUSTED`, Z `EXPIRED`, A 500 · 500 | A 500; X e Z intatti | docs/03 §4.2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-015 | membro `BLOCKED`, 500 disponibili · 100 | rifiuto `MEMBER_NOT_ACTIVE`, `available` 500 | docs/03 §2 · EVT-FACT-22 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-016 | membro `INACTIVE` · 100 | rifiuto `MEMBER_NOT_ACTIVE` | docs/03 §2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-017 | membro `ANONYMIZED` · 100 | rifiuto `MEMBER_NOT_ACTIVE` | docs/03 §2 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-018 | membro `BLOCKED` e saldo insufficiente · 900 | AMBIGUO — prevale `MEMBER_NOT_ACTIVE` | EVT-FACT-22 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-019 | solo 5 000 `STS`, 0 `PTS` · 100 | rifiuto `INSUFFICIENT_BALANCE`, `available` 0 | wallet §5 · F-WAL-01 | `TestbookWalSpendIT.spend` |
| TB-WAL-SPD-020 | stessa `redemptionId` elaborata due volte | un solo `SPEND`, un solo fatto, saldo 300 | wallet §5 | `TestbookWalSpendIT.spendIdempotent` |
| TB-WAL-SPD-021 | richiesta senza `redemptionId` | AMBIGUO — ignorata: nessun movimento né fatto | contracts `reward.redemption.requested` | `TestbookWalSpendIT.spendWithoutRedemptionIdIgnored` |
| TB-WAL-SPD-022 | `pointsCost` 0 | AMBIGUO — ignorata | contracts | `TestbookWalSpendIT.spendZeroCostIgnored` |
| TB-WAL-SPD-023 | membro senza wallet · 100 | AMBIGUO — rifiuto `INSUFFICIENT_BALANCE`, `available` 0 | — | `TestbookWalSpendIT.spendWithoutWallet` |
| TB-WAL-SPD-024 | Y 600 (guad. 01/12/25, scad. 31/12/26) e X 400 (guad. 01/03/26, scad. 31/10/26) · 500 | X 400, Y 100 (vince la scadenza sull'anzianità) | docs/03 §4.2 | `TestbookWalSpendIT.spend` |

## 11. Rimborso

**Regole**: R-15 — docs/03 §4.2 («Ogni `EARN`/`ADJUST_CREDIT`/`REFUND` crea un lotto»; «Rimborso: nuovo lotto con `expiresAt` = max(scadenza originaria più lontana tra i lotti consumati, oggi + 30 giorni)»), F-WAL-08, F-RWD-07, EVT-FACT-23. Q-54 registra un'altra scelta (punti «nei lotti d'origine ancora validi», lotto nuovo con la policy di oggi per quelli scaduti) ma parte dall'assunto che «nessuna fonte dice cosa fare»: docs/03 §4.2 lo dice, quindi l'oracolo resta docs/03 (§20, D-14…D-17).

| Ingresso | Classi | Limiti |
|---|---|---|
| lotti consumati | interi, in parte | scadenza più lontana > oggi + 30 g, < oggi + 30 g, già scaduta al rimborso |
| annullo | `refund=true` | `refund=false`, senza spesa, ripetuto |

**Strategia**: ogni combinazione lotti × scadenza da sola; l'atteso sul saldo e sull'atteso sul lotto sono in righe distinte, così la divergenza sul lotto non nasconde la correttezza del saldo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-REF-001 | spesa 1 500 di SPD-001, poi annullo con rimborso | saldo attivo di nuovo 2 200 | F-WAL-08 · F-RWD-07 | `TestbookWalSpendIT.refundRestoresBalance` |
| TB-WAL-REF-002 | idem | un `REFUND` «+» 1 500 con `redemption_id` e `balanceAfter` 2 200; fatto `{redemptionId, amount 1500, balanceAfter 2200}` | docs/03 §4.2 · EVT-FACT-23 | `TestbookWalSpendIT.refundMovementAndFact` |
| TB-WAL-REF-003 | idem (scadenza più lontana consumata: C, 31/03/2027) | nuovo lotto `ACTIVE` 1 500 con scadenza 31/03/2027 23:59:59; A e B restano esauriti, C a 700 | docs/03 §4.2 | `TestbookWalSpendIT.refundCreatesNewLotWithFarthestExpiry` |
| TB-WAL-REF-004 | lotto 300 che scade fra 10 giorni, spesa 200, rimborso | nuovo lotto 200 con scadenza oggi + 30 giorni | docs/03 §4.2 | `TestbookWalSpendIT.refundExpiryAtLeastThirtyDays` |
| TB-WAL-REF-005 | lotto 500 (31/12/2026), spesa 200, rimborso | nuovo lotto 200 con scadenza 31/12/2026; il lotto d'origine resta a 300 | docs/03 §4.2 | `TestbookWalSpendIT.refundOfPartialLotSpend` |
| TB-WAL-REF-006 | lotto 500 (31/10/2026), spesa 300, residuo scaduto il 01/11, rimborso il 02/11 | nuovo lotto 300 con scadenza max(31/10/2026, 02/11 + 30 g) = 02/12/2026 | docs/03 §4.2 | `TestbookWalSpendIT.refundAfterOriginExpiredLotExpiry` |
| TB-WAL-REF-007 | idem | saldo attivo 300 = Σ lotti attivi | F-WAL-08 · docs/03 §4.2 | `TestbookWalSpendIT.refundAfterOriginExpiredBalance` |
| TB-WAL-REF-008 | annullo con rimborso ripetuto | un solo `REFUND`, saldo 2 200 | wallet §5 (idempotenza) | `TestbookWalSpendIT.refundIdempotent` |
| TB-WAL-REF-009 | annullo con `refund=false` | nessun `REFUND`, saldo 700 | wallet §4 | `TestbookWalSpendIT.cancelWithoutRefund` |
| TB-WAL-REF-010 | annullo con rimborso di una richiesta mai spesa | nessun movimento, saldo invariato | F-WAL-08 | `TestbookWalSpendIT.refundWithoutSpend` |
| TB-WAL-REF-011 | dopo il rimborso di REF-001 | saldo attivo = Σ lotti `ACTIVE` | docs/03 §4.2 | `TestbookWalSpendIT.refundKeepsInvariant` |

## 12. Rettifiche manuali

**Regole**: R-19, R-32 — wallet §3 (`{currency, direction (CREDIT/DEBIT), amount, reason (GOODWILL/CORRECTION/COMPLAINT/TEST), note ≥ 10 caratteri}` — ruoli `CARE/ADMIN`; `422 INSUFFICIENT_BALANCE`, `NOTE_TOO_SHORT`), F-WAL-07 («motivo obbligatorio; solo CARE/ADMIN; sempre in audit»), docs/03 §4.2 («Un addebito non può portare il saldo sotto zero»), wallet §2 (importo sempre positivo), Q-45 (elenco dei motivi della scheda servizio), Q-46 (solo `PTS`), Q-127 (anonimizzato → 409 `MEMBER_ANONYMIZED`, altri stati rettificabili), docs/06 §3 (header assente → ANALYST).

| Ingresso | Classi valide | Classi non valide | Limiti / speciali |
|---|---|---|---|
| ruolo | `ADMIN`, `CARE` | `MARKETING`, `LEGAL`, `ANALYST`, assente, non valido | — |
| valuta | `PTS` | `STS` (Q-46), `XYZ`, assente | — |
| direzione | `CREDIT`, `DEBIT` | assente, `SIDEWAYS` | — |
| importo | > 0 | 0, negativo | 1; 1 000 000 000; addebito = saldo − 1 / saldo / saldo + 1; saldo 0; saldo attivo coperto solo dai pending |
| motivo | `GOODWILL`, `CORRECTION`, `COMPLAINT`, `TEST` | assente, `FRAUD` (elenco di docs/03, Q-45), sconosciuto | — |
| nota | 10, 11, 500 caratteri, 10 lettere accentate | assente, vuota, 9 caratteri | solo spazi, 9 caratteri tra spazi |
| stato del membro | `ACTIVE`, `INACTIVE`, `BLOCKED` (Q-127) | `ANONYMIZED` | senza wallet |

**Strategia**: il prodotto supera 64 → **tutte le coppie** sulle classi valide ruolo (2) × direzione (2) × motivo (4) × stato (3), con la nota variata tra le 4 classi valide: 12 righe coprono ogni coppia (ADJ-001…012; riduzione da 48 combinazioni). Poi ogni classe non valida **da sola** sulla riga base `CARE · PTS · CREDIT · 100 · GOODWILL · nota 20 · ACTIVE` (ADJ-013…034) e ogni limite **da solo** (ADJ-035…042; i limiti 10 e 11 della nota sono già nelle coppie). Esito 200: saldo attivo ± importo, movimento `ADJUST_CREDIT`/`ADJUST_DEBIT` `MANUAL` con attore e `metadata {reason, note}`, lotto nuovo con scadenza (accredito) o consumo pari all'importo (addebito), saldo = Σ lotti attivi, fatto `wallet.points.adjusted`, audit `ADJUST`. Esito d'errore: nessun movimento, saldo invariato, nessun fatto; codice d'errore verificato solo dove una fonte lo nomina.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-ADJ-001 | ADMIN · CREDIT · GOODWILL · ACTIVE · nota 10 | 200; saldo 1 100 | wallet §3 · F-WAL-07 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-002 | CARE · DEBIT · GOODWILL · INACTIVE · nota 11 | 200; saldo 900 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-003 | CARE · CREDIT · GOODWILL · BLOCKED · nota accentata | 200; saldo 1 100 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-004 | CARE · DEBIT · CORRECTION · ACTIVE · nota 500 | 200; saldo 900 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-005 | ADMIN · CREDIT · CORRECTION · INACTIVE | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-006 | ADMIN · DEBIT · CORRECTION · BLOCKED | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-007 | CARE · CREDIT · COMPLAINT · ACTIVE | 200 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-008 | ADMIN · DEBIT · COMPLAINT · INACTIVE | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-009 | CARE · DEBIT · COMPLAINT · BLOCKED | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-010 | ADMIN · DEBIT · TEST · ACTIVE | 200 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-011 | CARE · CREDIT · TEST · INACTIVE | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-012 | ADMIN · CREDIT · TEST · BLOCKED | 200 | wallet §3 · Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-013 | ruolo MARKETING | 403 `FORBIDDEN_ROLE` | F-WAL-07 · docs/06 §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-014 | ruolo LEGAL | 403 | F-WAL-07 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-015 | ruolo ANALYST | 403 | F-WAL-07 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-016 | senza `X-LH-Actor` | 403 | docs/06 §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-017 | `X-LH-Actor` non valido | 403 | docs/06 §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-018 | valuta `STS` | 422 `CURRENCY_NOT_ADJUSTABLE` | Q-46 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-019 | valuta `XYZ` | 422 | Q-46 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-020 | valuta assente | 422 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-021 | direzione assente | 422 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-022 | direzione `SIDEWAYS` | 422 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-023 | importo 0 | 422 | wallet §2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-024 | importo −5 | 422 | wallet §2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-025 | motivo assente | 422 | F-WAL-07 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-026 | motivo `FRAUD` | 422 | Q-45 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-027 | motivo sconosciuto | 422 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-028 | nota assente | 422 `NOTE_TOO_SHORT` | wallet §3 · BO-03 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-029 | nota vuota | 422 `NOTE_TOO_SHORT` | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-030 | nota di 9 caratteri | 422 `NOTE_TOO_SHORT` | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-031 | nota di 12 spazi | AMBIGUO — 422 `NOTE_TOO_SHORT` | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-032 | 9 caratteri preceduti e seguiti da 5 spazi | AMBIGUO — 422 (conta il testo senza spazi) | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-033 | membro `ANONYMIZED` · CREDIT | 409 `MEMBER_ANONYMIZED` | Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-034 | membro `ANONYMIZED` · DEBIT (ADMIN) | 409 `MEMBER_ANONYMIZED` | Q-127 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-035 | addebito 1 001 su saldo 1 000 | 422 `INSUFFICIENT_BALANCE` | docs/03 §4.2 · wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-036 | addebito 1 000 su saldo 1 000 | 200; saldo 0 | docs/03 §4.2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-037 | addebito 999 su saldo 1 000 | 200; saldo 1 | docs/03 §4.2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-038 | addebito 101 con 100 attivi e 500 in attesa | 422 `INSUFFICIENT_BALANCE` (conta il saldo attivo) | docs/03 §4.2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-039 | addebito 1 su saldo 0 | 422 `INSUFFICIENT_BALANCE` | docs/03 §4.2 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-040 | accredito 1 su saldo 0 | 200; saldo 1 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-041 | accredito 1 000 000 000 (ADMIN) | 200; saldo 1 000 000 000 | wallet §3 | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-042 | membro senza wallet · accredito 100 | AMBIGUO — 200, wallet creato con 100 | — | `TestbookWalSpendIT.adjustments` |
| TB-WAL-ADJ-043 | lotti 300 (31/10/26) e 700 (31/12/26) · addebito 400 | AMBIGUO — consumo per scadenza: 300 a 0, 700 a 600 | docs/03 §4.2 | `TestbookWalSpendIT.debitConsumesByExpiry` |

## 13. Chiusura dell'edizione — regola (logica pura)

**Regole**: R-22 — docs/03 §4.3 (pseudocodice di chiusura; «La salita non avviene mai in chiusura»), F-TIER-04. Livelli e soglie letti da `seed/tiers.json`.

| Ingresso | Classi |
|---|---|
| livello attuale | BASE, SILVER, GOLD, PLATINUM (+ codice sconosciuto) |
| `periodSts` | 0 · SILVER − 1 (999) · SILVER (1 000) · GOLD − 1 (2 999) · GOLD (3 000) · PLATINUM − 1 (6 999) · PLATINUM (7 000) · 100 000 |

**Strategia**: tabella decisionale **completa** 4 × 8 = **32 ≤ 64** (CLR-001…032) + il codice sconosciuto da solo (CLR-033). Colonne attese: livello guadagnato · nuovo livello · esito.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-CLR-001 | BASE · 0 | BASE · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-002 | BASE · 999 | BASE · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-003 | BASE · 1 000 | SILVER · BASE (niente salita) · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-004 | BASE · 2 999 | SILVER · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-005 | BASE · 3 000 | GOLD · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-006 | BASE · 6 999 | GOLD · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-007 | BASE · 7 000 | PLATINUM · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-008 | BASE · 100 000 | PLATINUM · BASE · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-009 | SILVER · 0 | BASE · BASE · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-010 | SILVER · 999 | BASE · BASE · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-011 | SILVER · 1 000 | SILVER · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-012 | SILVER · 2 999 | SILVER · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-013 | SILVER · 3 000 | GOLD · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-014 | SILVER · 6 999 | GOLD · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-015 | SILVER · 7 000 | PLATINUM · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-016 | SILVER · 100 000 | PLATINUM · SILVER · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-017 | GOLD · 0 | BASE · SILVER (pavimento) · DOWNGRADED | docs/03 §4.3 · F-TIER-04 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-018 | GOLD · 999 | BASE · SILVER · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-019 | GOLD · 1 000 | SILVER · SILVER · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-020 | GOLD · 2 999 | SILVER · SILVER · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-021 | GOLD · 3 000 | GOLD · GOLD · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-022 | GOLD · 6 999 | GOLD · GOLD · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-023 | GOLD · 7 000 | PLATINUM · GOLD · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-024 | GOLD · 100 000 | PLATINUM · GOLD · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-025 | PLATINUM · 0 | BASE · GOLD (pavimento) · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-026 | PLATINUM · 999 | BASE · GOLD · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-027 | PLATINUM · 1 000 | SILVER · GOLD · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-028 | PLATINUM · 2 999 | SILVER · GOLD · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-029 | PLATINUM · 3 000 | GOLD · GOLD · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-030 | PLATINUM · 6 999 | GOLD · GOLD · DOWNGRADED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-031 | PLATINUM · 7 000 | PLATINUM · PLATINUM · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-032 | PLATINUM · 100 000 | PLATINUM · PLATINUM · RETAINED | docs/03 §4.3 | `TestbookWalCloseRuleTest` |
| TB-WAL-CLR-033 | livello `BRONZE` sconosciuto · 0 | AMBIGUO — trattato come il primo della scala: BASE · BASE · RETAINED | — | `TestbookWalCloseRuleTest` |

## 14. Chiusura dell'edizione — anteprima e applicazione

**Regole**: R-22, R-23, R-32 — wallet §3 (`POST /v1/editions/{code}/close?dryRun=true` anteprima `{summary, members[]}`; `dryRun=false` applica, ruolo `ADMIN`), wallet §5 («emette un fatto per membro + `edition.closed`»), wallet §7 (Stefano GOLD 650 → SILVER), docs/03 §4.3 («per ogni membro ACTIVE … periodSts = 0 · edizione → CLOSED; la successiva → ACTIVE»), docs/03 §4.4 («Una sola ACTIVE»), EVT-FACT-29/30/31, docs/08 BO-08.

Stato del ciclo di vita dell'edizione × azione:

| Stato \ azione | anteprima (qualsiasi ruolo) | applicazione ADMIN | applicazione altri ruoli |
|---|---|---|---|
| `ACTIVE` | 200, nessuna scrittura (ECL-001…006) | 200, `CLOSED` (ECL-012…019) | 403 (ECL-007…011) |
| `CLOSED` | 422 (ECL-021) | 422 (ECL-020) | — (il ruolo è verificato prima: 403) |
| `PLANNED` | — | 422 (ECL-022) | — |
| inesistente | 404 (ECL-023) | — | — |

**Strategia**: macchina a stati come sopra; esiti per membro con una rappresentante per esito (retrocesso, confermato, non `ACTIVE`) — la tabella completa della regola è in §13; ogni ruolo negato da solo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-ECL-001 | anteprima ED-2026 con membri GOLD 0 · PLATINUM 7 000 · SILVER 999 · BASE 0 | per ciascuno `currentTier, periodSts, earnedTier, newTier, outcome` = (GOLD, 0, BASE, SILVER, DOWNGRADED) · (PLATINUM, 7000, PLATINUM, PLATINUM, RETAINED) · (SILVER, 999, BASE, BASE, DOWNGRADED) · (BASE, 0, BASE, BASE, RETAINED) | wallet §3 · docs/03 §4.3 | `TestbookWalAdminIT.previewEntries` |
| TB-WAL-ECL-002 | anteprima: Stefano `MBR-000006` (seed: GOLD, 650 STS) | guadagnato BASE, nuovo SILVER, DOWNGRADED | wallet §7 | `TestbookWalAdminIT.previewStefano` |
| TB-WAL-ECL-003 | anteprima | `summary.retained`/`downgraded` = conteggi degli esiti in `members[]` | wallet §3 | `TestbookWalAdminIT.previewSummary` |
| TB-WAL-ECL-004 | anteprima con un membro GOLD `BLOCKED` | il membro non compare | docs/03 §4.3 | `TestbookWalAdminIT.previewExcludesInactive` |
| TB-WAL-ECL-005 | anteprima | nessuna scrittura: livelli e STS invariati, ED-2026 `ACTIVE`, nessun `tier.*` né `edition.closed` | F-TIER-05 | `TestbookWalAdminIT.previewWritesNothing` |
| TB-WAL-ECL-006 | anteprima con ruolo ANALYST | AMBIGUO — 200 (solo l'applicazione è riservata) | wallet §3 | `TestbookWalAdminIT.previewAnalyst` |
| TB-WAL-ECL-007 | applicazione con CARE | 403 `FORBIDDEN_ROLE`; ED-2026 resta `ACTIVE` | wallet §3 · BO-08 | `TestbookWalAdminIT.applyForbidden` |
| TB-WAL-ECL-008 | applicazione con MARKETING | 403 | wallet §3 | `TestbookWalAdminIT.applyForbidden` |
| TB-WAL-ECL-009 | applicazione con LEGAL | 403 | wallet §3 | `TestbookWalAdminIT.applyForbidden` |
| TB-WAL-ECL-010 | applicazione con ANALYST | 403 | wallet §3 | `TestbookWalAdminIT.applyForbidden` |
| TB-WAL-ECL-011 | applicazione senza `X-LH-Actor` | 403 | docs/06 §3 | `TestbookWalAdminIT.applyForbidden` |
| TB-WAL-ECL-012 | applicazione con ADMIN | 200; riepilogo uguale a quello dell'anteprima sugli stessi dati | F-TIER-05 | `TestbookWalAdminIT.applyAdmin` |
| TB-WAL-ECL-013 | membro GOLD con 0 STS | SILVER, `periodSts` 0, storico `DOWNGRADE` GOLD→SILVER con `ED-2026`, fatto `tier.downgraded {previousTier, newTier, editionCode}` | docs/03 §4.3 · EVT-FACT-29 | `TestbookWalAdminIT.appliedDowngrade` |
| TB-WAL-ECL-014 | membro PLATINUM con 7 000 STS | PLATINUM, `periodSts` 0, storico `RETAIN`, fatto `tier.retained {tier, editionCode}` | docs/03 §4.3 · EVT-FACT-30 | `TestbookWalAdminIT.appliedRetain` |
| TB-WAL-ECL-015 | membro GOLD `BLOCKED` con 500 STS | invariato (GOLD, 500), nessun fatto | docs/03 §4.3 | `TestbookWalAdminIT.appliedSkipsBlocked` |
| TB-WAL-ECL-016 | dopo l'applicazione | ED-2026 `CLOSED`; una sola edizione `ACTIVE` | docs/03 §4.3–4.4 | `TestbookWalAdminIT.appliedEditionStatus` |
| TB-WAL-ECL-017 | dopo l'applicazione, con ED-2024 `PLANNED` creata in EDN-005 | la successiva, ED-2027, è `ACTIVE` | docs/03 §4.3 | `TestbookWalAdminIT.appliedNextEditionActive` |
| TB-WAL-ECL-018 | dopo l'applicazione | un fatto `edition.closed {editionCode, retained, downgraded}` con chiave `edition:ED-2026` e conteggi del riepilogo | EVT-FACT-31 | `TestbookWalAdminIT.appliedEditionClosedFact` |
| TB-WAL-ECL-019 | dopo l'applicazione | audit `TRANSITION` su `edition:ED-2026` | wallet §4 | `TestbookWalAdminIT.appliedAudited` |
| TB-WAL-ECL-020 | seconda applicazione su ED-2026 | 422; nessuna seconda discesa né secondo fatto | wallet §3 · docs/03 §4.4 | `TestbookWalAdminIT.closeTwice` |
| TB-WAL-ECL-021 | anteprima su ED-2026 chiusa | 422 | docs/03 §4.4 | `TestbookWalAdminIT.previewClosed` |
| TB-WAL-ECL-022 | applicazione su ED-2028 `PLANNED` | 422 | docs/03 §4.4 | `TestbookWalAdminIT.closePlanned` |
| TB-WAL-ECL-023 | chiusura di `ED-1999` inesistente | 404 | docs/06 §2 | `TestbookWalAdminIT.closeUnknown` |
| TB-WAL-ECL-024 | dopo la chiusura, 100 STS al membro retrocesso | `periodSts` 100 (riparte da 0), resta SILVER | docs/03 §4.3 | `TestbookWalAdminIT.stsAfterClose` |

## 15. Edizioni

**Regole**: R-24, R-32 — docs/03 §4.4 (`{code, startDate, endDate, redemptionGraceUntil, status}`; «Una sola ACTIVE. Periodi contigui, senza sovrapposizioni»), wallet §3 («niente sovrapposizioni»), docs/08 §2 (`program.config` solo ADMIN).

| Ingresso | Classi valide | Non valide | Limiti |
|---|---|---|---|
| periodo | contiguo prima/dopo | sovrapposto, inizio > fine | inizio = fine di un'altra; fine = inizio di un'altra; interno; un solo giorno |
| campi | tutti | nome o inizio assente | — |
| codice | nuovo | già esistente | — |
| ruolo | ADMIN | MARKETING, assente | — |
| metodo | POST, PUT | PUT su edizione assente | — |

**Strategia**: ogni limite di sovrapposizione da solo; ogni campo mancante e ruolo negato da solo; righe in ordine (le PUT usano ED-2028 creata in EDN-001). Dopo ogni riga si verifica che nessuna coppia di edizioni si sovrapponga.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-EDN-001 | POST ED-2028 01/01–31/12/2028 | 200, `PLANNED` | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-002 | POST inizio 31/12/2027 (ultimo giorno di ED-2027) | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-003 | POST periodo interno a ED-2027 | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-004 | POST fine 01/01/2025 (primo giorno di ED-2025) | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-005 | POST ED-2024 01/01–31/12/2024 (contigua prima di ED-2025) | 200, `PLANNED` | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-006 | POST inizio 01/02/2031 dopo la fine 01/01/2031 | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-007 | POST ED-2031 di un giorno, non contigua | AMBIGUO — 200 (il buco tra edizioni è accettato) | docs/03 §4.4 · Q-47 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-008 | POST senza nome | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-009 | POST senza data d'inizio | 422 | docs/03 §4.4 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-010 | POST codice ED-2026 già esistente, date libere | AMBIGUO — 409 | — | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-011 | POST con MARKETING | 403 | docs/08 §2 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-012 | POST senza `X-LH-Actor` | 403 | docs/06 §3 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-013 | PUT ED-2028 con nuova grace, stesse date | 200 | wallet §3 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-014 | PUT ED-2028 con inizio 15/12/2027 | 422 | wallet §3 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-015 | PUT `ED-1999` inesistente | 404 | docs/06 §2 | `TestbookWalAdminIT.editionCrud` |
| TB-WAL-EDN-016 | creazione di ED-2028 | audit `CREATE` su `edition:ED-2028` | wallet §4 | `TestbookWalAdminIT.editionCreateAudited` |

## 16. Valute e policy di scadenza

**Regole**: R-25, R-05, R-06, R-32 — wallet §3 (`GET/PUT /v1/currencies/{code}` «policy di scadenza (vale per i nuovi lotti)»), docs/03 §4.1 (policy `ROLLING_MONTHS`, `END_OF_EDITION_PLUS_GRACE`, `NEVER`), BO-08 («`ROLLING_MONTHS` n mesi + fine mese, oppure `END_OF_EDITION_PLUS_GRACE` giorni»), docs/08 §2 (`program.config`).

| Ingresso | Classi valide | Non valide | Limiti |
|---|---|---|---|
| tipo | `ROLLING_MONTHS`, `END_OF_EDITION_PLUS_GRACE`, `NEVER` | sconosciuto | — |
| `months` | 6 | 0, 61 | 1, 60 |
| `graceDays` | assente | −1 | — |
| ruolo | ADMIN | MARKETING, assente | — |
| valuta | PTS | XYZ | — |

**Strategia**: ogni tipo valido con un accredito successivo (scadenza verificata nel fatto `wallet.points.earned`, accredito a T0 = 18/09/2026), ogni classe non valida e ogni limite da soli; policy del seed ripristinata dopo ogni modifica accettata.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-CUR-001 | PTS → `ROLLING_MONTHS 6` | 200; nuovo lotto scade l'ultimo istante del 31/03/2027 | wallet §3 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-002 | lotto creato prima del passaggio a `ROLLING_MONTHS 3` | scadenza del lotto esistente invariata | wallet §3 · BO-08 | `TestbookWalAdminIT.policyChangeKeepsExistingLots` |
| TB-WAL-CUR-003 | PTS → `END_OF_EDITION_PLUS_GRACE` | 200; nuovo lotto scade a fine 31/01/2027 (`redemptionGraceUntil` di ED-2026) | docs/03 §4.1 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-004 | PTS → `NEVER` | 200; nuovo lotto senza scadenza | docs/03 §4.1 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-005 | tipo `WEEKLY` | 422 | docs/03 §4.1 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-006 | `ROLLING_MONTHS 0` | AMBIGUO — 422 | — | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-007 | `ROLLING_MONTHS 61` | AMBIGUO — 422 (massimo 60) | — | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-008 | `ROLLING_MONTHS 1` | 200; nuovo lotto scade il 31/10/2026 | wallet §5 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-009 | `ROLLING_MONTHS 60` | AMBIGUO — 200; scade il 30/09/2031 | wallet §5 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-010 | ruolo MARKETING | 403 | docs/08 §2 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-011 | senza `X-LH-Actor` | 403 | docs/06 §3 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-012 | valuta `XYZ` | 404 | docs/06 §2 | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-013 | `END_OF_EDITION_PLUS_GRACE` con `graceDays` −1 | 422 | BO-08 (giorni di tolleranza) | `TestbookWalAdminIT.currencyPolicy` |
| TB-WAL-CUR-014 | modifica della policy PTS | audit `UPDATE` su `currency:PTS` | wallet §4 | `TestbookWalAdminIT.policyChangeAudited` |

## 17. Livelli: amministrazione

**Regole**: R-20, R-08, R-32 — wallet §3 («soglie crescenti col rank (`422 TIER_THRESHOLDS_NOT_MONOTONIC`); `BASE` ha soglia 0 fissa»), F-TIER-01, BO-07 (`program.config`), docs/08 §2.

| Ingresso | Classi valide | Non valide | Limiti |
|---|---|---|---|
| soglia di SILVER / GOLD / PLATINUM | tra le vicine | uguale alla precedente o alla successiva | vicina − 1, vicina + 1, molto alta |
| soglia di BASE | 0 | ≠ 0 | 1 |
| moltiplicatore | 1,75 | 0 | — |
| ruolo | ADMIN | MARKETING, assente | — |
| codice | esistente | sconosciuto | — |

**Strategia**: per ogni livello i limiti di monotonia da soli (uguale alla vicina = rifiuto, vicina ± 1 = accettata); ruoli e codice da soli. Dopo ogni modifica accettata si ripristinano soglia e moltiplicatore del seed; dopo ogni riga si verifica la scala.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-TAD-001 | SILVER soglia 0 (= BASE) | 422 `TIER_THRESHOLDS_NOT_MONOTONIC`; scala invariata | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-002 | SILVER soglia 3 000 (= GOLD) | 422 `TIER_THRESHOLDS_NOT_MONOTONIC` | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-003 | SILVER soglia 2 999 | 200; SILVER a 2 999 | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-004 | GOLD soglia 1 000 (= SILVER) | 422 `TIER_THRESHOLDS_NOT_MONOTONIC` | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-005 | GOLD soglia 7 000 (= PLATINUM) | 422 `TIER_THRESHOLDS_NOT_MONOTONIC` | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-006 | GOLD soglia 1 001 | 200 | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-007 | PLATINUM soglia 3 000 (= GOLD) | 422 `TIER_THRESHOLDS_NOT_MONOTONIC` | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-008 | PLATINUM soglia 3 001 | 200 | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-009 | PLATINUM soglia 1 000 000 | 200 | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-010 | BASE soglia 1 | 422 `TIER_THRESHOLDS_NOT_MONOTONIC` | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-011 | BASE soglia 0 | 200 | wallet §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-012 | ruolo MARKETING | 403 `FORBIDDEN_ROLE` | BO-07 · docs/08 §2 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-013 | senza `X-LH-Actor` | 403 `FORBIDDEN_ROLE` | docs/06 §3 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-014 | livello `BRONZE` | 404 | docs/06 §2 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-015 | moltiplicatore SILVER 0 | AMBIGUO — 422 | F-TIER-01 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-016 | moltiplicatore SILVER 1,75 | 200 | F-TIER-01 | `TestbookWalAdminIT.tierAdmin` |
| TB-WAL-TAD-017 | moltiplicatore SILVER 1,75, poi 130 PTS con moltiplicatore a un SILVER | 227 = floor(130 × 1,75) | F-TIER-01 · F-TIER-03 | `TestbookWalAdminIT.newMultiplierAppliesToNextGrant` |
| TB-WAL-TAD-018 | modifica di SILVER | audit `UPDATE` su `tier:SILVER` | wallet §4 | `TestbookWalAdminIT.tierChangeAudited` |

## 18. Viste, API di lettura, passività, ciclo di vita del membro

### 18.1 Vista del wallet

**Regole**: R-26 — wallet §3 (`expiringSoon: {amount, within30d, nextExpiryAt}`, `tier: {code, name, since, periodSts, next?: {code, threshold, missing}, progressPct, multiplier, keepWarning?}`), wallet §5 («`keepWarning`: valorizzato da ottobre se `periodSts` < soglia del tier attuale: `{tier, missing}`»), docs/03 §4.3 («a PLATINUM: livello massimo»), PT-01. `progressPct` non ha una formula nella specifica: non è verificato.

| Ingresso | Classi | Limiti |
|---|---|---|
| livello / `periodSts` | BASE, SILVER, GOLD, PLATINUM | soglia successiva − 1 |
| scadenza di un lotto rispetto ad «adesso» | entro 30 g, oltre | +30 g, +30 g +1 s, già passata |
| data (per `keepWarning`) | settembre, ottobre–dicembre, gennaio | 30/09 23:59:59, 01/10 00:00, 31/12 23:59:59, 01/01 00:00 |
| `periodSts` rispetto alla soglia attuale | sotto, in soglia | soglia − 1 |

**Strategia**: un caso per livello per `next`; limiti della finestra dei 30 giorni da soli; per `keepWarning` i confini di calendario da soli più le classi di livello (BASE con soglia 0, PLATINUM).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-WVW-001 | BASE, 0 STS | `next {SILVER, 1000, missing 1000}`, moltiplicatore 1,00 | wallet §3 | `TestbookWalAccrualIT.walletViewNextTier` |
| TB-WAL-WVW-002 | SILVER, 1 420 STS | `next {GOLD, 3000, missing 1580}` | wallet §3 · PT-01 | `TestbookWalAccrualIT.walletViewNextTier` |
| TB-WAL-WVW-003 | GOLD, 6 999 STS | `next {PLATINUM, 7000, missing 1}` | wallet §3 | `TestbookWalAccrualIT.walletViewNextTier` |
| TB-WAL-WVW-004 | PLATINUM, 7 100 STS | nessun `next` (livello massimo), moltiplicatore 2,00 | docs/03 §4.3 | `TestbookWalAccrualIT.walletViewNextTier` |
| TB-WAL-WVW-005 | lotto 250 PTS che scade fra 30 giorni esatti | `expiringSoon {amount 250, within30d true, nextExpiryAt}` | wallet §3 | `TestbookWalAccrualIT.expiringSoonIncludesThirtyDays` |
| TB-WAL-WVW-006 | lotto che scade fra 30 giorni + 1 s | `amount` 0, `within30d` false | wallet §3 | `TestbookWalAccrualIT.expiringSoonExcludesBeyondThirtyDays` |
| TB-WAL-WVW-007 | lotto scaduto un'ora fa, non spazzato | AMBIGUO — escluso (`amount` 0) | wallet §3 | `TestbookWalAccrualIT.expiringSoonExcludesAlreadyDue` |
| TB-WAL-WVW-008 | lotti 200 (+5 g), 300 (+20 g), 900 (+200 g) PTS e 400 STS | `amount` 500, `nextExpiryAt` = +5 g | wallet §3 | `TestbookWalAccrualIT.expiringSoonSumsAndEarliest` |
| TB-WAL-WVW-009 | 30/09 23:59:59, GOLD con 2 500 STS | nessun `keepWarning` | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-010 | 01/10 00:00, GOLD con 2 500 STS | `keepWarning {tier GOLD, missing 500}` | wallet §5 · PT-01 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-011 | 01/10, GOLD con 3 000 STS | nessun `keepWarning` | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-012 | 31/12 23:59:59, SILVER con 999 STS | `keepWarning {tier SILVER, missing 1}` | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-013 | 15/10, BASE con 0 STS | nessun `keepWarning` (soglia 0) | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-014 | 15/11, PLATINUM con 0 STS | `keepWarning {tier PLATINUM, missing 7000}` | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-015 | 01/01/2027 00:00, GOLD con 0 STS | nessun `keepWarning` (fuori da ottobre–dicembre) | wallet §5 | `TestbookWalAccrualIT.keepWarning` |
| TB-WAL-WVW-016 | wallet di un membro sconosciuto | 404 | docs/06 §2 | `TestbookWalAccrualIT.unknownWallet` |
| TB-WAL-WVW-017 | `GET /v1/portal/wallets/{id}` vs `/v1/wallets/{id}` | stessi saldi e stesso livello | wallet §3 («come sopra») | `TestbookWalAccrualIT.portalWalletMatchesManagement` |

### 18.2 Libro mastro, lotti, portale, distribuzione

**Regole**: R-02, R-27, R-28, R-29 — wallet §3 (ledger «filtri `currency, type, from, to`; ordinamento `occurredAt desc`»; lots «lotti non esauriti, per scadenza»; portale tiers `{code, name, threshold, multiplier, benefits[], color}` (PT-08); activity `{…, pending, expiresAt?, breakdown?}`), F-WAL-02 (movimento con campagna, azione, attore), PT-07 («scomposizione ("130 punti base × 1,25 livello SILVER = 162"), data di scadenza del lotto, stato in attesa»), BO-07 (n. membri per livello).

**Strategia**: un caso per filtro e per campo richiesto (partizione per parametro); ogni caso con un membro fresco.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-API-001 | tre `EARN` con date di business T0, T0 − 10 g, T0 + 1 g | ordine 30, 10, 20 (occurredAt decrescente) | wallet §3 | `TestbookWalAccrualIT.ledgerOrderedByOccurredAtDesc` |
| TB-WAL-API-002 | `?currency=STS` con un movimento PTS e uno STS | solo il movimento STS | wallet §3 | `TestbookWalAccrualIT.ledgerFilterCurrency` |
| TB-WAL-API-003 | `?type=SPEND` con un `EARN` e uno `SPEND` | solo lo `SPEND` | wallet §3 | `TestbookWalAccrualIT.ledgerFilterType` |
| TB-WAL-API-004 | `?from=T0−1g&to=T0+1g` con `EARN` a T0 − 10 g e a T0 | solo quello a T0 | wallet §3 | `TestbookWalAccrualIT.ledgerFilterPeriod` |
| TB-WAL-API-005 | movimento da un effetto con `actionId` e attore | il movimento espone campagna, azione e attore | F-WAL-02 · BO-03 | `TestbookWalAccrualIT.ledgerExposesActionAndActor` |
| TB-WAL-API-006 | lotti ACTIVE (31/03/27), PENDING (31/12/26), EXHAUSTED, EXPIRED, STS senza scadenza | 200 (PENDING), 100 (ACTIVE), 500 (STS): non esauriti, per scadenza, null in fondo | wallet §3 | `TestbookWalAccrualIT.lotsView` |
| TB-WAL-API-007 | `GET /v1/portal/tiers` | per ogni livello del seed `code, name, threshold, multiplier, benefits, color` | wallet §3 · PT-08 | `TestbookWalAccrualIT.portalTiers` |
| TB-WAL-API-008 | attività dopo un accredito con `pendingDays` 7 | voce con `pending = true` | wallet §3 · PT-07 | `TestbookWalAccrualIT.portalActivityPending` |
| TB-WAL-API-009 | attività dopo 130 PTS con moltiplicatore a un SILVER | `breakdown` «130 punti base × 1,25 livello SILVER = 162», `amount` 162 | PT-07 | `TestbookWalAccrualIT.portalActivityBreakdown` |
| TB-WAL-API-010 | attività dopo un accredito PTS | voce con `expiresAt` del lotto | wallet §3 · PT-07 | `TestbookWalAccrualIT.portalActivityExpiresAt` |
| TB-WAL-API-011 | nuovo membro GOLD | `GET /v1/tiers/distribution`: GOLD +1 | wallet §3 · BO-07 | `TestbookWalAccrualIT.tierDistribution` |

### 18.3 Passività

**Regole**: R-30, R-07, R-04 — F-WAL-09 («punti in circolazione per valuta e per mese di scadenza»), wallet §3 (`{currency, outstanding, pending, byExpiryMonth[]}`), docs/03 §4.2 (saldo attivo = Σ lotti attivi).

**Strategia**: delta prima/dopo per ogni tipo di movimento (attivo, in attesa, scadenza) più gli invarianti di coerenza (Σ per mese = `outstanding`) e il mese di un lotto che scade l'ultimo istante del mese.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-LIA-001 | accredito attivo di 100 PTS | `outstanding` +100, `pending` +0, Σ `byExpiryMonth` +100 | F-WAL-09 | `TestbookWalAccrualIT.liabilityActiveGrant` |
| TB-WAL-LIA-002 | accredito di 70 PTS con `pendingDays` 5 | `pending` +70, `outstanding` +0 | F-WAL-09 | `TestbookWalAccrualIT.liabilityPendingGrant` |
| TB-WAL-LIA-003 | passività PTS | Σ `byExpiryMonth` = `outstanding` | F-WAL-09 · docs/03 §4.2 | `TestbookWalAccrualIT.liabilityMonthsSumToOutstanding` |
| TB-WAL-LIA-004 | passività `sts` (minuscolo) dopo un accredito STS | valuta `STS`; mesi nulli (nessuna scadenza); Σ = `outstanding` | F-WAL-09 · docs/03 §4.1 | `TestbookWalAccrualIT.liabilitySts` |
| TB-WAL-LIA-005 | valuta `XYZ` | 404 | docs/06 §2 | `TestbookWalAccrualIT.liabilityUnknownCurrency` |
| TB-WAL-LIA-006 | accredito di 77 PTS a T0 (scade l'ultimo istante di settembre 2027) | mese `2027-09` +77 | F-WAL-09 · wallet §5 | `TestbookWalAccrualIT.liabilityMonthOfLastInstant` |
| TB-WAL-LIA-007 | job di scadenza | `outstanding` diminuisce esattamente dei punti scaduti; Σ per mese = `outstanding` | F-WAL-09 · docs/03 §4.2 | `TestbookWalAccrualIT.liabilityAfterExpiry` |

### 18.4 Ciclo di vita del membro

**Regole**: R-31 — wallet §4 (`member.registered` «crea 2 wallet + `member_tier` BASE», `member.status.changed`), docs/03 §2. Eventi passati dal router del servizio (idempotenza per `id` d'evento).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WAL-MBR-001 | `member.registered` | wallet `PTS` e `STS` a 0; livello BASE, `periodSts` 0 | wallet §4 | `TestbookWalAccrualIT.memberRegistered` |
| TB-WAL-MBR-002 | `member.registered` ripetuto dopo un accredito STS di 1 000 | due wallet; saldo STS 1 000 e livello SILVER invariati | wallet §4 | `TestbookWalAccrualIT.memberRegisteredIdempotent` |
| TB-WAL-MBR-003 | `member.status.changed` → `BLOCKED`, poi richiesta premio | stato `BLOCKED`; spesa rifiutata `MEMBER_NOT_ACTIVE`, saldo invariato | wallet §4 · docs/03 §2 | `TestbookWalAccrualIT.memberStatusChanged` |

## 19. Ambiguità (righe AMBIGUO)

Nessuna di queste è registrata in `docs/15`: la riga asserisce il comportamento attuale. Da decidere con una voce in `docs/15`.

| Riga | Questione | Comportamento attuale asserito |
|---|---|---|
| GRT-049…051 | docs/03 §2 «solo i membri ACTIVE accumulano», ma il controllo è del motore (§3.5): un `points.grant` già deciso per un membro non più `ACTIVE` va applicato? | accreditato |
| GRT-061, GRT-065 | effetto fuori contratto (senza `effectId`, subject non di membro) | ignorato senza errore né DLQ |
| POL-013, POL-023, POL-024 | policy senza `months`, valuta senza policy, tipo sconosciuto | 12 mesi; non scade; non scade |
| TUP-016, TUP-017 | gli STS in attesa contano nel `periodSts` (e fanno salire) all'accredito o al rilascio? | al rilascio |
| SPD-018 | membro non `ACTIVE` e saldo insufficiente: quale motivo? | `MEMBER_NOT_ACTIVE` |
| SPD-021…023 | richiesta incompleta; membro senza wallet | ignorata; rifiuto con `available` 0 |
| ADJ-031, ADJ-032 | la nota ≥ 10 caratteri conta gli spazi? | conta il testo senza spazi ai bordi |
| ADJ-042 | rettifica su un membro senza wallet | wallet creato |
| ADJ-043 | ordine di consumo di un addebito manuale | FIFO come la spesa |
| CLR-033 | livello attuale sconosciuto in chiusura | trattato come il primo della scala |
| ECL-006 | ruolo per l'anteprima di chiusura | qualsiasi ruolo |
| EDN-007 | «periodi contigui»: un buco tra edizioni va rifiutato? | accettato |
| EDN-010 | codice di edizione già esistente | 409 |
| CUR-006, 007, 009 | limiti di `months` | 1…60 |
| TAD-015 | moltiplicatore 0 | 422 |
| WVW-007 | un lotto scaduto non ancora spazzato è «in scadenza»? | no |
| JOB-014 | `asOf` come data pura | fine di quel giorno a Roma |

## 20. Divergenze

Le righe restano rosse finché il codice non è corretto o una decisione in `docs/15` non cambia l'oracolo. Nessun codice di produzione è stato modificato.

| # | Riga | Specifica | Osservato | Causa (file:riga) |
|---|---|---|---|---|
| D-01 | TB-WAL-GRT-064 | wallet §5: `expires_at` = «ultimo istante del mese … in Europe/Rome» | `2027-09-30T22:00:00Z` = **01/10/2027 00:00** Roma (primo istante del mese dopo) | `domain/ExpiryPolicy.java:51` usa `LocalTime.MAX` (…59.999999999); `infra/PointsLotRepository.java:34,183` lo passa come `Timestamp` e il driver JDBC, arrotondando ai microsecondi, lo porta al secondo successivo |
| D-02 | TB-WAL-LIA-006 | F-WAL-09: passività per mese di scadenza | il lotto di settembre 2027 è contato in `2027-10` | stessa causa di D-01 (`infra/LiabilityRepository.java:70` raggruppa il valore salvato) |
| D-03 | TB-WAL-EXP-011 | docs/03 §4.2: «un movimento `EXPIRE` per membro/valuta con il totale» | due movimenti `EXPIRE` (100 e 200) | `application/WalletService.java:313-332`: un movimento per lotto |
| D-04 | TB-WAL-JOB-013 | wallet §4: `lh.audit.v1` «… job» | nessuna voce di audit per i job | `application/WalletService.java:269,309,344` e `api/WalletJobsController.java`: nessuna chiamata a `AuditPublisher.recordJob` |
| D-05 | TB-WAL-WVW-010 | wallet §5: `keepWarning {tier, missing}` da ottobre se `periodSts` < soglia | campo assente | `api/WalletView.java:27-36` e `application/WalletQueryService.java:65-86`: `keepWarning` non esiste |
| D-06 | TB-WAL-WVW-012 | idem (31/12, SILVER a soglia − 1) | campo assente | come D-05 |
| D-07 | TB-WAL-WVW-014 | idem (PLATINUM con 0 STS) | campo assente | come D-05 |
| D-08 | TB-WAL-API-003 | wallet §3: ledger con filtro `type` | il filtro è ignorato (restituiti `SPEND` ed `EARN`) | `api/WalletsController.java:50-56`, `infra/LedgerRepository.java:63-74`: solo `currency` |
| D-09 | TB-WAL-API-004 | wallet §3: ledger con filtri `from`, `to` | filtri ignorati | come D-08 |
| D-10 | TB-WAL-API-005 | F-WAL-02: movimento con azione e attore; BO-03 li mostra | `actionId` e `actor` assenti dalla risposta | `domain/LedgerEntry.java:6-20`, `infra/LedgerRepository.java:80-86`: colonne salvate ma non esposte |
| D-11 | TB-WAL-API-007 | wallet §3 / PT-08: scala del portale con `threshold` | campo `thresholdSts` | `api/PortalWalletsController.java:29` espone il record `domain/Tier.java:11` |
| D-12 | TB-WAL-API-008 | wallet §3 / PT-07: `pending` nell'attività | sempre `false` | `api/PortalActivityController.java:49` |
| D-13 | TB-WAL-API-010 | wallet §3 / PT-07: `expiresAt?` del lotto nell'attività | campo assente | `api/PortalActivityController.java:33` (record senza `expiresAt`, `direction`, `icon`) |
| D-14 | TB-WAL-REF-003 | docs/03 §4.2: rimborso = nuovo lotto con scadenza max(più lontana consumata, oggi + 30 g) | nessun lotto nuovo: i punti tornano nei lotti d'origine (A 500, B 800, C 900) | `application/RedemptionPayments.java:143-149` (scelta Q-54) |
| D-15 | TB-WAL-REF-004 | idem: scadenza almeno oggi + 30 g | punti restituiti al lotto d'origine che scade fra 10 giorni | `RedemptionPayments.java:143-149` (Q-54) |
| D-16 | TB-WAL-REF-005 | idem: nuovo lotto di 200 con la scadenza del lotto consumato | il lotto d'origine torna a 500, nessun lotto nuovo | `RedemptionPayments.java:143-149` (Q-54) |
| D-17 | TB-WAL-REF-006 | idem: lotto scaduto → scadenza oggi + 30 g (02/12/2026) | nuovo lotto con la policy di oggi: 30/11/2027 (01/12/2027 00:00 Roma, vedi D-01) | `RedemptionPayments.java:153-158` (Q-54) |
| D-18 | TB-WAL-ECL-017 | docs/03 §4.3: «edizione → CLOSED; **la successiva** → ACTIVE» | diventa `ACTIVE` ED-2024 (PLANNED con inizio minimo, precedente a ED-2026); ED-2027 resta `PLANNED` | `application/EditionCloseBatchService.java:163-166`: sceglie la `PLANNED` con `startDate` minima invece della prima dopo l'edizione chiusa |

Nota su D-14…D-17: Q-54 (APERTA) registra la scelta implementata, ma la motiva con «nessuna fonte dice cosa fare» mentre docs/03 §4.2 definisce il rimborso; la decisione va presa aggiornando docs/03 oppure il codice.

## 21. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 32 (R-01…R-32) |
| Rami del codice mappati | 97 (B-01…B-97) |
| Rami senza specifica | 29 (B-03 trim, B-07, B-08, B-11 ordine, B-13, B-15, B-16, B-18, B-21, B-26 tempistica, B-33, B-34, B-40, B-46, B-47 default, B-49 tipo sconosciuto, B-50, B-64 anteprima, B-66, B-73, B-77, B-78, B-81, B-85, B-86 `progressPct`, B-91, B-94, B-95, B-97 default) — 17 provati da righe AMBIGUO; 12 non eseguiti (eventi o richieste fuori contratto, dati incoerenti, formula non specificata) |
| Rami non raggiungibili | 3 (B-08 via HTTP, B-29, B-30) |
| Regole non implementate o implementate diversamente | 11 (vedi fine §2) |
| Righe del testbook | 373 |
| Righe per area | POL 24 · CLR 33 · GRT 65 · TUP 19 · REL 5 · WVW 17 · API 11 · MBR 3 · LIA 7 · JOB 14 · EXP 16 · WRN 9 · SPD 24 · REF 11 · ADJ 43 · CUR 14 · TAD 18 · EDN 16 · ECL 24 |
| Tabelle decisionali complete | GRT-001…048 (48 = 2 × 3 × 2 × 4), CLR-001…032 (32 = 4 × 8) |
| Combinazioni ridotte | rettifiche: 48 combinazioni valide → 12 righe a coppie (ADJ-001…012) + 22 classi non valide da sole + 8 limiti da soli; accredito × stato del membro: 192 → 48 + 3 stati da soli; ruoli dei job: 7 × 3 → 7 + 2 + 2; chiusura in integrazione: rappresentanti per esito (tabella completa in §13) |
| Righe AMBIGUO | 28 (§19) |
| Divergenze | 18 righe (D-01…D-18), 11 cause distinte |
