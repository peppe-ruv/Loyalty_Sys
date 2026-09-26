# reward-service

**Porta** 8085 · **Schema** `reward` · **Feature** `F-RWD-*`, `F-CPN-*` · **Milestone** M4 (catalogo, fasce, pool, saga), M5 (coupon da effetto `coupon.issue`), M6 (visibilità per segmento), M7 (approvazione `LEGAL`)

## 1. Scopo e confini
Catalogo premi organizzato per **fasce**, pool di coupon, **richieste premio** e loro evasione. Avvia la saga di spesa e reagisce all'esito del wallet.
Non conosce i saldi: il controllo del saldo è del wallet. Il campo `reachable` del catalogo portale lo calcola il **frontend** incrociando catalogo e saldo (due chiamate indipendenti).

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `reward_category` | `code` PK, `name`, `icon`, `sort_order` |
| `reward_band` | `code` PK (`F1`…`F5`), `name`, `points_threshold` UQ, `color`, `sort_order` |
| `reward` | `id`, `code` UQ, `name`, `description`, `terms`, `image_url`, `type` (`PHYSICAL, COUPON, DIGITAL, DONATION, EXPERIENCE`), `category_code`, `band_code`, `fulfilment` (`AUTO_COUPON, MANUAL, INSTANT`), `coupon_pool_id` null, `stock_total` null = illimitato, `stock_remaining`, `per_member_limit` null, `eligible_tiers text[]`, `eligible_segments text[]`, `valid_from`, `valid_to`, `status` (ciclo §3.6 di `docs/03`), `version`, `created_by`, `updated_at` |
| `coupon_pool` | `id`, `code` UQ, `name`, `prefix`, `validity_days`, `total`, `available` |
| `coupon` | `code` PK, `pool_id`, `status` (`AVAILABLE, ISSUED, USED, EXPIRED, VOID`), `member_id`, `reward_code`, `origin` (`REDEMPTION, CAMPAIGN`), `redemption_id`, `effect_id` UQ null, `issued_at`, `expires_at`, `used_at` |
| `redemption` | `id` (ULID), `member_id`, `reward_code`, `reward_name`, `points_cost`, `status` (`PENDING, CONFIRMED, FULFILLED, REJECTED, CANCELLED`), `reject_reason`, `needs_attention bool`, `coupon_code`, `fulfilment_note`, `shipping jsonb`, `correlation_id`, `requested_at`, `confirmed_at`, `closed_at`, `actor` |
| `reward_member_snapshot` | `member_id` PK, `status`, `tier_code`, `segments text[]`, `first_name`, `last_name`, `updated_at` (da fatti). Prefisso `reward_` per non collidere con `campaign.member_snapshot` nel search_path dell'hub (ADR-023) |

## 3. API
### Gestione
| Metodo | Path | Note |
|---|---|---|
| GET/POST/PUT | `/v1/reward-categories` | |
| GET/POST/PUT/DELETE | `/v1/reward-bands` | soglie uniche e crescenti (`422 BAND_THRESHOLD_DUPLICATE`); non eliminabile se ha premi (`409 BAND_IN_USE`) |
| GET/POST/PUT | `/v1/rewards`, `/v1/rewards/{id}` | filtri `status, band, category, type, q`; modifica ammessa in `DRAFT`/`REJECTED`/`PAUSED`; in `LIVE` solo `stock_total`, `valid_to`, `image_url`. `PUT` con `version` obbligatoria (`422 VERSION_REQUIRED` se assente, `409 VERSION_CONFLICT` se superata; Q-280). `fulfilment=AUTO_COUPON` richiede `couponPoolId` (`422 REWARD_INVALID` in creazione e modifica; la pubblicazione di un AUTO_COUPON senza pool è rifiutata con lo stesso codice; Q-281) |
| POST | `/v1/rewards/{id}/transitions` | `{action, comment}` — macchina a stati comune (`docs/06 §7`) |
| POST | `/v1/rewards/{id}/duplicate` | copia in `DRAFT` con codice `-COPY` |
| GET/POST | `/v1/coupon-pools` | |
| POST | `/v1/coupon-pools/{id}/generate` | `{count ≤ 5000}` → codici `prefix-XXXX-XXXX` |
| POST | `/v1/coupon-pools/{id}/import` | `{codes[]}`; duplicati → riepilogo `{imported, skipped[]}` |
| GET | `/v1/coupon-pools/{id}/coupons` | filtri `status, memberId` |
| GET | `/v1/coupons/{code}` · POST `/v1/coupons/{code}/use` · POST `/v1/coupons/{code}/void` | `409 COUPON_ALREADY_USED`, `410 COUPON_EXPIRED`, `404`. `void` solo da `AVAILABLE` (ritiro di un codice mai emesso) e `ISSUED` valido; da `USED`/`VOID`/scaduto `409` (`COUPON_EXPIRED` per uno scaduto, che resta `EXPIRED`; Q-278) |
| GET | `/v1/redemptions` | filtri `status, memberId, rewardCode, needsAttention, from, to` |
| GET | `/v1/redemptions/{id}` | con cronologia stati |
| POST | `/v1/redemptions/{id}/fulfil` | `{note, tracking?}` — solo `CONFIRMED` e `fulfilment=MANUAL`; ruoli `CARE/ADMIN` |
| POST | `/v1/redemptions/{id}/cancel` | `{reason}` — `CONFIRMED` → `CANCELLED` con `refund=true`; ruoli `CARE/ADMIN` |
| GET | `/v1/approvals` | formato comune |
| GET | `/v1/rewards/stats` | top premi, richieste per stato, stock sotto soglia (< 10 %) |

### Portale
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/portal/catalog?memberId=` | `{bands[]: {code, name, pointsThreshold, rewards[]: {code, name, type, imageUrl, category, pointsCost, stockState (AVAILABLE/LOW/SOLD_OUT), lockedByTier?: {requiredTiers[]}, perMemberLimitReached}}}` — include i premi bloccati per tier (mostrati con lucchetto), esclude quelli fuori segmento |
| GET | `/v1/portal/rewards/{code}?memberId=` | dettaglio + `terms` |
| POST | `/v1/portal/redemptions` | `{memberId, rewardCode, shipping?}` → **202** `{redemptionId, status: PENDING, correlationId}` |
| GET | `/v1/portal/redemptions?memberId=` · `/v1/portal/redemptions/{id}` | il portale interroga fino a stato finale o `CONFIRMED` |
| POST | `/v1/portal/redemptions/{id}/cancel` | `?memberId=` obbligatorio (`400` se assente, `404` se la richiesta è di un altro membro; Q-283); solo `PENDING` |
| GET | `/v1/portal/coupons?memberId=` | `{code, rewardName, status, issuedAt, expiresAt, origin}` |

Errori di validazione immediata su `POST redemptions`: `422 REWARD_NOT_AVAILABLE`, `REWARD_SOLD_OUT`, `MEMBER_LIMIT_REACHED`, `MEMBER_NOT_ACTIVE`, `TIER_NOT_ELIGIBLE`, `SHIPPING_REQUIRED` (per `PHYSICAL`).

### Demo
| POST | `/v1/demo/jobs/expire-coupons?asOf=` · `/v1/demo/jobs/timeout-redemptions?asOf=` | |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Consuma | `lh.effects.v1` | `coupon.issue` |
| Consuma | `lh.facts.v1` | `wallet.points.spent`, `wallet.spend.rejected`, `member.registered/updated/status.changed`, `member.segment.entered/left`, `tier.upgraded/downgraded` |
| Produce | `lh.facts.v1` | `reward.redemption.requested/confirmed/fulfilled/rejected/cancelled`, `coupon.issued`, `coupon.used`, `reward.status.changed` |
| Produce | `lh.audit.v1` | scritture su catalogo, fasce, pool, evasioni, annulli |

## 5. Regole
Dominio in `docs/03 §5`. Note implementative:
- **Richiesta**: in un'unica transazione valida, decrementa `stock_remaining` con `UPDATE … WHERE stock_remaining > 0` (0 righe → `REWARD_SOLD_OUT`), inserisce `redemption PENDING`, scrive in outbox `reward.redemption.requested`. Il `correlationId` della richiesta HTTP diventa quello di tutta la saga.
- **`wallet.points.spent`** → `CONFIRMED` + fatto `confirmed`. Poi, secondo `fulfilment`: `AUTO_COUPON` → preleva un codice (`SELECT … FOR UPDATE SKIP LOCKED LIMIT 1` su `coupon AVAILABLE` del pool), `ISSUED`, scadenza = oggi + `validity_days` (fine di quel giorno, 23:59:59.999999 `Europe/Rome`: data di emissione a Roma + `validity_days`, Q-277), fatti `coupon.issued` + `fulfilled`; `INSTANT` (donazioni, digitali) → `FULFILLED` subito; `MANUAL` → resta `CONFIRMED` in coda a BO-13.
- Pool vuoto → `needs_attention = true`, nessun fatto `fulfilled`; BO-13 mostra l'avviso; dopo una nuova generazione di codici `POST /v1/redemptions/{id}/retry-fulfilment`.
- **`wallet.spend.rejected`** → `REJECTED` con motivo, stock ripristinato.
- **Stock residuo** (Q-280): non supera mai `stock_total − richieste PENDING/CONFIRMED/FULFILLED` (mai sotto zero): vale alla modifica del totale (anche da illimitato a limitato) e a ogni ripristino (rifiuto, timeout, annullo).
- **Timeout**: job ogni minuto, `PENDING` da più di 10 min → `REJECTED (TIMEOUT)`, stock ripristinato. Se `wallet.points.spent` arriva dopo il timeout → emette `reward.redemption.cancelled` con `refund=true` (compensazione) e log `WARN`.
- **Annullo** da `CONFIRMED`: stock ripristinato, eventuale coupon `VOID`, fatto `cancelled` con `refund=true` → il wallet rimborsa.
- `coupon.issue` da campagna: idempotenza su `effect_id`; usa il pool del premio indicato; pool vuoto → DLQ `COUPON_POOL_EMPTY` (non ritentabile).
- Eventi consumati per richieste sconosciute → ignorati con log `WARN` (non DLQ).
- Job giornaliero: coupon `ISSUED` scaduti → `EXPIRED`.

## 6. Seed
`seed/reward-categories.json`, `seed/reward-bands.json`, `seed/rewards.json`, `seed/coupon-pools.json` (codici generati con seme fisso → stessi codici a ogni reset), `seed/redemptions.json` (storico + la richiesta manuale in attesa di Sofia, i 2 coupon di Davide). Dettaglio in `docs/10 §5`.

## 7. Accettazione minima
- Davide richiede `RWD-SHOP-10` (1 500 PTS) → `202`; entro 10 s stato `FULFILLED` con coupon; saldo −1 500; stock −1.
- Anna (100 PTS) richiede `RWD-COFFEE-5` → `202`, poi `REJECTED (INSUFFICIENT_BALANCE)`; stock invariato.
- Premio con `stock_remaining=1` e due richieste concorrenti → una `PENDING`, l'altra `422 REWARD_SOLD_OUT`.
- `RWD-WEEKEND` richiesto da membro `SILVER` → `422 TIER_NOT_ELIGIBLE`; nel catalogo compare con `lockedByTier`.
- Annullo di una richiesta `CONFIRMED` → `wallet.points.refunded`, stock +1, coupon `VOID`.
- `POST /v1/coupons/{code}/use` due volte → seconda `409`.
- Rielaborazione di `wallet.points.spent` → nessun secondo coupon.

## 8. Fase 2 (profilo `enterprise`)

Riferimento: `docs/18`. Le righe qui sotto sono segnaposto dell'adozione (M8.0): la fetta citata le rende normative aggiornando questa scheda.

- **Cataloghi esterni** (ADR-045, M13.6): `fulfilment=EXTERNAL`, `reward_provider` con adattatori generici, saga riserva → spesa → conferma con rilascio e rimborso automatico, sincronizzazione in `DRAFT`, stato `DEGRADED`; nuovo fatto `reward.redemption.refunded`.
- **Dati personali** (ADR-032, M8.4): `reward_member_snapshot.first_name`/`last_name` escono dagli snapshot; i contatti per la spedizione li inoltra member-service.

**Classificazione `x-lh-class`** (`docs/18 §3.15`, F2-GRC-05; prima stesura M8.0, verificata e resa per colonna in M8.13). Tutto ciò che non è elencato è `INTERNAL`.
- `PERSONAL`: `redemption.shipping`, `reward_member_snapshot.first_name`, `last_name` (fino a M8.4).
- `CONFIDENTIAL`: `coupon.code` non ancora emesso, `coupon_pool.prefix`.
- `PUBLIC`: `reward` pubblicati (nome, descrizione, termini, immagine), `reward_category`, `reward_band`.
