# Runbook

> 🇮🇹 Procedure operative: cosa fare, in che ordine, con quali comandi. Ogni alert della piattaforma cita il
> runbook corrispondente; un alert senza runbook non si pubblica.
> 🇬🇧 Operational procedures: what to do, in which order, with which commands. Every platform alert references
> its runbook; an alert without a runbook does not ship.

| Runbook | Contenuto · Content |
| --- | --- |
| [`observability.md`](observability.md) | Alert su SLO, diagnosi, mitigazioni, manutenzione dei cruscotti, escalation · SLO alerts, diagnosis, mitigations, dashboard maintenance, escalation |

---

## Procedure generali · General procedures

### 1. Riprocessare la coda di scarto · Reprocess the dead-letter queue

**Quando · When** — La coda di scarto supera la soglia · the DLQ exceeds its threshold.

1. Leggere il motivo di scarto dagli eventi in coda (schema non rispettato, membro non risolvibile, istante non
   valido) · read the rejection reason from the queued events.
2. Correggere la fonte o lo schema del tipo azione nel backoffice · fix the source or the action-type schema in the
   back office.
3. Ripubblicare gli eventi corretti **con la stessa chiave di idempotenza**: nessun doppio accredito · republish the
   corrected events **with the same idempotency key**: no duplicate credit.

### 2. Ritardo nell'elaborazione delle azioni · Action processing lag

**Quando · When** — Il lag del consumo cresce durante un lancio · consumer lag grows during a launch.

1. Aumentare le repliche del consumatore interessato, mai oltre il numero di partizioni del topic · scale the affected
   consumer, never beyond the topic partition count.
2. Verificare la latenza del registro: se è il collo di bottiglia, scalare il registro e controllare le connessioni al
   database · check ledger latency; if it is the bottleneck, scale the ledger and inspect database connections.
3. L'interfaccia mostra l'istante di aggiornamento: nessuna azione richiesta all'utente · the UI shows freshness; no
   user action required.

### 3. Interruttore di emergenza su giocate e riscatti · Kill switch for plays and redemptions

**Quando · When** — Errori sulle giocate o incidente in corso · play errors or an ongoing incident.

1. Portare a zero le repliche del servizio dei concorsi · scale the contest service to zero replicas.
2. Il BFF risponde con indisponibilità e il sito disabilita i pulsanti · the BFF answers unavailable and the site
   disables the buttons.
3. Comunicare a marketing e al referente legale prima di riattivare · notify marketing and the legal owner before
   re-enabling.

### 4. Ripristino in seconda region · Secondary-region restore

**Quando · When** — Perdita della region primaria · loss of the primary region.

1. Ripristinare il database dal backup continuo (obiettivo di perdita dati 15 minuti) · restore the database from the
   continuous backup (15-minute recovery point objective).
2. Applicare l'infrastruttura nella region di ripristino · apply the infrastructure in the recovery region.
3. Installare i servizi · install the services.
4. Aggiornare il DNS · update DNS.
   Obiettivo di ripristino 4 ore; prova trimestrale · 4-hour recovery time objective; quarterly drill.

### 5. Chiusura di un concorso ed export per la verifica esterna · Contest close and external-verification export

1. Portare il concorso allo stato di chiusura dal backoffice · move the contest to closed in the back office.
2. Esportare registro giocate, istanti vincenti e premi non assegnati · export the play log, winning moments and
   unclaimed prizes.
3. Verificare la catena di hash del registro con lo script di controllo · verify the log hash chain with the checking
   script.
4. Gestire i premi non assegnati secondo il regolamento depositato, alla presenza del soggetto previsto · handle
   unclaimed prizes per the filed rules, with the required witness present.

### 6. Batch di fine anno di programma · Program year-end batch

1. Provare in ambiente di staging almeno 30 giorni prima, con i dati di produzione anonimizzati · rehearse in staging
   at least 30 days ahead, with anonymised production data.
2. Eseguire: verifica dei livelli, scadenza delle unità, chiusura delle missioni · run tier review, unit expiry,
   mission closure.
3. Produrre il report di riepilogo per le funzioni di controllo · produce the summary report for control functions.
