# CLAUDE.md — guida per lavorare su Loyalty Hub

Questo file è letto da Claude Code (e da chiunque apra il repository) prima di toccare il codice. Spiega cos'è il
progetto, come è fatto, come si costruisce e si verifica, quali regole non si rompono e come si estende. Le sezioni
"Regole" e "Come estendere" sono normative: un contributo che le viola va rifatto.

## 1. Cos'è

Loyalty Hub è la piattaforma loyalty **vendor neutral** di Iren (D01, ADR-001): riceve azioni premianti da qualunque
sistema (Salesforce, SAP via middleware, IrenYou/app, partner, file), le trasforma in punti e tier, sblocca fasce di
premi, gestisce concorsi instant win conformi al DPR 430/2001 e, dalla 0.5.0, decide *cosa proporre a chi* con un
motore decisionale configurabile dal backoffice (Loyalty 4.0). Il perimetro funzionale è "almeno Open Loyalty"
(edizione open source e attuale: RF-60..RF-116) più osservabilità e BI enterprise (RF-117..RF-124) e il livello
decisionale (RF-125..RF-136). Versione corrente: **0.7.0** (il codice 0.5.0 e il bundle UX 0.6.0 sono confluiti in
un'unica numerazione).

Lingua: codice in inglese (identificatori), **commenti, javadoc, documenti, messaggi di commit in italiano**. Ogni
requisito ha un codice `RF-nn` (funzionale), `RI-nn` (integrazione), `RC`/`RT`, e ogni scelta un `ADR-nnn` in
`docs/adr/` (= decisione `Dnn` nel Registro decisioni). I commenti citano il requisito che implementano.

## 2. Mappa del repository

| Cartella | Cosa contiene |
| --- | --- |
| `services/` | Maven multi-modulo Java 21 / Spring Boot 3.5: `common` + 14 servizi di dominio (vedi §3) |
| `cms/` | Backoffice Payload CMS (TypeScript): `src/collections.ts` (contenuti e configurazione del programma), `src/collections-decisions.ts` (Loyalty 4.0), `src/endpoints/` (guest token BI, simulazioni), `src/views/` (Andamenti) |
| `web/bff/` | Backend for frontend Node (un solo file, `src/server.js`): area membro, console operatore, NBA, inbox, consensi, identità |
| `web/site/` | Sito Next.js (app router) — oggi una pagina di esempio con saldo, NBA e inbox |
| `web/backoffice-design-system/` | Pacchetto `@loyalty-hub/backoffice-design-system` (ADR-026, ADR-027): contratti dei 15 pattern UI, regole eseguibili (workflow D14, frasi senza id tecnici, formati KPI, contrasto WCAG) e token `--lh-*` sulle primitive Design Tokens Italia |
| `deploy/terraform/` | AWS eu-south-1: VPC, EKS, RDS, MSK, ElastiCache, S3, Secrets Manager, osservabilità |
| `deploy/helm/loyalty-hub/` | Umbrella chart: un template generico genera Deployment/Service/HPA/PDB per ogni voce di `values.yaml → services` |
| `deploy/observability/`, `deploy/bi/` | kube-prometheus-stack/Thanos/Loki/Tempo/OTel values, regole di alert, dashboard Grafana; ClickHouse operator e Superset |
| `analytics/clickhouse/schema/` | Kafka engine → fatti → viste KPI (file numerati, applicati in ordine) |
| `analytics/superset/` | Config, init idempotente, export del cruscotto |
| `docs/` | `SPECIFICA.md` (puntatore al documento vivo), `PARITA-OPEN-LOYALTY.md`, `CATALOGO-FUNZIONALE.md`, `OSSERVABILITA-BI.md`, `COPERTURA-LOYALTY-4.0.md`, `LOYALTY-4.0.md`, `LINEE-GUIDA-UX-BACKOFFICE.md` (LG-01..LG-48) e `SPECIFICA-ADDENDUM-UX.md` (RF-137..RF-142), `adr/ADR-001..026`, `contracts/` (OpenAPI ingresso, AsyncAPI), `runbooks/` |
| `docker-compose.yml`, `Makefile`, `scripts/seed.sh` | Ambiente locale con la stessa topologia della produzione, comandi, dati di esempio |
| `.github/workflows/` | `ci.yml` (Java, web/CMS, Helm/Terraform lint, osservabilità), `release.yml` (immagini per servizio) |

## 3. Servizi e responsabilità

Ogni servizio Java ha: `pom.xml`, `Dockerfile` (multi-stage a layer), `src/main/resources/application.yml` (porta,
schema Flyway `<nome>service`, Kafka, actuator, tracing OTLP, log ECS), `db/migration/V*.sql`, package
`it.iren.loyalty.<nomeservizio>` con `domain/` (logica pura, senza Spring), `app/` (servizi applicativi, store JDBC),
`api/` (controller REST `/v1/...`), `messaging/` (consumer Kafka), `<Nome>Config.java` (bean e adattatori HTTP con
`@ConditionalOnMissingBean`, sostituibili nei test). Un servizio ha un solo database logico (schema Postgres proprio)
e **non legge mai le tabelle di un altro servizio**: parla via eventi Kafka o API.

| Servizio | Porta | Schema | Ruolo | Requisiti chiave |
| --- | --- | --- | --- | --- |
| `common` | — | — | Evento canonico CloudEvents (`CanonicalEvents`, `RewardingAction`, `EventTypes`), `Currency`, metriche `LoyaltyMetrics` (auto-configurate), ponte log OTLP | RI-01, RF-117 |
| `ingress-adapters` | 8081 | ingress | REST/Kafka/file → evento canonico; idempotenza (`seen_keys`), validazione schema evento, catalogo prodotti, codici promo, rate limiting, DLQ | RI-01..RI-04, RF-98, RF-69 |
| `rules-engine` | 8082 | rulesengine | Campagne (WHEN/IF/THEN con SpEL `#fn.*`), referral, automazioni, simulatore; `POST /v1/evaluations` valuta senza effetti | RF-80..RF-86 |
| `ledger` | 8083 | ledger | Registro movimenti append-only (trigger DB), wallet configurabili, pending/lock/scadenze, blocco, trasferimenti, outbox | RF-87..RF-89, RI-08 |
| `tier-service` | 8084 | tierservice | Tier annuali (doppia valuta STATUS/PREMIO), tier set, override, `TierUpdater` dai movimenti | RF-10..RF-13, RF-105..RF-107 |
| `catalog-redemption` | 8085 | catalogredemption | Catalogo premi (10 tipi), riscatti con stati, coupon pool, conversione unità, paga con i punti | RF-102..RF-104 |
| `contest-service` | 8086 | contestservice | Instant win a istanti vincenti pre-generati (DPR 430), ruota della fortuna | RF-30..RF-36, RF-97 |
| `identity-mapping` | 8087 | identitymapping | Grafo identità (sub OIDC, CRM, SAP, dispositivo, app, POS, e-commerce), risoluzione, merge/unmerge | RF-136 |
| `read-model` (context-service) | 8088 | readmodel | Customer 360 per membro (JSONB), proiezioni da 10 topic, `GET /v1/context/{id}`, snapshot keyset, audience | RF-125, RF-126 |
| `notifier` | 8089 | notifier | Modelli messaggio, webhook HMAC, **delivery omnicanale** (adattatori app/web/push/email/sms/webhook/operator), inbox, coda operatore | RF-77, RF-78, RF-132 |
| `segment-service` | 8090 | segmentservice | Segmenti (30 criteri), collezioni di valori, appartenenze | RF-65, RF-100 |
| `member-service` | 8091 | memberservice | Adesione, referral, etichette, campi custom, identificatori, GDPR (export/anonimizzazione), **consensi** con base giuridica | RF-72, RF-99, RF-135 |
| `engagement-service` | 8092 | engagementservice | Achievement, challenge, badge, classifiche con cicli premianti | RF-90..RF-96 |
| `decision-service` | 8093 | decisionservice | Motore decisionale, Next Best Action, decision log, previsioni, esperimenti | RF-127..RF-130, RF-134 |
| `fraud-service` | 8094 | fraudservice | Segnali di rischio → riskScore/riskLevel/reasonCodes, blocco automatico | RF-131 |

Flusso principale (0.5.0):

```
fonte → ingress-adapters → Kafka loyalty.actions.v1 (CloudEvents + correlationid)
      → decision-service: context (read-model) + rules-engine /v1/evaluations + offerte + previsioni + esperimento + policy
          → effetti contrattuali (ledger, tier, engagement, member) · DECISION_V1 → notifier/delivery → canali
          → azioni interne (CAMPAIGN_COMPLETED, EXPERIMENT_EXPOSED, CHURN_RISK_CHANGED) rientrano dallo stesso topic
      → fraud-service (segnali) → RISK_V1 → read-model → vincolo del motore
      → read-model → bff → site / widget
```

Con `RULES_APPLY_EFFECTS=true` e `DECISIONS_ENABLED=false` il rules-engine applica gli effetti da solo (modalità 0.4).

## 4. Eventi e contratti

- Un evento è un **CloudEvents 1.0 JSON**: `id` = chiave di idempotenza, `type` (costanti in `EventTypes`, es.
  `it.iren.loyalty.action.v1`), `source` (`urn:iren:loyalty:...`), `subject` = `member:<id>`, estensione
  `correlationid` (ereditata con `CanonicalEvents.withCorrelation`), `data` = payload.
- Azioni premianti (`RewardingAction`): `actionType` UPPER_SNAKE, `idempotencyKey`, `externalRef`, `occurredAt`,
  `reversalOf` (storno), `attributes` (mappa libera validata dallo schema evento del backoffice). Esterne e interne
  hanno la stessa forma (D08): i servizi emettono azioni interne sullo stesso topic.
- Topic: `loyalty.actions|movements|tiers|redemptions|contests|members|segments|decisions|risk|deliveries|consents|identities.v1`,
  DLQ `loyalty.actions.dlq.v1`. Chiave di partizione = `memberId`. Consumer idempotenti (chiave o `ON CONFLICT DO NOTHING`).
- Ogni servizio espone `/v1/...` con OpenAPI su `/v3/api-docs` (springdoc). Liste che crescono: paginazione keyset
  (`after`, `size`). Scritture: chiave di idempotenza nel corpo (`actionKey`, `grantKey`, `transferKey`, `mergeId`).
- Payload dei nuovi eventi: vedi `DecisionService.publish`, `RiskService.publish`, `DeliveryService.publish`,
  `ConsentService.publish`, `IdentityService.publishIdentity`; le proiezioni in `read-model/app/Projections.java` e
  le viste ClickHouse in `analytics/clickhouse/schema/050_loyalty40.sql` devono restare allineate.

## 5. Costruire, eseguire, verificare

```
make build            # mvn -f services/pom.xml package (tutti i moduli)
make test             # test JUnit 5 + AssertJ (src/test/java di ogni modulo)
make check            # tutto: Java + design system + sito + BFF + backoffice
make check-ds         # design system: eslint, tsc, vitest, build del pacchetto
make check-web        # node --check del BFF, next build del sito e del backoffice Payload
make up               # docker compose: Postgres, Kafka, Redis, tutti i servizi, CMS (:3002), sito (:3000), BFF (:3001)
make seed-local       # membro demo, azioni, transazione, adesione con referral
make up-observability # Grafana :3005, Prometheus, Loki, Tempo, OTel
make up-bi            # ClickHouse :8123, Superset :8088 (incorporato in /admin/analytics)
make lint             # helm lint + terraform validate
```

Verifica minima prima di un commit: `make build && make test`; se la modifica tocca il web, `make check-web`; se
tocca il design system, `make check-ds`. Il CMS si installa (`cd cms && npm ci`) e si controlla con
`npm run typecheck`: da quando il pannello è un'app Next (Payload 3) `npm run build` costruisce davvero `/admin` e
`/api`, e la mappa degli import dei componenti custom si rigenera con `npm run generate:importmap`.
La CI (`ci.yml`) fa la stessa cosa più `promtool check rules`, lint YAML/JSON dei values e dashboard, schema
ClickHouse su nodo singolo e ShellCheck sugli script.

Se Maven Central non è raggiungibile (è successo nel sandbox in cui è nato il codice), i domini puri si compilano con
`javac` e si verificano con test standalone: i domini di `decision-service`, `fraud-service`, `rules-engine/campaign`,
`engagement-service`, `tier-service`, `contest-service/wheel` non dipendono da Spring. Non lasciare mai un modulo che
compila "solo con gli stub": la CI con Maven è l'arbitro.

Il seed delle regole/campagne di esempio è in `rules-engine/RulesConfig.java`; le policy/regole di rischio/routing di
esempio sono `DecisionPolicy.example()`, `RiskPolicy.example()`, `DeliveryRouting.example()`,
`Consent.defaultPurposes()` e vengono usate quando il CMS non ha documenti pubblicati o non risponde.

## 6. Regole che non si rompono

1. **Il ledger è l'unica verità sui saldi.** Nessun servizio scrive movimenti se non tramite le API del ledger
   (`/v1/ledger/postings|debits|blocks|transfers|reversals`); le tabelle del ledger sono append-only (trigger). AI,
   policy decisionali, esperimenti e previsioni **non modificano mai** punti, saldi, denaro, eligibilità o status
   (RF-130): entrano solo nel punteggio delle azioni discrezionali.
2. **Azioni contrattuali vs discrezionali.** `AWARD_POINTS`, `UPGRADE_TIER`, `GRANT_BADGE`, `SET_ATTRIBUTE`,
   `EMIT_EVENT` sono sempre applicate (`DecisionPolicy.alwaysApply`); `ISSUE_REWARD`, `ISSUE_COUPON`, `SHOW_OFFER`,
   `SEND_MESSAGE`, `TRIGGER_CAMPAIGN`, `ASK_FOR_FEEDBACK` sono arbitrate. Non spostare un'azione da una classe all'altra
   nel codice: è una scelta del backoffice.
3. **Nessun dato personale nella piattaforma** (D12): identità = sub OIDC; anagrafica e recapiti restano nel CRM.
   Niente email/telefono in chiaro (solo hash come identificatori), niente PII in log, metriche, tracce, eventi,
   warehouse. L'OTel collector pseudonimizza l'id membro.
4. **Idempotenza ovunque**: ogni scrittura ha una chiave; ogni consumer tollera il replay. La chiave si considera
   consumata **solo dopo** che la scrittura è andata a buon fine: all'ingresso la pubblicazione attende l'ack del
   broker e, se manca, la chiave viene rilasciata e la fonte riceve un errore. Un duplicato è tollerato dal disegno,
   un'azione persa no.
5. **Concorsi (DPR 430)**: configurazione bloccata a concorso avviato (RF-35), istanti vincenti da CSPRNG, registro
   giocate a prova di manomissione, server in Italia. Non toccare `contest-service` senza leggere ADR-007 e RF-30..36.
6. **Configurazione dal backoffice, non da codice.** Tutto ciò che il marketing/Legal/frodi deve poter cambiare vive
   in una collezione Payload con workflow (bozza → revisione → approvato → [Legal] → pubblicato) e versione; i servizi
   leggono solo `status=published`, con cache (30 s) e ripiego all'ultimo valore buono o al seed. Una nuova
   funzionalità che introduce parametri li mette in una collezione esistente o nuova, mai in `application.yml`.
7. **Retro-compatibilità**: contratti di eventi e API sono versionati (`.v1`); un cambio incompatibile è un `.v2`
   con periodo di coesistenza; i flag `RULES_APPLY_EFFECTS` e `DECISIONS_ENABLED` restano finché il decision-service
   non è in produzione da un intero anno programma.
8. **Bounded context**: IDENTITY (identity-mapping), CUSTOMER (member-service, read-model), LOYALTY (ledger, tier),
   REWARDS (catalog-redemption), CAMPAIGNS/RULES (rules-engine), DECISIONS (decision-service), ENGAGEMENT
   (engagement-service, contest-service), ANALYTICS (read-model, ClickHouse), FRAUD (fraud-service), CONSENT
   (member-service), DELIVERY (notifier). Una funzionalità che tocca due contesti passa da eventi, non da join.

## 7. Convenzioni di codice

- Java 21, record per il dominio, funzioni pure nei package `domain` (niente Spring, niente I/O: `DecisionEngine`,
  `RiskEngine`, `CampaignEvaluator`, `TierPolicy`...); i servizi applicativi usano `JdbcTemplate` con SQL esplicito
  (JPA solo nel ledger); JSONB per documenti (contesto, decisioni, valutazioni).
- Adattatori verso altri servizi: interfacce in `domain`/`app` (es. `Ports.*`, `Effects`, `LedgerClient`) con
  implementazione `RestClient` nella `*Config` sotto `@ConditionalOnMissingBean`; URL da variabili d'ambiente
  `<SERVIZIO>_URL` con default `http://<nome>:<porta>` (stessi nomi in compose e Helm).
- Kafka: `@KafkaListener(topics = EventTypes.TOPIC_X, groupId = "<servizio>[-scopo]")`, payload `byte[]`,
  `CanonicalEvents.deserialize`/`data(event, Class)`; produzione con `KafkaTemplate<String, byte[]>` chiave `memberId`.
- Metriche: solo tramite `LoyaltyMetrics` (prefisso `loyalty_`, etichette a bassa cardinalità, mai l'id membro).
  Una metrica nuova = metodo in `LoyaltyMetrics` + riga in `docs/OSSERVABILITA-BI.md` + eventuale alert in
  `deploy/observability/alerts/loyalty-rules.yaml` con runbook in `docs/runbooks/osservabilita.md`.
- Migrazioni Flyway numerate per servizio (`V<n>__<nome>.sql`), mai modificate dopo il merge; schema = nome servizio.
- **Chiavi del ledger**: la chiave di idempotenza di un movimento identifica l'*effetto*, non l'azione, e comincia
  sempre con la chiave dell'azione (`azione:campagna`, `azione:decisione:tipo`) — il ledger è idempotente per
  (chiave, wallet) e lo storno dell'azione cerca per prefisso. Chi aggiunge un effetto che scrive sul ledger deriva
  la chiave con `RulesConfig.postingKey` o `DecisionService.effectKey`, mai a mano.
- Tempo: `Instant` in UTC nel dominio, `Europe/Rome` solo per calendario (ore di silenzio, anno programma, cron).
- SpEL: usato per condizioni/effetti delle campagne (`SpelExpressionEngine`, funzioni `#fn.*`), condizioni offerte e
  formule di punteggio (`SpelSupport`, contesto `SimpleEvaluationContext` in sola lettura). Non esporre mai un
  `StandardEvaluationContext` a espressioni scritte dagli utenti.
- CMS (`cms/src/*.ts`): collezioni con `versions: { drafts: true }`, campo `status` con i livelli `twoLevel`/`threeLevel`,
  hook `bumpVersion` quando i servizi riportano la versione; descrizioni dei campi in italiano, per l'operatore.
- BFF: un router a `if`/regex in `server.js`; ogni rotta operatore sotto `/api/operator/` e protetta da `isOperator`;
  degrado controllato (cache ultimo saldo, NBA → `NO_ACTION` se il motore non risponde). Le chiavi di idempotenza si
  compongono solo con `src/keys.js` (convenzione RI-01 a tre segmenti, mai `Date.now()`): dove l'operazione muove
  valore il `clientRef` è obbligatorio e in sua assenza si risponde 400.
- Test: JUnit 5 + AssertJ nel modulo (`src/test/java`, stessa struttura del package); i domini puri hanno test di
  logica con valori "parlanti" (es. Torino→Roma per il viaggio impossibile). Un fix di bug porta il test che lo riproduce.
- Commit: titolo in italiano, corpo puntato per componente, trailer `Co-Authored-By` se generato con Claude. Il
  repository ha **una sola versione** (oggi 0.7.0): `services/pom.xml` (`revision`, con `-SNAPSHOT` in sviluppo),
  `deploy/helm/loyalty-hub/Chart.yaml` (`version` e `appVersion`), `Makefile` (ripiego quando non c'è un tag) e i
  `package.json` di radice, design system, `cms/`, `web/site`, `web/bff`. Le immagini prendono comunque la versione
  dal tag git (`release.yml`).

## 8. Come estendere (ricette)

**Nuovo tipo di azione premiante** — definire lo schema in backoffice (`event-schemas`, RF-98) o come costante in
`EventTypes`; se emessa da un servizio, `CanonicalEvents.action(source, memberId, new RewardingAction(...))` sul
topic azioni; se deve essere ignorata dal decision-service o dal fraud-service (azioni "di servizio"), aggiungerla ai
rispettivi `SKIP`/`SERVICE_ACTIONS`; una campagna con trigger `CUSTOM_EVENT` la premia senza codice.

**Nuovo effetto di campagna** — `Campaign.Effect.Type` + `CampaignEvaluator` (rules-engine) → mappatura in
`decision-service/app/Candidates.fromCampaigns` → `ActionType` (nuova o esistente) → esecuzione in
`DecisionService.execute` + `Effects` → collezione `decision-policies` (opzione `type`) → test in
`DecisionEngineTest` e Smoke.

**Nuova azione decisionale** — `DecisionPolicy.ActionType`, classificazione contrattuale/discrezionale (default in
`defaultAlwaysApply`), `propensityKey`, `MESSAGING`/`OFFERS`/`RETENTION` nel `DecisionEngine`, `Effects` o consegna
via delivery, opzione nel CMS, riga in `docs/LOYALTY-4.0.md`.

**Nuovo vincolo della policy** — campo in `DecisionPolicy.Constraints`/`ActionSpec` → controllo in
`DecisionEngine.constraintViolation` con codice motivo `UPPER_SNAKE` → `CmsSources.toPolicy` → campo nel CMS →
test con il caso che scatta e quello che non scatta → codice nell'elenco di `docs/LOYALTY-4.0.md`.

**Nuova previsione** — chiave in `RuleBasedPredictionProvider` (deterministica, in [0,1]) → `CompositePredictionProvider.STANDARD_KEYS`
→ opzione `servesKeys` nel CMS → uso nel punteggio (`propensityKey`) o nelle condizioni delle offerte.

**Nuovo provider di previsione** — implementare `PredictionProvider`, aggiungere il `Kind` in `PredictionRouting`,
costruirlo in `DecisionConfig.predictionProvider`; timeout breve, nessun retry, contesto pseudonimo.

**Nuovo segnale antifrode** — `RiskPolicy.Signal` + spec in `example()` → osservazione in `RiskStore.activity`
(query su finestra) → campo in `RiskEngine.MemberActivity` → `observed.put(...)` in `RiskEngine.assess` → opzione
nel CMS `fraud-rules` → test in `RiskEngineTest`.

**Nuovo canale di consegna** — implementare `ChannelAdapter` (in `notifier/delivery/Adapters` o classe propria),
registrarlo nella lista `channelAdapters` di `NotifierConfig`, aggiungere il codice canale alle `options` dei campi
`channel` nel CMS (`delivery-routing`, `decision-policies`).

**Nuovo tipo di identificatore** — usare `IdentityGraph.Identifier(kind, value)`; se deterministico aggiungerlo a
`IdentityGraph.DETERMINISTIC`; i canali lo passano a `POST /v1/identities/resolve`.

**Nuova collezione nel backoffice** — definirla in `cms/src/collections*.ts`, registrarla in `payload.config.ts`,
leggerla dal servizio con il pattern `cached(...)` di `CmsSources` (published, cache, fallback, versione), documentare i
campi per l'operatore, aggiungere i permessi in `roles`.

**Nuovo servizio** — copiare `decision-service` (pom, Dockerfile, application.yml, `*Application`, `*Config`),
porta libera dopo 8094, schema Flyway proprio, aggiungere a `services/pom.xml` (`<modules>`), `docker-compose.yml`,
`deploy/helm/loyalty-hub/values.yaml`, `.github/workflows/release.yml` (matrice), README (§Struttura) e agli env
`<NOME>_URL` di chi lo chiama.

**Nuova metrica/KPI di BI** — evento di dominio → tabella Kafka engine + `fact_*` + vista materializzata in
`analytics/clickhouse/schema/0nn_*.sql` → vista `kpi_*` → grafico nell'export Superset.

## 9. Documentazione da tenere allineata

Ogni modifica funzionale aggiorna, nello stesso commit: il documento del filone (`docs/CATALOGO-FUNZIONALE.md`,
`docs/OSSERVABILITA-BI.md`, `docs/LOYALTY-4.0.md`), l'ADR se cambia una scelta (nuovo numero, mai riscrivere uno
approvato), `README.md` (§Architettura, §Struttura, §Stato), i contratti in `docs/contracts/` se cambiano API o eventi,
il runbook se nasce un alert. Il documento vivo "Specifica Loyalty Hub" e il "Registro decisioni" (D01–D25) sono
mantenuti in Claude: chi modifica il codice segnala cosa va riportato lì.

## 10. Dove migliorare (backlog noto)

- ~~Build completa con Maven~~ **fatta**: `mvn -f services/pom.xml package` è verde su tutti e 15 i moduli (41 test).
  La prima esecuzione ha trovato due errori veri, entrambi invisibili a `javac` con gli stub: raw type di
  `RestClient.body(List.class)` in un ternario (rules-engine) e `AchievementEngine.periodKey` package-private usata
  da `app` (engagement-service). Il compilatore ora gira con `-Xlint:unchecked,rawtypes,deprecation` senza warning:
  tenerlo così.
- ~~Punti aperti dalla revisione del codice~~ **chiusi tutti e otto** (`docs/REVISIONE-CODICE-0.5.0.md`, che tiene
  la decisione presa per ciascuno). Resta da chiudere il merge di `identity-mapping`, dove un errore fra il
  trasferimento delle unità e la chiusura del membro assorbito lascia uno stato incoerente (nessun ammanco: il
  `transferKey` è idempotente e il merge si ripete); va affrontato insieme all'unmerge dei saldi. Cambiano semantica di saldi o consegne: si affrontano uno
  alla volta, con i test di integrazione a fare da rete.
- ~~Test di integrazione con Testcontainers~~ **c'è il banco**: `PostgresIntegrationTest` in `common` (test-jar,
  Postgres 16 condiviso, migrazioni Flyway vere) e un `ContextLoadsTest` per servizio. Da estendere a Kafka per il
  ciclo decisionale end-to-end, il merge identità con trasferimento unità e la consegna con fallback.
- OpenAPI: generare e pubblicare in `docs/contracts/` gli spec di tutti i servizi (oggi solo ingresso) e AsyncAPI per
  i topic nuovi (decisions, risk, deliveries, consents, identities).
- `read-model`: cache Redis del contesto e ricalcolo notturno delle finestre (RFM, contatti a 7 giorni); oggi il
  ricalcolo avviene a lettura (`ContextProjector.refreshed`).
- `decision-service`: budget giornaliero di unità (`dailyUnitsBudget`) non ancora applicato; `HttpPredictionProvider`
  senza circuit breaker (Resilience4j è già nel parent pom); metriche di uplift anche nel decision log.
- `fraud-service`: segnali su rete di dispositivi (grafo), lista nera di IP/dispositivi dal backoffice, revisione
  manuale con esito che rialimenta le soglie.
- `notifier`: reinvio manuale delle consegne fallite dalla console, template per canale con localizzazione en,
  adattatori push/email/sms reali (oggi `MessageSender` su log).
- `identity-mapping`: unmerge che ripristina anche i saldi (oggi solo identificatori), collegamenti probabilistici
  proposti in coda operatore.
- Sito: area riservata completa (wallet, catalogo, gamification, inbox interattiva, consensi) con autenticazione OIDC
  reale; oggi `demo-member` fisso.
- Backlog di parità (`docs/PARITA-OPEN-LOYALTY.md`): analytics avanzate, export verso S3, rotazione segreti webhook,
  notifiche di scadenza punti.
- Sicurezza: OAuth2/OIDC sulle API interne (oggi rete di servizio), rate limiting per fonte nel gateway, scansione
  dipendenze in CI, SBOM.

## 11. Contesto normativo che condiziona il codice

DPR 430/2001 (concorsi e operazioni a premio): comunicazione al Ministero, cauzioni, durata, server in Italia, perizia
sul software instant win, premi non assegnati alla ONLUS. GDPR: consensi separati per finalità con base giuridica,
diritto all'oblio (anonimizzazione, `member-service`), portabilità (export), registro giocate conservato per legge,
warehouse senza dati personali con retention 5 anni. In caso di dubbio la scelta conservativa vince e va scritta in un ADR.
