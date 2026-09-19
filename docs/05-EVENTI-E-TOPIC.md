# 05 — Eventi e topic Kafka

Contratto tra i servizi. Ciò che è scritto qui è vincolante (vedi `CLAUDE.md §6`).

## 1. Topic

Il Kafka gratuito consente **5 topic da 2 partizioni** (ADR-004): il disegno ne usa esattamente cinque. I nomi sono configurabili (`loyaltyhub.topics.*`) ma i default sono questi.

| Topic | Contenuto | Chiave | Produttori | Consumatori |
|---|---|---|---|---|
| `lh.actions.v1` | azioni premianti canoniche, esterne **e interne** | `memberId` | **solo** `ingestion` | `campaign`, `gamification`, `member`, `insight` |
| `lh.effects.v1` | effetti decisi dal motore | `memberId` | **solo** `campaign` | `wallet`, `reward`, `gamification`, `engagement`, `insight` |
| `lh.facts.v1` | fatti di dominio di tutti i servizi | `memberId`, oppure id aggregato per fatti di configurazione | tutti tranne `insight` | tutti (filtrano per `type`) |
| `lh.audit.v1` | audit delle scritture da backoffice e dei job | `entityType:entityId` | tutti | `insight` |
| `lh.dlq.v1` | messaggi non elaborabili | chiave originale | tutti (error handler) | `insight` |

Parametri: 2 partizioni, retention del provider (3 giorni nel free tier; il sistema di record è Postgres). Consumer group = `lh-<servizio>`. `auto.offset.reset=earliest`. In locale i topic sono creati da bean `NewTopic` in `lh-common`; su Aiven vanno creati a mano una volta (`docs/11 §3`).

Un consumer che riceve un `type` che non gli interessa lo **ignora senza errore** (e senza scrivere `processed_event`).

## 2. Envelope — CloudEvents 1.0, JSON strutturato

Valore del record = CloudEvent completo, `content-type: application/cloudevents+json`. Header Kafka duplicati per filtrare senza parse: `lh-type`, `lh-correlation-id`.

| Attributo | Obbl. | Regola |
|---|---|---|
| `specversion` | sì | `"1.0"` |
| `id` | sì | ULID (26 caratteri). Per le fonti esterne è l'id fornito dalla fonte (dedup su `source`+`id`) |
| `source` | sì | `urn:loyaltyhub:source:<codice fonte>` per le azioni; `urn:loyaltyhub:service:<servizio>` per effetti, fatti, audit |
| `type` | sì | `io.loyaltyhub.<famiglia>.<nome>` con famiglia `action`, `effect`, `fact`, `audit` |
| `subject` | sì | `member:<memberId>`; per fatti di configurazione `<entityType>:<id>` |
| `time` | sì | quando è accaduto (RFC 3339, UTC). Data di business |
| `datacontenttype` | sì | `application/json` |
| `dataschema` | sì | `urn:loyaltyhub:schema:<famiglia>.<nome>:<versione>` |
| `lhtenant` | sì | `aurora` nel PoC |
| `lhcorrelationid` | sì | id dell'azione radice della catena |
| `lhcausationid` | no | id dell'evento che ha causato questo |
| `lhhop` | sì | 0 per le azioni esterne; +1 a ogni passaggio dal ponte interno; > 3 → DLQ `LOOP_GUARD` |
| `lhactor` | no | `<ruolo>:<username>` se originato da backoffice, `system` per i job, `member:<id>` dal portale |
| `data` | sì | payload specifico del tipo |

In ingresso dalle fonti esterne sono obbligatori solo: `specversion, id, source, type, subject, time, data`. Gli attributi `lh*` li aggiunge `ingestion`. Le fonti esterne usano `type` breve o completo: `purchase.completed` ≡ `io.loyaltyhub.action.purchase.completed`.

### Esempio — azione
```json
{
  "specversion": "1.0",
  "id": "01J8ZK3V7Q2M9T4B6N8R0XWYCD",
  "source": "urn:loyaltyhub:source:ecommerce",
  "type": "io.loyaltyhub.action.purchase.completed",
  "subject": "member:MBR-000003",
  "time": "2026-09-18T10:15:00Z",
  "datacontenttype": "application/json",
  "dataschema": "urn:loyaltyhub:schema:action.purchase.completed:1",
  "lhtenant": "aurora",
  "lhcorrelationid": "01J8ZK3V7Q2M9T4B6N8R0XWYCD",
  "lhhop": 0,
  "data": {
    "orderId": "ORD-88213", "amount": 130.00, "currency": "EUR", "channel": "ONLINE",
    "items": [ { "sku": "SKU-1001", "category": "casa", "quantity": 1, "unitPrice": 130.00 } ]
  }
}
```

### Esempio — effetto
```json
{
  "specversion": "1.0", "id": "01J8ZK3W1A…", "source": "urn:loyaltyhub:service:campaign",
  "type": "io.loyaltyhub.effect.points.grant", "subject": "member:MBR-000003",
  "time": "2026-09-18T10:15:00Z", "dataschema": "urn:loyaltyhub:schema:effect.points.grant:1",
  "datacontenttype": "application/json", "lhtenant": "aurora",
  "lhcorrelationid": "01J8ZK3V7Q2M9T4B6N8R0XWYCD", "lhcausationid": "01J8ZK3V7Q2M9T4B6N8R0XWYCD", "lhhop": 0,
  "data": {
    "effectId": "9F2C7A51B3D84E6FA0C1D2E3F4", "campaignCode": "CMP-PURCHASE-BASE",
    "actionId": "01J8ZK3V7Q2M9T4B6N8R0XWYCD", "actionType": "purchase.completed",
    "currency": "PTS", "baseAmount": 130, "campaignMultiplier": 1.0, "amount": 130,
    "tierMultiplierApplies": true, "pendingDays": 0, "description": "Punti per acquisto ORD-88213"
  }
}
```
`time` degli effetti e dei fatti derivati = `time` dell'azione radice quando rappresentano la stessa data di business (accrediti), altrimenti l'istante di elaborazione.

### Esempio — fatto
```json
{
  "specversion": "1.0", "id": "01J8ZK3X5C…", "source": "urn:loyaltyhub:service:wallet",
  "type": "io.loyaltyhub.fact.wallet.points.earned", "subject": "member:MBR-000003",
  "time": "2026-09-18T10:15:00Z", "dataschema": "urn:loyaltyhub:schema:fact.wallet.points.earned:1",
  "datacontenttype": "application/json", "lhtenant": "aurora",
  "lhcorrelationid": "01J8ZK3V7Q2M9T4B6N8R0XWYCD", "lhcausationid": "01J8ZK3W1A…", "lhhop": 0,
  "data": {
    "ledgerEntryId": "01J8ZK3X4Z…", "effectId": "9F2C7A51B3D84E6FA0C1D2E3F4", "campaignCode": "CMP-PURCHASE-BASE",
    "currency": "PTS", "baseAmount": 130, "tierCode": "SILVER", "tierMultiplier": 1.25, "amount": 162,
    "balanceAfter": 2012, "lotId": "01J8ZK3X4Y…", "expiresAt": "2027-09-30T21:59:59Z", "pending": false
  }
}
```

## 3. Catalogo — azioni (`lh.actions.v1`, prefisso `io.loyaltyhub.action.`)

| ID | Nome | Origine | `data` |
|---|---|---|---|
| EVT-ACT-01 | `purchase.completed` | ecommerce, app | `orderId*, amount*, currency*, channel (ONLINE/STORE/APP), items[] {sku, category, quantity, unitPrice}` |
| EVT-ACT-02 | `purchase.returned` | ecommerce | `orderId*, amount*` |
| EVT-ACT-03 | `ebill.activated` | billing | `contractId*` |
| EVT-ACT-04 | `directdebit.activated` | billing | `contractId*` |
| EVT-ACT-05 | `selfreading.submitted` | app | `meterId*, reading*` |
| EVT-ACT-06 | `app.login.daily` | app | `platform (IOS/ANDROID/WEB)` |
| EVT-ACT-07 | `survey.completed` | partner | `surveyId*, score` |
| EVT-ACT-08 | `quiz.completed` | partner | `quizId*, correctAnswers*, totalQuestions*` |
| EVT-ACT-09 | `review.submitted` | ecommerce | `productId*, rating* (1–5)` |
| EVT-ACT-10 | `newsletter.subscribed` | crm | — |
| EVT-ACT-20 | `member.registered` | internal ← `fact.member.registered` | `channel, referred (bool)` |
| EVT-ACT-21 | `member.profile.completed` | internal | — |
| EVT-ACT-22 | `member.birthday` | internal | `age` |
| EVT-ACT-23 | `tier.upgraded` | internal ← `fact.tier.upgraded` | `previousTier, newTier` |
| EVT-ACT-24 | `instantwin.won` | internal ← `fact.contest.won` | `contestCode, prizeCode, prizeType (POINTS/COUPON/PHYSICAL), points?, rewardCode?` |
| EVT-ACT-25 | `achievement.completed` | internal | `achievementCode, periodKey` |
| EVT-ACT-26 | `badge.awarded` | internal | `badgeCode` |
| EVT-ACT-27 | `referral.completed` | internal | `role (REFERRER/REFEREE), counterpartMemberId` |
| EVT-ACT-28 | `reward.redeemed` | internal ← `fact.reward.redemption.confirmed` | `rewardCode, pointsCost` |
| EVT-ACT-9x | tipi **custom** | qualsiasi fonte | definito da backoffice (JSON Schema) |

`*` = obbligatorio. Il ponte copia i campi `data` omonimi dal fatto all'azione.

## 4. Catalogo — effetti (`lh.effects.v1`, prefisso `io.loyaltyhub.effect.`)

Campi comuni in `data`: `effectId*, campaignCode*, actionId*, actionType*`.

| ID | Nome | Campi specifici | Consumatore |
|---|---|---|---|
| EVT-EFF-01 | `points.grant` | `currency*, baseAmount*, campaignMultiplier*, amount*, tierMultiplierApplies*, pendingDays*, description` | wallet |
| EVT-EFF-02 | `plays.grant` | `contestCode*, count*` | gamification |
| EVT-EFF-03 | `coupon.issue` | `rewardCode*` | reward |
| EVT-EFF-04 | `badge.award` | `badgeCode*` | gamification |
| EVT-EFF-05 | `message.send` | `templateCode*, params{}` | engagement |

## 5. Catalogo — fatti (`lh.facts.v1`, prefisso `io.loyaltyhub.fact.`)

| ID | Nome | Produttore | `data` essenziale |
|---|---|---|---|
| EVT-FACT-01 | `member.registered` | member | snapshot completo: `memberId, firstName, lastName, nickname, email, externalId, status, channel, registeredAt, birthDate, city, referralCode, referredBy, attributes{}, labels[]` |
| EVT-FACT-02 | `member.updated` | member | snapshot completo (come sopra) |
| EVT-FACT-03 | `member.status.changed` | member | `previousStatus, newStatus, reason` |
| EVT-FACT-04 | `member.profile.completed` | member | — |
| EVT-FACT-05 | `member.birthday` | member | `age` |
| EVT-FACT-06 | `member.segment.entered` | member | `segmentCode` |
| EVT-FACT-07 | `member.segment.left` | member | `segmentCode` |
| EVT-FACT-08 | `referral.completed` | member | `role, counterpartMemberId, qualifyingActionId` |
| EVT-FACT-10 | `campaign.evaluated` | campaign | `actionId, actionType, matched[] {campaignCode, effects[] {type, currency?, amount?}}, skipped[] {campaignCode, reason}` |
| EVT-FACT-11 | `campaign.status.changed` | campaign | `campaignCode, name, previousStatus, newStatus` |
| EVT-FACT-20 | `wallet.points.earned` | wallet | vedi esempio §2 |
| EVT-FACT-21 | `wallet.points.spent` | wallet | `ledgerEntryId, currency, amount, balanceAfter, redemptionId` |
| EVT-FACT-22 | `wallet.spend.rejected` | wallet | `redemptionId, reason (INSUFFICIENT_BALANCE/MEMBER_NOT_ACTIVE), requested, available` |
| EVT-FACT-23 | `wallet.points.refunded` | wallet | `redemptionId, amount, balanceAfter` |
| EVT-FACT-24 | `wallet.points.expired` | wallet | `currency, amount, balanceAfter` |
| EVT-FACT-25 | `wallet.points.expiring` | wallet | `currency, amount, expiresAt` |
| EVT-FACT-26 | `wallet.points.adjusted` | wallet | `direction (CREDIT/DEBIT), currency, amount, reason, note, balanceAfter` |
| EVT-FACT-27 | `wallet.points.released` | wallet | `currency, amount, balanceAfter` |
| EVT-FACT-28 | `tier.upgraded` | wallet | `previousTier, newTier, periodSts` |
| EVT-FACT-29 | `tier.downgraded` | wallet | `previousTier, newTier, editionCode` |
| EVT-FACT-30 | `tier.retained` | wallet | `tier, editionCode` |
| EVT-FACT-31 | `edition.closed` | wallet | `editionCode, retained, downgraded` (chiave `edition:<code>`) |
| EVT-FACT-40 | `reward.redemption.requested` | reward | `redemptionId, rewardCode, rewardName, currency, pointsCost` |
| EVT-FACT-41 | `reward.redemption.confirmed` | reward | `redemptionId, rewardCode, rewardName, pointsCost` |
| EVT-FACT-42 | `reward.redemption.fulfilled` | reward | `redemptionId, rewardCode, couponCode?, note?` |
| EVT-FACT-43 | `reward.redemption.rejected` | reward | `redemptionId, reason` |
| EVT-FACT-44 | `reward.redemption.cancelled` | reward | `redemptionId, reason, refund (bool), pointsCost` |
| EVT-FACT-45 | `coupon.issued` | reward | `couponCode, rewardCode, expiresAt, origin (REDEMPTION/CAMPAIGN)` |
| EVT-FACT-46 | `coupon.used` | reward | `couponCode` |
| EVT-FACT-50 | `contest.plays.granted` | gamification | `contestCode, count, effectId` |
| EVT-FACT-51 | `contest.played` | gamification | `contestCode, playId, outcome (WIN/LOSE)` |
| EVT-FACT-52 | `contest.won` | gamification | `contestCode, playId, prizeCode, prizeName, prizeType, points?, rewardCode?` |
| EVT-FACT-53 | `achievement.progressed` | gamification | `achievementCode, periodKey, value, target` |
| EVT-FACT-54 | `achievement.completed` | gamification | `achievementCode, achievementName, periodKey` |
| EVT-FACT-55 | `badge.awarded` | gamification | `badgeCode, badgeName, origin (ACHIEVEMENT/CAMPAIGN)` |
| EVT-FACT-60 | `message.delivered` | engagement | `templateCode, channel, inboxMessageId` |
| EVT-FACT-61 | `content.status.changed` | engagement | `contentId, kind, previousStatus, newStatus` |

Analoghi `reward.status.changed`, `contest.status.changed` (chiave = codice oggetto) seguono la forma di EVT-FACT-11.

## 6. Audit (`lh.audit.v1`)

Un solo tipo: `io.loyaltyhub.audit.entry`. `lhactor` obbligatorio.
```json
{ "service": "wallet", "entityType": "WALLET", "entityId": "MBR-000004",
  "action": "ADJUST", "summary": "Accredito manuale di 200 PTS (GOODWILL)",
  "before": { "balance": 12300 }, "after": { "balance": 12500 } }
```
`action` ∈ `CREATE, UPDATE, DELETE, TRANSITION, ADJUST, JOB, RESET`. `before/after` contengono solo i campi cambiati.

## 7. Ponte interno (fatti → azioni)

Tabella `ingestion.internal_mapping` (seed, modificabile da BO-09):

| Fatto | Azione generata |
|---|---|
| `fact.member.registered` | `action.member.registered` |
| `fact.member.profile.completed` | `action.member.profile.completed` |
| `fact.member.birthday` | `action.member.birthday` |
| `fact.tier.upgraded` | `action.tier.upgraded` |
| `fact.contest.won` | `action.instantwin.won` |
| `fact.achievement.completed` | `action.achievement.completed` |
| `fact.badge.awarded` | `action.badge.awarded` |
| `fact.referral.completed` | `action.referral.completed` |
| `fact.reward.redemption.confirmed` | `action.reward.redeemed` |

Regole: nuovo `id`; `source = urn:loyaltyhub:source:internal`; stesso `subject`, `time`, `lhcorrelationid`; `lhcausationid` = id del fatto; `lhhop` + 1. Mai mappabili: `wallet.points.*`, `wallet.spend.*`, `campaign.*`, `message.*`, `*.status.changed`.

## 8. Matrice dei consumi

| Servizio | da `lh.actions.v1` | da `lh.effects.v1` | da `lh.facts.v1` |
|---|---|---|---|
| ingestion | — | — | `member.registered/updated/status.changed` (indice membri) · tipi del ponte |
| member | tutte (statistiche attività, qualifica referral) | — | `tier.*`, `wallet.points.*` (proiezione saldi/tier) |
| campaign | tutte | — | `member.*`, `member.segment.*`, `tier.*` (snapshot) · `wallet.points.earned` (budget effettivo) |
| wallet | — | `points.grant` | `member.registered` (crea wallet + tier BASE) · `member.status.changed` · `reward.redemption.requested` · `reward.redemption.cancelled` |
| reward | — | `coupon.issue` | `wallet.points.spent` · `wallet.spend.rejected` · `member.*`, `tier.*`, `member.segment.*` (snapshot visibilità) |
| gamification | tutte (obiettivi, classifiche a conteggio) | `plays.grant`, `badge.award` | `member.registered/updated` (nickname) · `wallet.points.earned` (classifiche a punti) |
| engagement | — | `message.send` | tutti (regole di notifica, webhook) · snapshot membro per il pubblico dei contenuti |
| insight | tutte | tutti | tutti (+ `lh.audit.v1`, `lh.dlq.v1`) |

## 9. Schemi e versioni

- `contracts/events/envelope.schema.json` + `contracts/events/<famiglia>/<nome>.schema.json` (JSON Schema 2020-12); `contracts/examples/<famiglia>.<nome>.json` = esempio valido per ogni tipo.
- Test di contratto: ogni esempio valida contro lo schema; ogni produttore ha un test che valida l'evento realmente prodotto.
- I consumatori **tollerano campi sconosciuti** e non falliscono su campi opzionali assenti.
- Compatibile (nuovo campo opzionale) → stessa versione. Incompatibile → `:<n+1>` in `dataschema`, doppia lettura temporanea, ADR.
