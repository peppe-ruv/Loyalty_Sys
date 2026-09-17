# Runbook — osservabilità e BI · observability and BI

> 🇮🇹 Dove guardare, cosa significa un alert, cosa fare. Le mitigazioni sono ordinate dalla più rapida alla più
> invasiva. **EN** — Where to look, what an alert means, what to do. Mitigations are ordered from fastest to most
> invasive.

---

## 1. Dove guardare · Where to look

| Cosa · What | Dove · Where |
| --- | --- |
| Metriche e SLO · Metrics and SLOs | Grafana → cruscotti Piattaforma (SLO), Programma, Concorsi |
| Log | Grafana → Explore → Loki, filtro per namespace e servizio; dal trace id si salta alla traccia · filter by namespace and service; jump from trace id to the trace |
| Tracce · Traces | Grafana → Explore → Tempo, query per servizio e stato di errore · query by service and error status |
| Alert | Alertmanager e sezione di allerta di Grafana · Alertmanager and Grafana alerting |
| Andamenti · Trends | Backoffice → Andamenti (BI incorporata); accesso diretto alla BI per gli analisti · back office → Trends (embedded BI); direct BI access for analysts |

---

## 2. Alert e cosa fare · Alerts and what to do

### Ingestione: consumo del budget di errore o latenza alta · Ingestion: error-budget burn or high latency

1. Cruscotto Piattaforma: errori per servizio e latenza p95. Se riguarda solo l'ingestione, guardare il database delle
   chiavi viste e gli errori del produttore Kafka · if limited to ingestion, check the seen-keys database and Kafka
   producer errors.
2. Log del servizio di ingestione filtrati sugli errori · ingestion service logs filtered on errors.
3. Mitigazione: scalare l'ingestione; se Kafka è lento, aumentare il batching del produttore; se il database è lento,
   verificare il pool di connessioni · mitigation: scale ingestion; increase producer batching if Kafka is slow; check
   the connection pool if the database is slow.
4. Le fonti ricevono comunque presa in carico: gli eventi non elaborabili restano in coda di scarto e si riprocessano ·
   sources still get an accepted response: unprocessable events stay in the DLQ and are reprocessed.

### Ritardo dei saldi · Balance lag

1. Guardare il lag massimo per gruppo di consumo: motore regole lento → latenza verso registro, livelli e segmenti
   (tracce); registro lento → database · check max lag per consumer group.
2. Aumentare la concorrenza del consumatore o le repliche (≤ partizioni), oppure il database · raise consumer
   concurrency or replicas (≤ partitions), or the database.
3. Nessuna azione utente: l'interfaccia dichiara la freschezza · no user action: the UI states freshness.

### Outbox bloccata · Stuck outbox

1. La metrica delle righe non pubblicate cresce: il relay è fermo o Kafka non è raggiungibile · the unpublished-rows
   metric grows: the relay is down or Kafka is unreachable.
2. Riavviare il pod del registro e verificare permessi e connettività verso Kafka · restart the ledger pod and check
   Kafka permissions and connectivity.
3. Nessuna perdita: l'outbox è transazionale · no loss: the outbox is transactional.

### Concorsi: latenza di giocata, nessuna giocata, premi residui bassi · Contests: play latency, no plays, low prize stock

1. **Non modificare mai** la configurazione di un concorso avviato. La latenza di giocata è attesa ai lanci per la
   contesa sul lock: oltre soglia attivare la sala d'attesa virtuale · **never change** a running contest's
   configuration. Play latency is expected at launch due to lock contention: enable the virtual waiting room above
   threshold.
2. Nessuna giocata: verificare sito, autenticazione e distribuzione dei contenuti; avvisare marketing · no plays:
   check site, authentication and content delivery; notify marketing.
3. Premi residui bassi: informare marketing; la gestione dei premi non assegnati avviene a fine concorso · low prize
   stock: notify marketing; unclaimed prizes are handled at contest close.

### Scarti in ingresso o azioni senza campagna · Ingestion rejects or actions without a campaign

1. Backoffice → coda di scarto: leggere il motivo (schema, membro non risolvibile, istante). Correggere schema o fonte
   e riprocessare · read the reason, fix schema or source, reprocess.
2. Azioni senza campagna: pubblicare la campagna o il tipo azione mancante; gli eventi restano nel topic e si
   rielaborano · publish the missing campaign or action type; events remain in the topic and can be replayed.

### Scorte di codici basse o webhook in errore · Low code stock or failing webhooks

Caricare un nuovo lotto di codici; verificare endpoint e segreto del partner. I tentativi riprendono da soli · upload
a new code pool; check the partner endpoint and secret. Retries resume automatically.

### Decisioni: effetti falliti, decisioni ferme, scarti in massa · Decisions: failed effects, stalled decisions, mass rejections

1. **Effetti falliti** — la metrica degli effetti con esito negativo indica il servizio di destinazione. La decisione è
   nel decision log e gli effetti sono idempotenti per chiave: si possono ripetere · the failed-effect metric names the
   target service. The decision is in the decision log and effects are idempotent by key: they can be replayed.
2. **Decisioni ferme** — lag del consumatore del servizio decisionale oppure valutazione lenta nel motore regole
   (seguire la correlazione nelle tracce). Ripiego: attivare la modalità storica con i due flag, senza perdita ·
   consumer lag or slow rules evaluation (follow correlation in traces). Fallback: switch to legacy mode with the two
   flags, no loss.
3. **Scarti in massa** — non è un guasto: è una policy troppo stretta. Marketing rivede cap di contatto, ore di
   silenzio e consensi richiesti nel backoffice; effetto entro un minuto, senza rilascio · not a failure: the policy is
   too tight. Marketing revises contact caps, quiet hours and required consents in the back office; effective within a
   minute, no release.

### Provider di previsione in errore · Prediction provider failing

Il provider esterno risponde in errore o oltre il timeout: il composito ripiega sul provider a regole e nessuna
decisione si blocca. Verificare endpoint, credenziale e latenza; se l'errore persiste, disattivare il provider dal
backoffice · the composite falls back to the rule-based provider and no decision blocks. Check endpoint, credential and
latency; if it persists, disable the provider from the back office.

### Rischio critico in aumento · Surge of critical risk

1. Elenco dei membri a rischio più alto e console operatore: quali segnali dominano · top-risk members and operator
   console: which signals dominate.
2. Abuso reale: mantenere il blocco automatico e aprire l'incidente · genuine abuse: keep the automatic block and open
   an incident.
3. Falsi positivi (per esempio coordinate errate da una fonte): alzare le soglie o disattivare il segnale dal
   backoffice; i membri vengono sbloccati alla rivalutazione periodica o su richiesta · false positives: raise
   thresholds or disable the signal from the back office; members are unblocked at the next periodic reassessment or
   on demand.

### Consegne in errore · Delivery failures

Canale in errore (fornitore o webhook esterno): l'instradamento permette di spostare le consegne su un altro canale
senza rilascio. Le consegne fallite restano nel registro e **non** vengono ripetute automaticamente, per evitare doppi
contatti · routing allows moving deliveries to another channel without a release. Failed deliveries stay in the log and
are **not** auto-retried, to avoid double contacts.

---

## 3. Manutenzione · Maintenance

| Attività · Task | Come · How |
| --- | --- |
| Cambiare una retention · Change a retention | Values dell'osservabilità e infrastruttura come codice: mai a mano sul cluster · observability values and infrastructure as code: never by hand on the cluster |
| Nuovo cruscotto tecnico · New technical dashboard | Esportare il JSON nel repository e includerlo nell'applicazione automatica · export the JSON into the repository and include it in the automatic apply |
| Nuovo cruscotto di BI · New BI dashboard | Esportare dalla BI nel repository e registrare l'identificativo nell'endpoint del token ospite · export from BI into the repository and register the id in the guest-token endpoint |
| Nuova metrica di business · New business metric | Metodo nella facciata delle metriche, etichette a bassa cardinalità, mai identificatori personali · method in the metrics facade, low-cardinality labels, never personal identifiers |
| Backup del warehouse · Warehouse backup | Backup notturno verso storage a oggetti; ripristino con il comando dedicato · nightly object-storage backup; restore with the dedicated command |
| Prova di carico prima di un concorso · Pre-contest load test | Confronto con i cruscotti SLO; silenziare gli alert solo per la finestra del test · compare against SLO dashboards; silence alerts only for the test window |

---

## 4. Escalation

Intervento immediato → turno di reperibilità della piattaforma (15 minuti) → responsabile tecnico
(30 minuti). Gli alert marcati come attinenti ai concorsi vanno anche al referente legale. I ticket entrano nel backlog
della piattaforma entro il giorno lavorativo successivo.
**EN** — Page → platform on-call (15 minutes) → engineering lead (30 minutes). Alerts flagged as contest-related also
go to the legal owner. Tickets enter the platform backlog by the next business day.
