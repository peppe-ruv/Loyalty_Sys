# Parità funzionale con Open Loyalty

Vincolo (17 settembre 2026): il prodotto deve offrire **come minimo** le funzionalità di Open Loyalty, con riferimento
all'edizione open source (repository `kwarambatendai/openloyalty`, fork di OpenLoyalty/open-loyalty 4.x, PHP/Symfony) e,
come traccia per il "dopo", alle funzionalità dell'edizione attuale documentata su help.openloyalty.io.

Tutte le voci dell'edizione open source hanno oggi un requisito (RF) e un servizio owner nello scaffold. Le voci
dell'edizione commerciale che non coincidono con un requisito esistente sono classificate SHOULD/COULD.

Fonti: [Open Loyalty user guide 4.x](https://docs.openloyalty.io/en/latest/userguide/) ·
[earning rules](https://docs.openloyalty.io/en/latest/userguide/earning_rules/index.html) ·
[reward campaigns](https://docs.openloyalty.io/en/latest/userguide/reward_campaigns/index.html) ·
[segments](https://docs.openloyalty.io/en/latest/userguide/segments/index.html) ·
[API 4.x](https://docs.openloyalty.io/en/latest/api/index.html) · [help center edizione attuale](https://help.openloyalty.io/sitemap.md).

## Traduzione dei concetti

| Open Loyalty | Loyalty Hub (Iren) |
| --- | --- |
| Customer | Membro: identità = `sub` OIDC dell'IAM Iren (D12), nessuna anagrafica duplicata |
| Transaction con items | Azione premiante `TRANSACTION` con righe `lines[]` (SKU, categoria, marca, importo, etichette) |
| POS / Store, Seller | Canale (`channel`: web, app, sportello, negozio, call-center, partner) e postazione operatore |
| Level | Tier (punti STATUS, anno programma, discesa morbida) |
| Points (unico wallet) | Doppia valuta PREMIO / STATUS (D05) |
| Earning rule | Regola punti versionata (RF-05, RF-63..RF-66) |
| Reward campaign | Premio del catalogo per fascia (RF-14, RF-74..RF-76) |
| Segment | Segmento dinamico/statico (RF-71) |
| Marketing automation webhook | Webhook sottoscrivibile (RF-78) + topic Kafka (RI-06) |
| Fortune wheel (edizione attuale) | Instant win con istanti vincenti pre-generati (RF-30..RF-39, DPR 430/2001) |

## Matrice di parità (edizione open source = MUST)

Stato: **coperto** = già nella specifica e nello scaffold prima del 17/9; **nuovo** = aggiunto il 17/9 (RF-60..RF-79);
**parziale** = coperto in modo diverso, con la differenza indicata.

| Area OL | Funzionalità Open Loyalty | Stato | RF | Servizio / componente |
| --- | --- | --- | --- | --- |
| Customers | Registrazione, attivazione, login | parziale → coperto | D12, RF-72 | IAM Iren via OIDC; adesione al programma in `member-service` |
| Customers | Etichette chiave/valore sul cliente | nuovo | RF-72 | `member-service` (`labels` JSONB) |
| Customers | Consensi, stato attivo/inattivo, cancellazione | nuovo | RF-72, RF-73 | `member-service` |
| Customers | Anonimizzazione e cancellazione GDPR | nuovo | RF-73 | `member-service` → evento `MEMBER_V1` per il CRM |
| Customers | Import clienti (XML) | nuovo | RF-72 | `member-service` `/v1/members/import` (CSV) |
| Customers | Assegnazione manuale del livello | nuovo | RF-70 | `tier-service` override |
| Customers | Assegnazione al POS | nuovo | RF-61 | canale di adesione e canale per azione |
| Customers | Referral (codice, premio a presentatore e presentato) | nuovo | RF-68 | `member-service` `ReferralPolicy` |
| Customers | Vista dettaglio: punti, trasferimenti, transazioni, premi, referral | coperto | RF-44 | backoffice + `read-model` |
| Levels | Soglie, upgrade, verifica periodica, discesa | coperto | RF-10..RF-13 | `tier-service` |
| Levels | Premio di livello (sconto %, premi speciali) | nuovo | RF-12, RF-76 | `tiers.discountPercent`, `welcomeRewards` |
| Points transfers | Accredito, spesa, scadenza, annullo, manuale | coperto | RF-04, RF-09, RF-18 | `ledger` (+ job scadenza `LedgerJobs`) |
| Points transfers | Punti in sospeso (locked) | nuovo | RF-66 | `ledger.movement.available_at`, `Balance.pending` |
| Points transfers | Import trasferimenti | coperto | RI-01 | ingresso batch REST/file (RI-04) |
| Transactions | Import con righe articolo (SKU, etichette, categoria, marca) | nuovo | RF-62 | `TransactionLine` in `common` |
| Transactions | Abbinamento al cliente (email, telefono, tessera, documento) | coperto | RI-02, RF-03 | `identity-mapping`; priorità in RF-79 |
| Transactions | Resi e storni | coperto | RF-04, RI-08 | `TRANSACTION_RETURNED` = storno |
| Transactions | Etichette di transazione, filtro per POS | nuovo | RF-61, RF-62 | attributi `labels`, `channel` |
| Earning rules | General spending (punti per unità di spesa, SKU/etichette escluse, consegna esclusa) | nuovo | RF-63 | `Rule.Earning`, `LineFilter` |
| Earning rules | Multiply earned points (anche per etichetta prodotto) | nuovo | RF-64 | `Earning.multiplier` + `LineFilter` |
| Earning rules | Product purchase | nuovo | RF-62 + RF-05 | condizioni su righe |
| Earning rules | Custom event rule | coperto | RF-01, RF-05 | `rules-engine` |
| Earning rules | Event rule (registrazione, primo acquisto, newsletter, anniversario) | nuovo | RF-60 | azioni interne di `member-service` |
| Earning rules | Customer referral | nuovo | RF-68 | `REFERRAL_COMPLETED`, `REFERRED_ENROLLED` |
| Earning rules | Geolocation | nuovo | RF-67 | `CHECK_IN` + operatore `GEO_WITHIN` |
| Earning rules | QR code | nuovo | RF-69 | `ingress-adapters` codici + `CODE_REDEEMED` |
| Earning rules | Instant reward | nuovo | RF-76 | `Earning.autoRewardId` → `catalog-redemption /v1/grants` |
| Earning rules | Periodo, target livelli/segmenti, POS, limiti d'uso, "ultima regola", foto | parziale → coperto | RF-05, RF-65, RF-66 | `Rule.Target`, `Rule.Limits`, CMS `point-rules` |
| Segments | 14 criteri, export, attiva/disattiva, uso in regole/premi/livelli | nuovo | RF-71 | `segment-service` (18 criteri) |
| Reward campaigns | 8 tipi: cashback, custom, discount code, free delivery, gift, invitation, percentage, value code | nuovo | RF-74 | `RewardDefinition.Type` (10 tipi) |
| Reward campaigns | Costo in punti, limiti totali e per cliente, finestre, target, categorie, marca, foto | nuovo | RF-75 | `RedemptionPolicy`, CMS `rewards` |
| Reward campaigns | Codici coupon caricati | nuovo | RF-74 | `CouponPool` (SKIP LOCKED) |
| Reward campaigns | Fulfillment tracking, "segna come usato" | parziale → coperto | RF-16, RF-76 | `RedemptionState.USED` |
| POS / Merchants | Anagrafica punti vendita, pannello merchant | nuovo | RF-61, RF-75 | canali in `settings`; postazione operatore nel BFF `/api/operator/*` |
| Admin | Utenti, ruoli e ACL | coperto | RF-43 | SSO OIDC, ruoli nel gateway |
| Admin | Audit log | coperto | RF-41 | CMS versioni |
| Admin | Traduzioni | parziale | RF-79 | it/en su CMS e modelli messaggio |
| Admin | Modelli di messaggio (email/SMS/push) | nuovo | RF-77 | `notifier` `MessageTemplate` |
| Admin | Impostazioni (valuta, fuso, nome punti, scadenza, discesa livello, identificazione, referral, attivazione) | nuovo | RF-79 | CMS `settings` |
| Admin | Marketing automation (webhook) | nuovo | RF-78 | `notifier` `WebhookDispatcher` (HMAC, retry) |
| Admin | Dashboard / analytics | coperto | RF-46 | cruscotto operativo |
| Customer panel | Profilo, punti, storico, catalogo, riscatto, i miei premi, referral, livello | coperto | RF-50..RF-55, RF-72 | `web/site` + BFF `/api/members/*` |
| API | JWT admin/cliente/merchant, Swagger, event API | coperto | RI-05 | OpenAPI 3.1 + AsyncAPI 3 |

## Edizione attuale di Open Loyalty

Dal 17 settembre 2026 (ADR-018) anche l'edizione attuale è un requisito: il dettaglio capacità per capacità è in
`CATALOGO-FUNZIONALE.md` (RF-80..RF-116). Riepilogo:

| Funzionalità | Stato | Nota |
| --- | --- | --- |
| Campagne (trigger, 6 regole × 30 condizioni, effetti, limiti, espressioni, follow-up, automazioni, simulazione) | coperto | RF-80..RF-86, `rules-engine/campaign` |
| Referral multilivello | coperto | RF-85 |
| Wallet configurabili, blocchi, trasferimenti tra membri | coperto | RF-87..RF-89, `ledger` |
| Achievement, challenge, badge, leaderboard con ciclo premiante | coperto | RF-90..RF-94, `engagement-service` |
| Fortune wheel | coperto | RF-95: con premi agganciata agli istanti vincenti (DPR 430) |
| Tier set a condizioni multiple, modalità di discesa, benefici | coperto | RF-105..RF-107 |
| Custom event schemas, custom fields, collections, product catalog | coperto | RF-98..RF-101 |
| Reward flow completo, units conversion coupon, pay with points | coperto | RF-102..RF-104 |
| Segment conditions edizione attuale | coperto | RF-109 (età/località via campi custom) |
| Analytics, export S3, rotazione segreti webhook, notifiche scadenza | parziale | RF-111, RF-113, RF-114 nel backlog |
| SSO admin (Okta, Entra ID) | coperto | OIDC aziendale (RF-43) |
| Multi-tenant | fuori ambito | Un solo programma Iren (RF-20) |
| MCP server | COULD | Esposizione delle API a un assistente aziendale |

## Nuovi requisiti (RF-60..RF-79)

- RF-60 Catalogo di azioni interne del ciclo di vita del membro, generate dal sistema e regolate come le altre: `MEMBER_ENROLLED`, `PROFILE_COMPLETED`, `NEWSLETTER_SUBSCRIBED`, `FIRST_ACTION`, `MEMBERSHIP_ANNIVERSARY`, `SEGMENT_ENTERED`.
- RF-61 Ogni azione porta il canale (`channel`) come attributo canonico; regole, segmenti, premi e cruscotto sono filtrabili per canale. I canali sono un elenco nelle impostazioni.
- RF-62 Un'azione `TRANSACTION` può portare righe (`lines[]`: SKU, nome, categoria, marca, quantità, importo, etichette); `TRANSACTION_RETURNED` è lo storno con riferimento alla chiave originale (RI-08).
- RF-63 Una regola può assegnare punti proporzionali (punti per euro) sull'importo dell'azione o sulla somma delle righe ammesse da un filtro (SKU/etichette/categorie incluse o escluse; costi di consegna esclusi con l'etichetta `delivery`).
- RF-64 Una regola può avere un moltiplicatore di campagna (es. punti doppi in un periodo), cumulabile con il moltiplicatore per tier.
- RF-65 Una regola può essere limitata a tier, segmenti e canali; insiemi vuoti = tutti.
- RF-66 Una regola può fissare usi massimi per membro nel periodo, giorni di sospensione dei punti (spendibili solo dopo la finestra di reso; il saldo mostra "in sospeso") e il flag "ultima regola eseguita".
- RF-67 Check-in geolocalizzato: l'app invia posizione e luogo; l'azione `CHECK_IN` è premiata solo se entro il raggio configurato (`GEO_WITHIN lat,lon,metri`), una volta al giorno per luogo.
- RF-68 Presenta un amico: ogni membro ha un codice stabile e leggibile; il presentato lo inserisce all'adesione; al trigger configurato (adesione, prima azione, prima transazione) nascono due azioni interne premiate dalle regole; tetto annuo di referral per presentatore.
- RF-69 Codici promozionali e QR (QR, PROMO, lotti monouso) con validità, usi totali e per membro; l'uso valido genera `CODE_REDEEMED` con campagna; i lotti si generano ed esportano dal backoffice.
- RF-70 Assegnazione manuale del tier con causale, autore e scadenza; prevale sul calcolo finché valida; alla scadenza si applica la discesa morbida (RF-11); sopra soglia serve l'approvazione a quattro occhi (RF-18).
- RF-71 Segmenti dinamici e statici con criteri su anniversario, numero/valore/media delle azioni, recenza, periodo, SKU/etichette/marche/categorie acquistate, canale e quota per canale, etichette del membro, lista statica, tier, saldo, consensi; ricalcolo notturno e a evento; export CSV di soli id; simulatore; usabili come target di regole, premi, card/pop-up e concorsi.
- RF-72 Adesione e stato del membro: consensi per chiave, etichette libere, canale di adesione, stati ACTIVE / SUSPENDED (matura ma non spende) / CLOSED / ANONYMIZED, import CSV dal backoffice; ogni cambiamento pubblica `MEMBER_V1`.
- RF-73 GDPR: anonimizzazione su richiesta (restano ledger e registro giocate per obbligo di legge, senza dati personali) ed export di portabilità (profilo, saldi, movimenti, riscatti, giocate) in JSON.
- RF-74 Tipi di premio: buono, fisico, servizio, cashback in bolletta, codice sconto percentuale o a valore, servizio gratuito, invito a evento, omaggio, donazione; i codici possono venire da lotti caricati (prelievo con lock, allarme sotto scorta) con validità in giorni.
- RF-75 Un premio ha limiti per membro (totali e giornalieri), finestre di visibilità e di riscatto, segmenti target, categoria, foto e "in evidenza". Postazione operatore (sportello, negozio, call center) con ruoli dedicati: ricerca membro, registrazione azione, uso codice, premio consegnato/usato, tier manuale.
- RF-76 Premi automatici: una regola può assegnare un premio senza punti ("instant reward"), un tier può assegnare premi d'ingresso; il buono ha lo stato `USED` quando l'operatore o il partner lo segnano come utilizzato.
- RF-77 Modelli di messaggio per tipo evento, canale (email, SMS, push, in-app) e lingua, con segnaposto sul payload dell'evento; l'invio passa dai fornitori aziendali, il recapito resta nel CRM.
- RF-78 Webhook sottoscrivibili dal backoffice per tipo evento, firmati HMAC-SHA256, con intestazioni statiche e tentativi con backoff; i falliti sono visibili nel cruscotto.
- RF-79 Impostazioni globali: nome programma, nome dei punti, fuso, lingue (it, en), politica di scadenza punti, modalità di discesa tier, finestra di annullo riscatto, soglia quattro occhi, politica referral, priorità di identificazione, canali. Cruscotto esteso con membri per tier, punti emessi/spesi/in sospeso e scorte dei lotti.

## Cosa è cambiato nello scaffold (17 settembre 2026)

| Componente | Modifica |
| --- | --- |
| `services/common` | `TransactionLine`; tipi azione interni e attributi canonici in `EventTypes`; topic `members`, `segments` |
| `services/rules-engine` | `Rule.Earning` / `Target` / `Limits`; operatori `CONTAINS`, `MATCHES`, `EXISTS`, `GEO_WITHIN`; `SegmentClient`, `RewardClient`; regole seed per spesa, referral, check-in, codice |
| `services/ledger` | punti in sospeso (`available_at`, `Balance.pending`), rilascio e scadenza pianificati (`LedgerJobs`), migrazione V2 |
| `services/tier-service` | `TierOverride` + API override, migrazione V2 |
| `services/segment-service` (nuovo) | `Segment`, `SegmentEvaluator` (18 criteri), ricalcolo pianificato/puntuale, API e CSV |
| `services/member-service` (nuovo) | `Member`, `ReferralPolicy`, `Anonymizer`, adesione/consensi/etichette, anniversari, import, export |
| `services/ingress-adapters` | codici promozionali/QR (`PromoCode`, `/v1/codes`), check-in (`/v1/check-ins`), migrazione V2 |
| `services/catalog-redemption` | `RewardDefinition` (10 tipi), `RedemptionPolicy`, `RedemptionState` (USED), `CouponPool`, `/v1/grants`, transizioni, migrazione V2 |
| `services/notifier` | `MessageTemplate`, `WebhookSubscription` + `WebhookDispatcher`, `MessageSender` |
| `cms` | collezioni `segments`, `promo-codes`, `coupon-pools`, `reward-categories`, `programs` (missioni), `message-templates`, `webhooks`, `settings`; campi nuovi su `rewards`, `point-rules`, `tiers` |
| `web/bff` | area membro (`/api/members/*`: adesione, profilo, referral, movimenti, premi, codici, check-in, riscatto/annullo, export, oblio) e postazione operatore (`/api/operator/*`) |
| `docs/contracts` | OpenAPI: `/v1/codes/redeem`, `/v1/check-ins`, `TransactionLine`; AsyncAPI: canali `members`, `segments` |
| `deploy`, `docker-compose.yml`, `scripts/seed.sh` | due servizi in più, variabili d'ambiente, topic, seed con adesione, referral, transazione con righe, check-in, codice |

Verifica: i domini nuovi compilano e passano lo smoke test standalone (33 asserzioni: regole proporzionali e moltiplicatori,
targeting, limiti, geo, regex, premi automatici, 18 criteri di segmento, referral, politica di riscatto, ciclo di vita
USED, codici, modelli, firma webhook, override tier). I test JUnit corrispondenti sono in `src/test` di ogni modulo.
