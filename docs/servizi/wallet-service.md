# wallet-service

**Porta** 8084 · **Schema** `wallet` · **Feature** `F-WAL-*`, `F-TIER-*`, `F-DEMO-06` · **Milestone** M1 (accrediti, saldi, movimenti), M3 (lotti, scadenze, tier, edizioni, rettifiche), M4 (saga di spesa, rimborsi)

## 1. Scopo e confini
Libro mastro dei punti a **doppia valuta**, lotti con scadenza, **tier** ed edizioni annuali. Applica gli effetti `points.grant`, partecipa alla saga delle richieste premio.
Non decide *quanti* punti dare (lo fa il motore); applica solo il moltiplicatore di tier, che è un vantaggio del livello.

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `currency` | `code` PK (`PTS`,`STS`), `name`, `spendable`, `expiry_policy jsonb` (`{type: ROLLING_MONTHS, months: 12}` …) |
| `wallet` | (`member_id`,`currency`) PK, `balance_active`, `balance_pending`, `lifetime_earned`, `lifetime_spent`, `lifetime_expired`, `updated_at` |
| `points_lot` | `id`, `member_id`, `currency`, `amount`, `remaining`, `status` (`PENDING, ACTIVE, EXHAUSTED, EXPIRED`), `earned_at`, `available_at`, `expires_at`, `ledger_entry_id` · indice (`member_id`,`currency`,`status`,`expires_at`) |
| `ledger_entry` | `id` (ULID), `member_id`, `currency`, `type`, `amount` (sempre positivo), `direction` (`+`/`−`), `balance_after`, `occurred_at` (data di business), `created_at`, `source_type` (`CAMPAIGN, REDEMPTION, MANUAL, SYSTEM`), `effect_id` UQ null, `redemption_id`, `campaign_code`, `action_id`, `correlation_id`, `description`, `actor`, `metadata jsonb` |
| `lot_consumption` | `ledger_entry_id`, `lot_id`, `amount` (per rimborsi e audit FIFO) |
| `tier` | `code` PK, `name`, `rank`, `threshold_sts`, `multiplier numeric(4,2)`, `benefits jsonb` (elenco testi), `color`, `icon` |
| `member_tier` | `member_id` PK, `tier_code`, `since`, `period_sts`, `previous_tier`, `member_status` |
| `tier_history` | `id`, `member_id`, `from_tier`, `to_tier`, `kind` (`UPGRADE, DOWNGRADE, RETAIN, INITIAL`), `edition_code`, `at` |
| `edition` | `code` PK, `name`, `start_date`, `end_date`, `redemption_grace_until`, `status` |

## 3. API
### Gestione
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/wallets/{memberId}` | `{balances: {PTS:{active,pending,lifetimeEarned,…}, STS:{…}}, expiringSoon: {amount, within30d, nextExpiryAt}, tier: {code, name, since, periodSts, next?: {code, threshold, missing}, progressPct, multiplier, keepWarning?}}` |
| GET | `/v1/wallets/{memberId}/ledger` | filtri `currency, type, from, to`; ordinamento `occurredAt desc` |
| GET | `/v1/wallets/{memberId}/lots` | lotti non esauriti, per scadenza |
| POST | `/v1/wallets/{memberId}/adjustments` | `{currency, direction (CREDIT/DEBIT), amount, reason (GOODWILL/CORRECTION/COMPLAINT/TEST), note ≥ 10 caratteri}` — ruoli `CARE/ADMIN`; `422 INSUFFICIENT_BALANCE`, `NOTE_TOO_SHORT` |
| GET | `/v1/members/{memberId}/tier-history` | |
| GET/PUT | `/v1/tiers`, `/v1/tiers/{code}` | soglie crescenti col rank (`422 TIER_THRESHOLDS_NOT_MONOTONIC`); `BASE` ha soglia 0 fissa |
| GET/PUT | `/v1/currencies`, `/v1/currencies/{code}` | policy di scadenza (vale per i nuovi lotti) |
| GET/POST/PUT | `/v1/editions` | niente sovrapposizioni |
| POST | `/v1/editions/{code}/close?dryRun=true` | anteprima: `{summary: {retained, downgraded}, members[]: {memberId, currentTier, periodSts, earnedTier, newTier, outcome (RETAINED/DOWNGRADED)}}`; con `dryRun=false` applica (ruolo `ADMIN`) |
| GET | `/v1/liability` | `{currency, outstanding, pending, byExpiryMonth[]}` |
| GET | `/v1/tiers/distribution` | conteggio membri per tier |

### Portale
| GET | `/v1/portal/wallets/{memberId}` | come sopra, senza campi interni |
| GET | `/v1/portal/tiers` | scala dei livelli per il portale: `{code, name, threshold, multiplier, benefits[], color}` (PT-08) |
| GET | `/v1/portal/wallets/{memberId}/activity` | movimenti in forma leggibile: `{id, occurredAt, title, subtitle, amount, direction, currency, icon, pending, expiresAt?, breakdown?}` |

### Demo (ruolo `ADMIN`)
| POST | `/v1/demo/jobs/expire-points?asOf=` · `/release-pending?asOf=` · `/expiry-warnings?asOf=` | eseguono i job con data di riferimento |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Consuma | `lh.effects.v1` | `points.grant` |
| Consuma | `lh.facts.v1` | `member.registered` (crea 2 wallet + `member_tier` BASE), `member.status.changed`, `reward.redemption.requested`, `reward.redemption.cancelled` (con `refund=true`) |
| Produce | `lh.facts.v1` | `wallet.points.*`, `wallet.spend.rejected`, `tier.upgraded/downgraded/retained`, `edition.closed` |
| Produce | `lh.audit.v1` | rettifiche, modifiche a tier/valute/edizioni, job |

## 5. Regole
Tutte in `docs/03 §4`. Note implementative:
- Ogni operazione su un wallet prende il lock di riga (`SELECT … FOR UPDATE` su `wallet`) → serializzazione per membro/valuta.
- `points.grant`: unicità su `effect_id` = idempotenza di dominio. Se il membro non ha wallet (fatto `member.registered` non ancora arrivato) → crea wallet e tier BASE *on the fly*.
- Dopo un accredito `STS` → verifica salita nello stesso commit.
- Spesa da richiesta premio: valuta sempre `PTS`; idempotenza su `redemption_id`.
- Job schedulati (disattivabili con `loyaltyhub.jobs.enabled`): rilascio pending ogni ora; scadenze alle 02:00; preavvisi alle 09:00 (una volta per lotto: flag su `points_lot`). In demo si lanciano da BO-30.
- Chiusura edizione: un'unica transazione per lotti da 200 membri; emette un fatto per membro + `edition.closed`.
- Calcolo `expires_at` per `ROLLING_MONTHS(n)`: ultimo istante del mese di `earned_at + n mesi`, in `Europe/Rome`.
- `keepWarning`: valorizzato da ottobre se `periodSts` < soglia del tier attuale: `{tier, missing}`.

## 6. Seed
`seed/tiers.json`, `seed/currencies.json`, `seed/editions.json` (2025 `CLOSED`, 2026 `ACTIVE`, 2027 `PLANNED`), `seed/wallets.json` (saldi, `periodSts`, lotti con date **relative a oggi**, 8–25 movimenti storici per membro). Il seeder verifica l'invariante: Σ lotti attivi = saldo.

## 7. Accettazione minima
- Effetto `PTS` 130 con `tierMultiplierApplies` per membro `SILVER` → movimento `EARN` 162, `metadata.baseAmount=130`, lotto con scadenza a fine mese +12.
- Stesso `effectId` due volte → un solo movimento.
- Giulia (`SILVER`, 2 880 STS) + 130 STS → `tier.upgraded` a `GOLD` nello stesso commit.
- Spesa 1 500 con lotti [500 scad. ott, 800 scad. dic, 900 scad. mar] → consumati 500 + 800 + 200; `lot_consumption` coerente.
- Spesa oltre il saldo → nessun movimento, fatto `wallet.spend.rejected` con `available`.
- Chiusura 2026 in `dryRun`: Stefano (`GOLD`, 650 STS) → `SILVER` (guadagnato `BASE`, pavimento `SILVER`).
- Rettifica in addebito oltre il saldo → `422 INSUFFICIENT_BALANCE`; senza nota → `422`.
