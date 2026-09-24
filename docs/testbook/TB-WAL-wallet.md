# Testbook: Wallet (TB-WAL)

## 1. Accredito Punti (Grant e Lotti)

### Regola (F-WAL-03, F-WAL-05, F-TIER-03)
Ogni accredito (da effetti `points.grant` o rettifica `CREDIT`) genera un lotto.
- Se `pendingDays > 0`, il lotto è `PENDING` fino a `availableAt = oggi + pendingDays`, altrimenti è `ACTIVE`.
- Se `tierMultiplierApplies = true` e valuta = `PTS`, i punti base vengono moltiplicati per il moltiplicatore di tier corrente (`floor(base * multiplier)`).
- La scadenza `expiresAt` è calcolata dalla policy della valuta (`ROLLING_MONTHS` o `END_OF_EDITION_PLUS_GRACE`).
- Membro in stato non attivo (es. `ANONYMIZED`): rifiuto per anonimizzati.

### Domini di Valore e Classi di Equivalenza
- **Valuta**: `PTS`, `STS`
- **pendingDays**: `0`, `>0`
- **tierMultiplierApplies**: `true`, `false`
- **Stato Membro**: `ACTIVE`, `ANONYMIZED`

### Strategia di Combinazione
Decision table per Valuta × pendingDays × tierMultiplierApplies, più test specifici per stati del membro.

| ID | Valore | Valuta | pendingDays | tierMultApplies | Stato | Atteso | Rif | Test |
|---|---|---|---|---|---|---|---|---|
| TB-WAL-GRT-001 | PTS base 100, no mult | PTS | 0 | false | ACTIVE | ACTIVE, 100 PTS | F-WAL-03 | TestbookWalletGrantIT |
| TB-WAL-GRT-002 | PTS base 100, pending | PTS | 5 | false | ACTIVE | PENDING, 100 PTS | F-WAL-05 | TestbookWalletGrantIT |

## 2. Spesa FIFO (Spend)

### Regola (F-WAL-04)
La spesa consuma i lotti `ACTIVE` della valuta in ordine `FIFO`: prima scadenza (`expiresAt` crescente), a parità di scadenza, data di accredito (`earnedAt` crescente). Lotti senza scadenza (`null`) in coda.

### Strategia

| ID | Condizione | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WAL-SPD-001 | Spesa 1500, lotti [A: 500 ott, B: 800 dic, C: 900 mar] | Consuma 500 A, 800 B, 200 C | F-WAL-04 | TestbookWalletSpendIT#spendFifoOrder |

## 3. Rimborsi (Refunds)

### Regola (F-WAL-08, Q-54)
Rimborso di spesa restituisce i punti in un nuovo lotto o nel lotto originale in base alla scadenza.

| ID | Condizione | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WAL-REF-001 | Rimborso totale, lotti originali scadenza valida | Ripristino nei lotti d'origine | Q-54 | TestbookWalletRefundIT#pointsReturnedToValidLots |

## 4. Scadenza (Expiry) e Calcolo Date

### Regola (F-WAL-06)
- `ROLLING_MONTHS(n)`: ultimo istante del mese (23:59:59 Europe/Rome) di `earned_at + n mesi`.

| ID | Condizione | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WAL-EXP-001 | ROLLING(12), earned 15 Gen | Scadenza: 31 Gen (anno prox) 23:59:59 CET | docs/03 §4 | TestbookWalletExpiryTest#expiryRollingMonthsStandard |
| TB-WAL-EXP-002 | ROLLING(1), earned 28 Feb (non bisestile) | Scadenza: 31 Mar 23:59:59 CEST | docs/03 §4 | TestbookWalletExpiryTest#expiryRollingMonthsDST |

## 5. Tier (Salita e Discesa)

### Regola (F-TIER-02, F-TIER-04)
- **Salita immediata**: dopo accredito `STS`, se `periodSts >= soglia` di tier superiore -> Upgrade (il più alto raggiunto).
- **Chiusura edizione**: (earned = tier per periodSts), (floor = attuale - 1), nuovo = max(earned, floor).

| ID | Condizione | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WAL-TIR-004 | Chiusura, GOLD (periodSts=500 -> BASE) | Downgrade a SILVER (floor=GOLD-1) | F-TIER-04 | TestbookWalletTierTest |
| TB-WAL-TIR-005 | Chiusura, PLATINUM (periodSts=7000) | Retained PLATINUM | F-TIER-04 | TestbookWalletTierTest |
| TB-WAL-TIR-006 | Chiusura, SILVER (periodSts=4000) | Retained SILVER | F-TIER-04 | TestbookWalletTierTest |

## 6. Rettifiche Manuali (Adjustments)

### Regola (F-WAL-07)
Rettifica `CREDIT` o `DEBIT`. Richiesto: reason.

| ID | Condizione | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WAL-ADJ-005 | CREDIT 100 STS (non spendibile) | Rifiuto (422) | Q-46 | TestbookWalletAdjustmentIT#cannotAdjustStsCurrency |
| TB-WAL-ADJ-006 | CREDIT 100 PTS su ANONYMIZED | Rifiuto (409) | Q-127 | TestbookWalletAdjustmentIT#cannotAdjustAnonymizedMember |

## Copertura
- **Regole analizzate:** 15
- **Testcase definiti (Row):** 11
- **Divergenze trovate:** Nessuna.
