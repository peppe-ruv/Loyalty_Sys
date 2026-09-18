# Pubblicare su Vercel · Publishing on Vercel

> 🇮🇹 Documento bilingue. 🇬🇧 Bilingual document.

Tre pezzi del repository stanno su Vercel, e sono tre casi diversi. Vale la pena dire subito quale
parte della piattaforma è davvero lì e quale no.

**EN** — Three parts of the repository live on Vercel, and they are three different cases. It is
worth saying up front which part of the platform is really there and which is not.

| Progetto · Project | Radice · Root | Che cos'è · What it is | Gli serve · It needs |
| --- | --- | --- | --- |
| `loyalty-hub-playground` | `web/playground` | Sito statico: pattern del backoffice e motore decisionale su dati finti | niente · nothing |
| `loyalty-hub-backoffice` | `cms` | **Il backoffice vero**: Payload, 36 collezioni, workflow e versioni | Postgres, `PAYLOAD_SECRET`, Blob |
| `loyalty-hub-portale` | `web/site` | Portale membro. Senza `BFF_URL` è una **vetrina** con dati finti dichiarati | niente in vetrina; il BFF per i dati veri |

**I quattordici servizi Java non stanno su Vercel e non possono starci**: sono applicazioni Spring
Boot con database, Kafka e cache proprie, e vivono su Kubernetes (`deploy/helm/`). Il portale su
Vercel è la punta di quel backend: senza, mostra la vetrina.

**EN** — The fourteen Java services are not on Vercel and cannot be: they are Spring Boot
applications with their own databases, Kafka and cache, and they live on Kubernetes. The portal on
Vercel is the tip of that backend; without it, it shows the showcase.

---

## Backoffice

Radice del progetto `cms`, comando di build da `cms/vercel.json`. La build fa due cose in
quest'ordine: genera la mappa degli import del pannello ed **esegue le migrazioni**, poi costruisce.
Le migrazioni non stanno in `npm run build` di proposito: la CI costruisce il backoffice senza
database, e deve continuare a poterlo fare.

| Variabile · Variable | Obbligatoria | A che serve · What for |
| --- | --- | --- |
| `DATABASE_URI` | sì | Postgres del backoffice. Le migrazioni girano a ogni build e sono idempotenti |
| `PAYLOAD_SECRET` | sì | Firma le sessioni del pannello. Cambiarla invalida i cookie di tutti |
| `CMS_URL` | sì | URL pubblico del pannello, usato negli URL assoluti che Payload genera |
| `BLOB_READ_WRITE_TOKEN` | per gli upload | Manda i file della collezione `media` su Vercel Blob. **Senza, Payload scrive su disco**, che su Vercel è di sola lettura: gli upload falliscono |
| `SUPERSET_*` | no | Cruscotti BI incorporati. Senza, la vista «Andamenti» risponde `SUPERSET_UNAVAILABLE` |
| `DECISION_URL`, `FRAUD_URL` | no | Simulatore di decisioni e di rischio. Senza, quei pulsanti non rispondono |

Le migrazioni vivono in `cms/src/migrations/` e sono generate, non scritte a mano. Dopo aver
cambiato una collezione:

```sh
cd cms && DATABASE_URI=postgres://… npx payload migrate:create <nome>
```

Attenzione ai nomi lunghi: Postgres tronca in silenzio tutto ciò che supera i 63 caratteri, e un
indice troncato lascia il database disallineato dallo schema di Payload. Due campi hanno già dovuto
dichiarare un `dbName` corto per questo. Se `migrate:create` si rifiuta di partire citando un
identificatore troppo lungo, la soluzione è `dbName`/`enumName` sul campo, non accorciare il nome
che vede l'operatore.

**EN** — Migrations are generated, not hand-written. Watch identifier length: Postgres silently
truncates anything past 63 characters, and a truncated index leaves the database out of sync with
the Payload schema. Shorten with `dbName`/`enumName` on the field, never the operator-facing name.

---

## Portale · Portal

Radice `web/site`. Una sola variabile decide tutto:

- **`BFF_URL` non impostata** → vetrina. Saldo, offerta e messaggi vengono da `lib/demo.mjs`: dati
  inventati, nessun dato personale, membro come identificatore opaco, e un avviso in pagina che lo
  dice a chi guarda.
- **`BFF_URL` impostata** → il portale chiama il BFF vero. Ogni chiamata degrada per conto proprio
  (niente saldo, niente offerta, inbox vuota) e ha una scadenza di due secondi: una pagina che non
  esce è peggio di una pagina senza saldo.

I dati finti rispettano i contratti del BFF. Se il BFF cambia risposta vanno aggiornati o tolti: un
finto che mente è peggio di nessun finto.

**EN** — One variable decides everything. Without `BFF_URL` the portal is a showcase on fake data,
declared as such on the page; with it, the portal calls the real BFF and every call degrades on its
own, with a two-second deadline.

---

## Pubblicare · Publishing

Ogni progetto ha il suo `vercel.json` con framework, comando di installazione, comando di build e
intestazioni di sicurezza. La radice del progetto si imposta su Vercel, non nel file, ed è l'unico
pezzo che va messo a mano la prima volta.

Il playground si pubblica anche dalla CI: `.github/workflows/playground.yml` costruisce e carica a
ogni push su `main` che lo tocca, se esiste il segreto `VERCEL_TOKEN`; senza, il job si salta da
solo invece di fallire.

**EN** — Each project carries its own `vercel.json`; the root directory is set on Vercel and is the
only thing to do by hand the first time. The playground also publishes from CI when the
`VERCEL_TOKEN` secret exists, and skips itself when it does not.
