# Catalogo funzionale — parità con l'edizione attuale di Open Loyalty

Riferimento (17 settembre 2026): la serie ufficiale "Open Loyalty Feature Showcase" (37 demo) e la documentazione
dell'edizione attuale su [help.openloyalty.io](https://help.openloyalty.io/llms.txt). Questo catalogo elenca, area per
area, cosa fa Open Loyalty oggi e cosa fa Loyalty Hub, con il requisito (RF) e il componente owner. Le funzionalità
dell'edizione open source (RF-60..RF-79) sono in `PARITA-OPEN-LOYALTY.md`; qui i requisiti da RF-80 in poi.

Fonti aperte: [campaigns](https://help.openloyalty.io/campaigns/campaigns/campaigns-and-referral-campaigns/creating-campaigns.md) ·
[trigger types](https://help.openloyalty.io/campaigns/campaigns/campaigns-and-referral-campaigns/creating-campaigns/trigger-types.md) ·
[limitation](https://help.openloyalty.io/campaigns/campaigns/campaign-limitation.md) ·
[expressions](https://help.openloyalty.io/integrations-and-data-exchange/expressions.md) · [attributes](https://help.openloyalty.io/integrations-and-data-exchange/expressions/attributes-list.md) ·
[follow-up](https://help.openloyalty.io/campaigns/campaigns/follow-up-campaigns.md) · [automation](https://help.openloyalty.io/campaigns/campaigns/automation-campaigns.md) ·
[simulation](https://help.openloyalty.io/campaigns/campaigns/campaign-simulation.md) · [expiration & pending](https://help.openloyalty.io/campaigns/campaigns/custom-units-expiration-and-pending-settings.md) ·
[percent value distribution](https://help.openloyalty.io/campaigns/campaigns/campaigns-and-referral-campaigns/creating-campaigns/percent-value-distribution.md) ·
[referral](https://help.openloyalty.io/campaigns/campaigns/campaigns-and-referral-campaigns/creating-campaigns/referral-campaign.md) ·
[challenges](https://help.openloyalty.io/campaigns/challenges/creating-challenges.md) · [achievements](https://help.openloyalty.io/campaigns/achievements/creating-achievement.md) ·
[leaderboards](https://help.openloyalty.io/gamification/leaderboards/creating-leaderboards.md) · [fortune wheels](https://help.openloyalty.io/gamification/fortune-wheels/how-probability-works.md) ·
[badges](https://help.openloyalty.io/gamification/badges.md) · [tiers](https://help.openloyalty.io/gamification/tiers/tiers-configuration.md) · [tier benefits](https://help.openloyalty.io/gamification/tiers/tier-benefits.md) ·
[wallets](https://help.openloyalty.io/members-and-activity/wallets/wallet-types-and-configuration.md) · [unit transfers](https://help.openloyalty.io/members-and-activity/wallets/unit-transfers.md) ·
[custom event schemas](https://help.openloyalty.io/members-and-activity/custom-events/custom-event-schemas.md) · [custom fields](https://help.openloyalty.io/integrations-and-data-exchange/custom-fields.md) ·
[collections](https://help.openloyalty.io/integrations-and-data-exchange/collections.md) · [product catalog](https://help.openloyalty.io/integrations-and-data-exchange/product-catalog.md) ·
[reward types](https://help.openloyalty.io/rewards-redemption/rewards/reward-list/reward-types.md) · [reward fulfillment](https://help.openloyalty.io/rewards-redemption/rewards/reward-fulfillment.md) ·
[segment conditions](https://help.openloyalty.io/members-and-activity/members/segments/segment-conditions.md) · [member configuration](https://help.openloyalty.io/members-and-activity/members/configuration.md) ·
[analytics](https://help.openloyalty.io/global-management/analytics.md).

## 1. Campagne (RF-80..RF-86) — `rules-engine`, CMS `campaigns`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Trigger: Purchase transaction, Return transaction, Internal event (tier change, activation, profile update, achievement progressed), Custom event (da schema), Achievement, Redemption code | `Campaign.Trigger` con gli stessi sei tipi più SCHEDULE (automazioni); gli internal event sono le azioni interne (TIER_CHANGED, MEMBER_ENROLLED, PROFILE_COMPLETED, ACHIEVEMENT_PROGRESSED...) | RF-80 |
| Fino a 6 regole, 30 condizioni per regola; tutte le condizioni di una regola in AND; regole indipendenti | `Campaign.MAX_RULES`, `MAX_CONDITIONS`, condizioni dichiarative (attributo/operatore/valore) o espressioni | RF-80, RF-84 |
| Effetti: Add/Deduct units (formula), Give reward, Set/Remove member attribute, Grant badge, Assign tier | `Campaign.Effect.Type`: ADD_UNITS, DEDUCT_UNITS (fisso, per euro, formula), GIVE_REWARD, SET_ATTRIBUTE, REMOVE_ATTRIBUTE, GRANT_BADGE, ASSIGN_TIER, EMIT_EVENT | RF-81 |
| Limiti: esecuzioni per membro per periodo (orario, giornaliero, settimanale, mensile, annuale, totale); budget globale in unità; unità per membro per periodo; su data di business; storni restituiscono budget | `Campaign.Limits` + `CampaignEvaluator.Usage` (contatori dal ledger); budget scalato al residuo, motivo `UNITS_BUDGET` | RF-82 |
| Scadenza e sospensione per effetto con formula (`add_days_to_date(transaction.purchasedAt, 15)`), prevalgono sul wallet | `Effect.expiresAtExpression`, `pendingUntilExpression` → `Posting.expiresAt/pendingUntil` → ledger | RF-83 |
| Espressioni (Symfony ExpressionLanguage): transaction.*, customer.*, wallet.*, event.body.*, executionContext.processedAt, referrer.*; funzioni round_up/round_down/to_date/timestamp/add_days_to_date/percent_value_distribution | `ExpressionEngine` su Spring SpEL: `#transaction`, `#member`, `#wallets`, `#lines`, `#event`, `#executionContext`, `#fn.*` (stesse funzioni + `in_collection`, `days_between`); contesto in sola lettura, espressioni compilate | RF-84 |
| Referral campaign: premi a presentatore e presentato all'azione del presentato, catena multilivello (L1 100, L2 50...), max referral per presentatore | `Campaign.Kind.REFERRAL` + `ReferralChain.distribute` (effetto con `params.level`), tetto in `member-service.ReferralPolicy` | RF-85 |
| Automation campaigns: giornaliere/settimanali/mensili dopo mezzanotte, compleanno, anniversario; pubblico tutti/segmenti/tier/filtro; max 4 campagne e 200.000 membri; follow-up tramite segmento "campagna completata negli ultimi X giorni" (~10 min); percent value distribution; simulazione con membro reale o descritto | `AutomationScheduler` (SCHEDULE_TICK per membro del pubblico, limiti in `settings.limits`), `Segment.Type.CAMPAIGN_COMPLETED`, `PercentValueDistribution`, `POST /v1/simulations` con campagne in bozza e motivi di scarto | RF-86 |
| Visibilità: tutti, segmenti, tier, nascosta (non limita l'esecuzione) | `Campaign.Visibility` + BFF `/api/content/campaigns` | RF-82 |
| Attributi custom della campagna | `customAttributes` + campi custom entità CAMPAIGN | RF-99 |

## 2. Wallet e unità (RF-87..RF-89) — `ledger`, CMS `wallets`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Wallet types: codice, nomi unità singolare/plurale, traduzioni, scadenza (nessuna, dopo N giorni, fine mese, fine anno, data annuale), pending (subito o bloccati N giorni), limiti globali e per membro, saldo negativo, attivo; almeno un wallet di default | `WalletType` (+ END_OF_NEXT_PROGRAM_YEAR per il programma annuale), `WalletTypeSource`; PREMIO e STATUS di sistema, altri (es. BOLLINI) dal backoffice; `Currency` = codice wallet | RF-87 |
| Unit transfers: aggiunta, spesa, blocco, scadenza, annullo, trasferimento tra membri, import, etichette, commento | `LedgerService.post/debit/block/transfer/expire/releasePending/reverse`, `Movement.kind` (EARN, SPEND, EXPIRY, REVERSAL, BLOCK, UNBLOCK, TRANSFER_OUT, TRANSFER_IN, MANUAL), `labels` | RF-88 |
| Vista wallet per membro: attivi, maturati, spesi, bloccati, in sospeso, scaduti (usata dalle espressioni) | `Balance` con totali cumulati, `GET /v1/ledger/members/{id}/wallets` → `#wallets['PREMIO'].earned` | RF-89 |

## 3. Gamification (RF-90..RF-97) — `engagement-service`, `contest-service`, CMS `achievements`, `challenges`, `badges`, `leaderboards`, `fortune-wheels`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Achievement: trigger transazione o evento custom, progresso per occorrenze o valore attributo, obiettivo overall / ultimi X giorni / consecutivo (giorno, settimana lun-dom, mese, anno), limite eventi per periodo, completamenti illimitati o per periodo/totale, modifica = azzeramento progressi, aggiornamento progresso via API | `Achievement`, `AchievementEngine` (streak in Europe/Rome, valori unici), versione della definizione, azioni interne ACHIEVEMENT_PROGRESSED / ACHIEVEMENT_COMPLETED, `POST /v1/progress` | RF-90 |
| Challenge: fino a 6 milestone in qualunque ordine, tipo Direct o Referral, trigger transazione/evento, condizioni, progresso per occorrenze/valore/attributi unici, streak, limiti eventi, finestre (giorni, ore, date), visibilità, regole su avanzamento o completamento con condizione sul conteggio, limiti e budget | `Challenge`, `ChallengeEngine`; missioni del programma (RF-21) = challenge con `programYear`; azioni CHALLENGE_PROGRESSED / CHALLENGE_COMPLETED / MISSION_COMPLETED | RF-91 |
| Badge: codice di sistema immutabile, assegnazione manuale o da campagna, conteggio, uso in condizioni e segmenti, profilo membro | `Badge`, `Badge.Grant` (stackable), `POST /v1/badges/{code}/grants` idempotente, `Segment.Type.BADGE`, `#member['badges']` | RF-92 |
| Leaderboard: metrica (unità maturate...), periodo senza retroattività, gruppi per attributo o indirizzo (<500), top 1000 per gruppo, pari merito stessa posizione, ricalcolo ogni 4 ore, max 3 attive | `Leaderboard.rank` (competition ranking), `LeaderboardJobs.rank` ogni 4 h, gruppo da etichetta/campo custom, `topN` | RF-93 |
| Rewarding cycle: premi ai primi N per gruppo a fine ciclo | `Leaderboard.RewardingCycle`, `LeaderboardJobs.closeCycles` (premio, unità, badge) | RF-94 |
| Fortune wheel: spicchi con premi e pesi, probabilità indipendenti, livello di business logic (stock, budget, vincite per membro), costo in unità, giri per periodo | `FortuneWheel` + `WheelService`; **in Italia con premi = concorso a premi**: modalità `INSTANT_WIN_BACKED` obbligatoria (spicchio vincente solo su istante vincente periziato), `PROBABILITY` solo per premi a valore nullo; hook CMS che lo impone | RF-95 |
| Vista membro: timeline, tier, transazioni, eventi, campagne, challenge, achievement, premi, referral, badge, classifiche, campi custom | BFF `/api/members/{id}/*` (badges, achievements, challenges, leaderboards, wallets, custom-fields, tier-progress) | RF-96 |
| Sample challenges (Celebrate Summer, Frequent Buyer, Consistent Player, SKU Sell-off, Workout Streak, Shopping Buddies, Referral Boost) | Tutte esprimibili: finestra date, ACTION_COUNT, streak settimanale, filtro SKU, evento custom con streak, milestone REFERRAL, referral con soglia | RF-97 |

## 4. Tier (RF-105..RF-107) — `tier-service`, CMS `tier-sets`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Tier set con fino a 8 condizioni: unità attive, unità maturate totali, spesa totale, mesi dall'adesione, unità maturate nel periodo; soglie per tier; upgrade immediato | `TierSet` (+ CUSTOM_FIELD), `Match.ALL/ANY`, `qualified`, `duringPeriod` | RF-105 |
| Discesa: None, Automatic, Anniversary, Custom dates, Interval (mesi dall'ultima promozione) | `TierSet.Downgrade.Mode` + ANNUAL_ONE_LEVEL (D06, default), `nextReview` | RF-106 |
| Benefici una tantum (via campagna) e continuativi (sconto %, moltiplicatore), analytics per tier, progresso al successivo | `Tier.Benefits` (sconto, moltiplicatore, premi d'ingresso), `GET /v1/tiers/members/{id}/progress`, `missingToNext` | RF-107 |

## 5. Membri e dati (RF-98..RF-101, RF-108..RF-110) — `member-service`, `ingress-adapters`, `segment-service`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Custom event schemas: identificatore, nome, attributi Boolean/Datetime/Number/Text, attivo; attributi disponibili nelle condizioni | `EventSchema` = catalogo dei tipi azione (RF-01), validazione all'ingresso con scarto motivato, `lenient`, `GET /v1/schemas`, `POST /v1/schemas/{type}/validate` | RF-98 |
| Custom fields: gruppi, tipi String (lunghezza, regex), Number (min/max), Boolean, Date, Single/Multi select da collezione, sottogruppi ripetibili (membri), per membri/campagne/premi/ruote, permessi separati, in segmenti, webhook, export | `CustomFieldSchema` + `CustomFieldController` (validazione, righe ripetibili, ruolo di modifica), `Segment.Type.CUSTOM_FIELD / DATE_FIELD_IN_DAYS` | RF-99 |
| Collections: liste riutilizzabili fino a 1.000.000 di valori, CSV o singolo valore, usate nelle condizioni | `CollectionController` (segment-service), `#fn.in_collection`, `Segment.Type.IN_COLLECTION`, select dei campi custom | RF-100 |
| Product catalog: copia in sola lettura per tenant (SKU, nome, categoria, marca, prezzo, attributi) da CSV; ricerca; regole "prodotto → punti" una volta sola | `Product`, `ProductCatalog.enrich` (arricchimento righe all'ingresso), import CSV, ricerca, dizionario categorie | RF-101 |
| Identificatori: email, telefono, tessera; obbligatorio (uno), univoco, abbinamento eventi in ordine di priorità; tessera generata (formato, lunghezza 4-64, prefisso ≤ 8); definizione di membro attivo | `MemberIdentifiers` (+ OIDC_SUB, CRM_ID, SAP_BP), `CardGenerator`, `POST /v1/members/resolve`, `ActiveMemberRule` | RF-108 |
| Segment conditions edizione attuale: compleanno/registrazione entro N giorni, età, località, valore giornaliero, canale, marca/SKU/attributi prodotto, tier, achievement completato/progresso, eventi custom recenti, lista custom | 30 criteri in `Segment.Type` (età e località tramite campi custom: nessun dato anagrafico duplicato, D12) | RF-109 |
| Referred members, opt-out, GDPR, resi e annulli | `member-service` (referral, stati, anonimizzazione), `RedemptionState.refunds()` | RF-68, RF-73, RF-103 |

## 6. Premi (RF-102..RF-104) — `catalog-redemption`, CMS `rewards`

| Open Loyalty | Loyalty Hub | RF |
| --- | --- | --- |
| Reward types: material (foto, spedizione), coupon a valore fisso o dinamico (formula su transazione, filtri categoria/marca/SKU, arrotondamenti), units conversion coupon, percentage coupon; liste coupon; categorie; "claim for" | 10 tipi (RF-74) + `UnitsConversion` (tasso, min, max, passo), `dynamic_value_expression`, lotti codici, categorie, `POST /v1/grants` | RF-102 |
| Fulfillment: stati Pending, Approved, Packing, Waiting for shipping, Shipped, Completed, Returned, Rejected, Cancelled, Issued; cambio massivo; annullo con rimborso | `RedemptionState` superset con transizioni e rimborso automatico (CANCELLED, REJECTED, RETURNED), `POST /v1/redemptions/transitions` massivo, storico stati | RF-103 |
| Pay with points, promo e sconti in carrello | `POST /v1/pay-with-points` (unità per importo, sconto, chiave di storno), BFF `/api/members/{id}/pay-with-points`, tier `discountPercent` per il carrello | RF-104 |

## 7. Amministrazione e integrazione (RF-111..RF-116) — CMS, `notifier`, `read-model`

| Open Loyalty | Loyalty Hub | RF | Stato |
| --- | --- | --- | --- |
| Analytics: membri registrati, attivi, transazioni, media transazioni, unità, tier, referral, campagne | Cruscotto RF-46 esteso; `read-model` espone le stesse metriche (`/v1/read/analytics/*`) | RF-111 | da completare nel read-model |
| Ruoli con permessi granulari (ACL), admin, chiavi API, SSO Okta/Entra/Auth0 | CMS `roles` con 20 permessi, SSO OIDC (RF-43), chiavi API per fonte nel gateway | RF-112 | coperto |
| Webhook: sottoscrizioni per tipo evento, HMAC con rotazione segreto, notifiche di scadenza, SQS | RF-78 + evento di scadenza imminente (job ledger) e rotazione segreto | RF-113 | parziale |
| Imports (membri, segmenti, transazioni, trasferimenti, codici, coupon, stati premi, collezioni, attributi, catalogo) ed exports (UI CSV, configurazione, S3 con struttura dati) | Import: membri, transazioni (batch), codici, coupon, collezioni, catalogo; export: segmenti CSV, portabilità membro, CDC verso data platform; export configurazione = collezioni CMS in JSON | RF-114 | parziale (S3 export da fare) |
| Translations, tenants (store), config duplication, usage/limits, system logs, bulk actions | Localizzazione it/en su CMS; tenant singolo (RF-79); duplicazione configurazione = export/import JSON; limiti in `settings.limits`; audit RF-41 | RF-115, RF-116 | parziale (multi-tenant fuori ambito) |
| Integrazioni: Zapier, Lambda, EventBridge, Kafka, Braze, MCP server | Kafka nativo (RI-06), webhook (RF-78), CDC; connettore marketing automation via webhook; MCP server COULD | RI-06 | coperto/COULD |

## Nuovi requisiti (RF-80..RF-116)

- RF-80 Una campagna ha un trigger (transazione, reso, evento interno, evento custom da schema, achievement, codice, pianificazione), fino a 6 regole con al più 30 condizioni ciascuna (AND dentro la regola, regole indipendenti), date di attività, ordine di esecuzione per data di inizio.
- RF-81 Effetti di campagna: aggiungi/sottrai unità a un wallet (valore fisso, per euro, formula), assegna premio, imposta/rimuovi attributo del membro, assegna badge, assegna tier, emetti evento interno.
- RF-82 Limiti: esecuzioni per membro per periodo, budget totale in unità, unità per membro per periodo, sulla data di business; visibilità tutti/segmenti/tier/nascosta; il simulatore mostra campagne scattate, effetti e motivi di esclusione.
- RF-83 Scadenza e sospensione delle unità calcolate da formula per effetto (es. 30 giorni dalla transazione); in assenza valgono le impostazioni del wallet.
- RF-84 Linguaggio di espressioni sicuro (SpEL in sola lettura) con contesto transazione/righe, membro (tier, segmenti, badge, attributi), wallet (attivi, maturati, spesi, sospesi, bloccati, scaduti), evento, presentatore, istante di elaborazione, e funzioni di arrotondamento, date, collezioni e distribuzione per fasce.
- RF-85 Campagne referral multilivello: le azioni del presentato premiano la catena dei presentatori con effetti per livello e tetto per presentatore.
- RF-86 Campagne di automazione pianificate (giornaliere, settimanali, mensili, compleanno, anniversario) su un pubblico, con limiti configurabili; campagne a catena tramite segmento "campagna completata"; distribuzione percentuale per fasce cumulative.
- RF-87 Wallet configurabili: codice, nomi unità, scadenza (nessuna, giorni, fine mese, fine anno, data annuale, fine anno programma successivo), sospensione, limiti, saldo negativo, stato; PREMIO e STATUS di sistema.
- RF-88 Operazioni sulle unità: accredito, spesa, blocco/sblocco, scadenza, storno, trasferimento tra membri, etichette e commento; tutte idempotenti e nel registro immutabile.
- RF-89 Vista wallet per membro con unità attive, maturate, spese, in sospeso, bloccate, scadute, disponibile alle espressioni e all'area membro.
- RF-90 Achievement con obiettivo overall, finestra mobile o streak per periodo di calendario, limiti di eventi e di completamenti, valori unici; definizione versionata; avanzamento e completamento come azioni interne; aggiornamento manuale tracciato.
- RF-91 Challenge con fino a 6 milestone (dirette o da referral), finestre di disponibilità, visibilità, regole su avanzamento/completamento con effetti e condizione sul conteggio, limiti; le missioni del programma annuale sono challenge.
- RF-92 Badge con codice immutabile, assegnazione manuale/da campagna/da challenge, conteggio, uso in condizioni e segmenti.
- RF-93 Classifiche su metrica e periodo, senza retroattività, con gruppi, top 1000, pari merito, ricalcolo ogni 4 ore, massimo configurabile di classifiche attive.
- RF-94 Ciclo premiante delle classifiche: premi, unità o badge ai primi N per gruppo a ogni chiusura di ciclo, idempotenti.
- RF-95 Ruota della fortuna con spicchi pesati, costo, giri per periodo, stock, budget e vincite per membro; con premi di valore è agganciata agli istanti vincenti periziati (DPR 430/2001).
- RF-96 Area membro con timeline, wallet, tier e progresso, challenge, achievement, badge, classifiche, campi custom.
- RF-97 Le sette challenge di esempio di Open Loyalty sono configurabili senza codice.
- RF-98 Schemi dei tipi azione con attributi tipizzati e obbligatori; validazione all'ingresso con scarto motivato; modalità permissiva per fonti legacy.
- RF-99 Campi custom tipizzati e validati per membri (anche ripetibili), campagne, premi e ruote, con permesso di modifica per ruolo e uso nei segmenti.
- RF-100 Collezioni di valori riutilizzabili nelle condizioni di campagne e segmenti e nei select dei campi custom.
- RF-101 Catalogo prodotti in sola lettura importato da CSV, usato per arricchire le righe delle transazioni.
- RF-102 Buoni a valore dinamico (formula) e a conversione di unità (tasso, minimo, massimo, passo); categorie; assegnazione manuale.
- RF-103 Stati di evasione a parità con Open Loyalty, cambio massivo, storico, rimborso automatico su annullo/rifiuto/reso.
- RF-104 Paga con i punti: unità per importo con sconto restituito al carrello e chiave di storno; sconto di tier sul carrello.
- RF-105 Tier set con fino a 8 condizioni (unità attive, maturate, spesa, mesi, unità nel periodo, campo custom) in AND o OR e soglie per tier; upgrade immediato.
- RF-106 Modalità di discesa: nessuna, automatica, anniversario, date fisse, intervallo dall'ultima promozione, annuale di un livello.
- RF-107 Benefici di tier continuativi (sconto, moltiplicatore) e una tantum (premi d'ingresso); progresso verso il tier successivo per condizione.
- RF-108 Identificatori del membro con obbligatorietà, univocità e priorità di abbinamento; tessera generata; definizione di membro attivo.
- RF-109 Criteri di segmento estesi: registrazione recente, date dei campi custom, campi custom, valore giornaliero, eventi custom recenti, achievement, challenge, campagne completate, badge, collezioni.
- RF-110 Riscatto e "claim for" dal backoffice; stato del referral e dei presentati nella vista membro.
- RF-111 Analytics: membri registrati e attivi, transazioni e media, unità, tier, referral, campagne, premi, con intervallo di date.
- RF-112 Ruoli con permessi granulari, chiavi API per fonte, SSO OIDC.
- RF-113 Webhook con rotazione del segreto e notifica di scadenza imminente delle unità.
- RF-114 Import ed export: membri, transazioni, trasferimenti, codici, coupon, stati premi, collezioni, catalogo; export CSV e configurazione; export dati verso storage (S3) con struttura documentata.
- RF-115 Contenuti multilingua (it, en) e traduzioni dell'interfaccia.
- RF-116 Limiti di piattaforma configurabili (classifiche attive, automazioni, pubblico) e report d'uso.

## Cosa è entrato nello scaffold (17 settembre 2026, 0.3.0)

| Componente | Modifica |
| --- | --- |
| `rules-engine` | package `campaign`: `Campaign`, `CampaignEvaluator`, `ExpressionEngine`/`SpelExpressionEngine`, `PercentValueDistribution`, `ReferralChain`, `AutomationScheduler`, `CollectionSource`, `CampaignSource`; `SimulationController`; consumer su campagne con tutti gli effetti |
| `ledger` | wallet configurabili (`WalletType`), `Currency` = codice, blocco/sblocco, trasferimenti P2P, totali cumulati, tipo movimento, scadenza/sospensione da formula, migrazione V3 |
| `engagement-service` (nuovo) | `Achievement`/`AchievementEngine`, `Challenge`/`ChallengeEngine`, `Badge`, `Leaderboard` + job di ranking e cicli premianti, API |
| `contest-service` | `FortuneWheel`, `WheelService` (modalità instant win), API e migrazione V2 |
| `ingress-adapters` | `EventSchema` + validazione all'ingresso, catalogo prodotti con arricchimento righe, API |
| `segment-service` | collezioni, 12 criteri in più (campi custom, badge, achievement, challenge, campagne, collezioni) |
| `member-service` | campi custom con schemi e validazione, identificatori e tessera, risoluzione eventi |
| `tier-service` | `TierSet` con condizioni multiple, modalità di discesa, benefici, progresso, assegnazione da campagna |
| `catalog-redemption` | stati OL-compatibili con storico e cambio massivo, `UnitsConversion`, paga con i punti |
| CMS | collezioni `campaigns`, `wallets`, `event-schemas`, `custom-field-schemas`, `collections`, `channels`, `achievements`, `challenges`, `badges`, `leaderboards`, `fortune-wheels`, `tier-sets`, `roles`; localizzazione it/en; settings estese |
| BFF | area membro gamification/wallet/paga con i punti/campi custom; operatore: badge, blocchi, simulazioni, stati massivi |

Verifica: 33 + 34 asserzioni standalone (Smoke2, Smoke3) verdi più i JUnit per campagne con SpEL, achievement/challenge/classifiche, ruota, tier set, schemi.
