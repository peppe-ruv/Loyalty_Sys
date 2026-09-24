# Ricevitore d'esempio dei webhook

Esempio minimo, **senza dipendenze** (solo Node ≥ 18), per ricevere i webhook di Loyalty Hub e verificarne la firma
(F-WBH-01, BO-23; regole in `docs/servizi/engagement-service.md §5`).

## Cosa arriva

Ogni consegna è una `POST` con:

| Elemento | Contenuto |
|---|---|
| corpo | il CloudEvent originale del fatto (JSON strutturato, `application/cloudevents+json`) |
| `X-LH-Signature` | `sha256=<HMAC-SHA256(secret, corpo)>` in esadecimale minuscolo |
| `X-LH-Event-Id` | `id` del CloudEvent: usalo per scartare i doppioni (un ritento rimanda lo stesso evento) |
| `X-LH-Delivery-Id` | id della consegna nel registro di BO-23 |

Una risposta **2xx** chiude la consegna (`OK`). Qualsiasi altra risposta, un redirect o nessuna risposta entro **5 s**
è un fallimento: si ritenta dopo 1, 5 e 15 minuti, poi la consegna diventa `GAVE_UP` e si può solo *Riprovare* a mano
da BO-23. `message.delivered` non viene mai consegnato.

Il **segreto** (`whsec_…`) si vede una sola volta, alla creazione del webhook in BO-23: copialo subito.

## Verificare una firma

La firma si calcola sui **byte grezzi** del corpo, così come sono arrivati: non riserializzare il JSON (spazi e ordine
delle chiavi cambierebbero l'HMAC). Il confronto va fatto a tempo costante.

```js
import { verifySignature } from "./verify.mjs";
if (!verifySignature(process.env.LH_WEBHOOK_SECRET, rawBody, req.headers["x-lh-signature"])) {
  // 401: non viene da Loyalty Hub (o il corpo è stato alterato)
}
```

Da riga di comando (per esempio col corpo copiato dal dettaglio della consegna in BO-23):

```bash
node deploy/webhook-receiver/verify.mjs --secret whsec_... --signature sha256=... --file corpo.json
# oppure: cat corpo.json | node deploy/webhook-receiver/verify.mjs --secret whsec_... --signature sha256=...
```

Esce con `0` se la firma è valida, `1` altrimenti.

## Provare in locale

1. Avvia engagement col profilo `local` (in `local` è ammesso `http://localhost`; negli altri profili solo `https://`
   verso indirizzi pubblici, per evitare SSRF).
2. In BO-23 crea un webhook verso `http://localhost:4000/hook`, copia il segreto.
3. Avvia il ricevitore:
   ```bash
   LH_WEBHOOK_SECRET=whsec_... node deploy/webhook-receiver/receiver.mjs
   ```
4. *Invia evento di prova* da BO-23: nel terminale compare `✓ firma valida`.
5. Per vedere i ritenti: `FAIL_WITH=500 LH_WEBHOOK_SECRET=... node deploy/webhook-receiver/receiver.mjs`. In BO-23 la
   consegna passa a `FAILED` con il prossimo tentativo pianificato; dalla Console demo (BO-30) o con
   `POST /v1/demo/jobs/deliver-webhooks?asOf=<istante>` si anticipa il tempo fino a `GAVE_UP`.

## Test

```bash
node --test deploy/webhook-receiver/verify.test.mjs
```

Il vettore di `fixtures/firma-esempio.json` è verificato anche in Java da `WebhookSignatureTest` (engagement-service), e
`WebhookIT` controlla con lo stesso calcolo (e, se `node` è disponibile, con `verify.mjs`) la firma di una consegna
prodotta davvero dal servizio.
