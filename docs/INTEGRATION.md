# Guida all'integrazione · Integration guide

> 🇮🇹 Come far parlare un sistema esterno con Loyalty Hub: inviare azioni, leggere saldi, ricevere eventi,
> gestire errori e duplicati. Con esempi eseguibili contro l'ambiente locale.
> 🇬🇧 How to make an external system talk to Loyalty Hub: send actions, read balances, receive events, handle
> errors and duplicates. With runnable examples against the local environment.

---

## 1. Le quattro porte di ingresso · The four inbound doors

| Porta · Door | Quando usarla · When to use it | Contratto · Contract |
| --- | --- | --- |
| **REST sincrono · Synchronous REST** | Azioni a evento singolo da portale, app, POS, partner · single-event actions from portal, app, POS, partners | `POST /v1/actions` |
| **Batch REST** | Carichi periodici fino a poche migliaia di azioni · periodic loads up to a few thousand actions | `POST /v1/actions/batch` |
| **Kafka** | Sistemi già event-driven, alti volumi · already event-driven systems, high volumes | Topic delle azioni con busta canonica · actions topic with the canonical envelope |
| **File (storage/SFTP)** | Estrazioni notturne da sistemi legacy · nightly extracts from legacy systems | CSV/JSON su prefisso concordato · CSV/JSON on an agreed prefix |

Tutte e quattro producono lo stesso evento canonico: cambiare trasporto non cambia la semantica.
**EN** — All four produce the same canonical event: changing transport never changes semantics.

---

## 2. Inviare un'azione premiante · Sending a rewarding action

```sh
curl -X POST http://localhost:8081/v1/actions \
  -H 'content-type: application/json' \
  -H 'authorization: Bearer <token>' \
  -d '{
    "memberId": "9f2c4a…",
    "actionType": "TRANSACTION",
    "idempotencyKey": "billing-2026-000123",
    "externalRef": "INV-2026-000123",
    "occurredAt": "2026-01-15T09:58:11Z",
    "attributes": {
      "amount": 84.50,
      "currency": "EUR",
      "channel": "web",
      "paymentMethod": "card"
    },
    "lines": [
      { "sku": "SRV-01", "name": "Servizio base", "category": "service", "quantity": 1, "amount": 79.00 },
      { "sku": "DEL-01", "name": "Consegna", "category": "delivery", "quantity": 1, "amount": 5.50, "labels": ["delivery"] }
    ]
  }'
```

| Campo · Field | Obbligatorio · Required | Note |
| --- | --- | --- |
| `memberId` | sì · yes (o un identificatore risolvibile · or a resolvable identifier) | Identificatore opaco del membro · opaque member identifier |
| `actionType` | sì · yes | `UPPER_SNAKE`, dichiarato in uno schema · declared in a schema |
| `idempotencyKey` | sì · yes | Stabile e ripetibile dalla fonte · stable and repeatable from the source |
| `externalRef` | no | Riferimento leggibile per l'assistenza · human-readable reference for support |
| `occurredAt` | sì · yes | Istante reale dell'azione, non dell'invio · when the action happened, not when it was sent |
| `attributes` | no | Validati contro lo schema del tipo · validated against the type schema |
| `lines` | no | Righe per regole e segmenti su prodotto · line items for product-based rules and segments |
| `reversalOf` | no | Chiave dell'azione da stornare · key of the action being reversed |

Risposte · Responses:

| Codice · Code | Significato · Meaning | Cosa fare · What to do |
| --- | --- | --- |
| `202 Accepted` | Presa in carico, elaborazione asincrona · accepted, asynchronous processing | Nulla · nothing |
| `200 OK` (replay) | Chiave già vista: stessa risposta · key already seen: same response | Nulla: è il comportamento atteso · nothing: expected behaviour |
| `400` | Payload o schema non validi · invalid payload or schema | Correggere la fonte; il motivo è nel corpo · fix the source; reason in the body |
| `401` / `403` | Token assente o ambito insufficiente · missing token or insufficient scope | Verificare le credenziali della fonte · check source credentials |
| `429` | Quota della fonte superata · source quota exceeded | Backoff esponenziale, rispettare `Retry-After` · exponential backoff, honour `Retry-After` |
| `503` | Dipendenza indisponibile · dependency unavailable | Riprovare con la stessa chiave · retry with the same key |

Regola d'oro: **riprovare sempre con la stessa chiave di idempotenza**. Un doppio invio non produce doppi
punti; un invio con chiave nuova sì.
**EN** — Golden rule: **always retry with the same idempotency key**. A duplicate delivery never yields duplicate
points; a delivery with a fresh key does.

---

## 3. Storni e resi · Reversals and returns

```sh
curl -X POST http://localhost:8081/v1/actions -H 'content-type: application/json' -d '{
  "memberId": "9f2c4a…",
  "actionType": "TRANSACTION_RETURNED",
  "idempotencyKey": "billing-2026-000123-return",
  "reversalOf": "billing-2026-000123",
  "occurredAt": "2026-01-20T11:00:00Z",
  "attributes": { "amount": 84.50, "channel": "web" }
}'
```

Lo storno non cancella nulla: genera un movimento inverso, restituisce il budget consumato dalle campagne e
lascia la traccia dell'originale. **EN** — A reversal deletes nothing: it creates an inverse movement, returns the
campaign budget consumed and preserves the original trace.

---

## 4. Leggere lo stato · Reading state

| Cosa · What | Chiamata · Call |
| --- | --- |
| Wallet e saldi · Wallets and balances | `GET /v1/ledger/members/{memberId}/wallets` |
| Movimenti (paginati) · Movements (paginated) | `GET /v1/ledger/members/{memberId}/movements?after=<cursor>&size=50` |
| Tier e progresso · Tier and progress | `GET /v1/tiers/members/{memberId}/progress` |
| Customer 360 | `GET /v1/context/{memberId}` |
| Prossima azione consigliata · Next Best Action | `POST /v1/decisions/next-best-action/{memberId}` |
| Perché è stata decisa · Why it was decided | `GET /v1/decisions/{decisionId}` |
| Catalogo premi visibile · Visible reward catalogue | `GET /v1/rewards?memberId=…` |
| Appartenenza a un segmento · Segment membership | `GET /v1/segments/{segmentId}/members/{memberId}` |

Le letture eventualmente consistenti riportano l'istante di aggiornamento: mostralo all'utente invece di
fingere tempo reale. **EN** — Eventually consistent reads carry a freshness timestamp: show it to the user instead of
pretending real time.

---

## 5. Ricevere eventi · Receiving events

### 5.1 Webhook

Si sottoscrive un tipo di evento dal backoffice indicando URL, intestazioni statiche e segreto. Ogni consegna
porta la firma `HMAC-SHA256` del corpo.
**EN** — Subscribe to an event type from the back office with URL, static headers and a secret. Every delivery carries
an `HMAC-SHA256` signature of the body.

```
POST /your/endpoint
x-loyalty-event: io.loyaltyhub.tier.v1
x-loyalty-delivery: 7b1e…
x-loyalty-signature: sha256=<hex>
x-loyalty-timestamp: 1768471091

{ "specversion": "1.0", "type": "io.loyaltyhub.tier.v1", … }
```

Verifica · Verification (Node):

```js
import { createHmac, timingSafeEqual } from "node:crypto";

function verify(rawBody, header, secret) {
  const expected = "sha256=" + createHmac("sha256", secret).update(rawBody).digest("hex");
  const a = Buffer.from(expected), b = Buffer.from(header);
  return a.length === b.length && timingSafeEqual(a, b);
}
```

| Regola · Rule | Dettaglio · Detail |
| --- | --- |
| Rispondere presto · Respond fast | `2xx` entro pochi secondi; elaborare in asincrono · `2xx` within seconds; process asynchronously |
| Idempotenza · Idempotency | Deduplicare su `x-loyalty-delivery` e su `id` dell'evento · deduplicate on `x-loyalty-delivery` and the event `id` |
| Tentativi · Retries | Backoff esponenziale lato piattaforma; i falliti restano visibili nel backoffice · platform-side exponential backoff; failures visible in the back office |
| Rotazione del segreto · Secret rotation | Accettare due segreti durante la finestra di rotazione · accept two secrets during the rotation window |

### 5.2 Kafka

Per volumi alti, consumare direttamente i topic di dominio: chiave di partizione `memberId`, busta
CloudEvents, tipi versionati. Un consumatore esterno deve essere idempotente e tollerare il replay.
**EN** — For high volumes, consume the domain topics directly: `memberId` partition key, CloudEvents envelope,
versioned types. An external consumer must be idempotent and tolerate replay.

---

## 6. Identità · Identity

Se la fonte non conosce l'identificatore del membro, invia gli identificatori che possiede e li fa risolvere.
**EN** — If the source does not know the member identifier, it sends the identifiers it has and asks for resolution.

```sh
curl -X POST http://localhost:8087/v1/identities/resolve -H 'content-type: application/json' -d '{
  "identifiers": [
    { "kind": "email_hash", "value": "b1946ac9…" },
    { "kind": "pos_card",   "value": "7781-0042" }
  ]
}'
```

| Tipo · Kind | Note |
| --- | --- |
| `oidc_sub` | Identificatore dal provider di identità: sempre deterministico · identity provider subject: always deterministic |
| `crm_id`, `erp_id` | Chiavi dei sistemi aziendali · corporate system keys |
| `email_hash`, `phone_hash` | Solo in hash: mai in chiaro · hashed only: never in clear text |
| `device_id`, `app_id` | Dispositivo e installazione, per omnicanalità · device and installation, for omnichannel |
| `pos_card`, `ecommerce_id` | Tessera e account di negozio · loyalty card and store account |

---

## 7. Autenticazione e ambiti · Authentication and scopes

| Chiamante · Caller | Credenziale · Credential | Ambito · Scope |
| --- | --- | --- |
| Fonte di integrazione · Integration source | Chiave API o client credentials · API key or client credentials | Scrittura di azioni, lettura del proprio stato · write actions, read own state |
| Membro (via BFF) · Member (via BFF) | Token OIDC del provider di identità · OIDC token | Solo i propri dati · own data only |
| Operatore · Operator | Token OIDC + ruolo · OIDC token + role | Rotte operatore · operator routes |
| Backoffice · Back office | SSO aziendale · corporate SSO | Permessi per collezione e campo · per-collection and per-field permissions |

Nessuna credenziale nel repository: in cluster i segreti arrivano dal gestore segreti del cloud, in locale dal
file di Compose. **EN** — No credentials in the repository: in-cluster secrets come from the cloud secret manager,
locally from the Compose file.

---

## 8. Limiti e contratti operativi · Limits and operational contracts

| Contratto · Contract | Valore di riferimento · Reference value |
| --- | --- |
| Latenza di ingestione p95 · Ingestion p95 latency | < 200 ms |
| Latenza giocata e riscatto p95 · Play and redemption p95 | < 500 ms |
| Freschezza dei saldi · Balance freshness | Secondi (lag del consumo monitorato) · seconds (monitored consumer lag) |
| Freschezza del Customer 360 | Secondi · seconds |
| Freschezza dei segmenti · Segment freshness | Minuti/ore secondo il criterio · minutes/hours depending on the criterion |
| Dimensione massima di un batch · Max batch size | Alcune migliaia di azioni per chiamata · a few thousand actions per call |
| Quote per fonte · Per-source quotas | Configurabili all'ingresso · configurable at the edge |

---

## 9. Lista di controllo per una nuova integrazione · New integration checklist

- [ ] Tipi di azione dichiarati in uno schema, con attributi obbligatori · action types declared in a schema with required attributes
- [ ] Chiave di idempotenza stabile e ripetibile dalla fonte · stable, repeatable idempotency key from the source
- [ ] Strategia di storno concordata (chiave dell'originale) · agreed reversal strategy (original key)
- [ ] Mappatura dell'identità decisa (identificatore o risoluzione) · identity mapping decided (identifier or resolution)
- [ ] Gestione di `429` e `503` con backoff e stessa chiave · `429`/`503` handling with backoff and same key
- [ ] Nessun dato personale negli attributi (solo id, hash, valori tecnici) · no personal data in attributes
- [ ] Test end-to-end in ambiente locale con `make up` · end-to-end test locally with `make up`
- [ ] Prova di carico se la fonte genera picchi · load test if the source produces spikes
- [ ] Sottoscrizione webhook o consumo Kafka per il ritorno · webhook subscription or Kafka consumption for feedback
- [ ] Contatto tecnico e canale di allerta concordati · technical contact and alerting channel agreed

Contratti formali · Formal contracts: [`contracts/`](contracts/) (OpenAPI, AsyncAPI).
