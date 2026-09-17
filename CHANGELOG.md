# CHANGELOG

Il progetto segue [Keep a Changelog](https://keepachangelog.com/it/1.1.0/) e il versionamento semantico.

## [Non rilasciato]

### Aggiunto
- design system: i 15 pattern non sono più solo contratti di tipo, hanno la loro **implementazione React**
  (`DataTable`, `SectionForm`, `RuleCard`/`RuleList`, `KpiTabsChart`, `EntityProfile`/`Timeline`, `EmptyState`,
  chip e bottoni), con gli stili fatti solo di token `--lh-*` e 14 test che provano le regole del catalogo —
  contatore della lista, operatori sempre scritti, sezioni in ordine fisso, nessun id tecnico a schermo
- `web/playground`: sito statico (export Next.js, nessun backend) che mostra i pattern con dati finti e un
  simulatore del motore decisionale dove si vede *perché* un'azione viene scartata, con il suo codice motivo
- `make playground` e passo dedicato in CI

### Sicurezza
- Next.js aggiornato da 15.5.0 a 15.5.25 (CVE-2025-66478) nel sito e nel playground

## [0.7.0] — 2026-09-17

### Licenza e nome
- licenza Apache-2.0 (testo ufficiale in `LICENSE`, campo `license` nei manifest e blocco `licenses` nel pom)
- rinomina vendor neutral: `it.iren.loyalty` → `io.loyaltyhub`, tipi evento `io.loyaltyhub.*.v1`, URN `urn:loyaltyhub:*`;
  nessun riferimento a marchi o prodotti di terze parti nel repository
- documentazione bilingue italiano/inglese: README, CLAUDE, CONTRIBUTING, SECURITY, `docs/` e ADR 001-026

### Aggiunto
- `identity-mapping`: il merge è una macchina a stati registrata (`merge_history.status`), ripartibile passo per passo,
  con chiave di idempotenza esplicita (`mergeId`) e ripresa automatica dei merge interrotti (`POST
  /v1/identities/merges/{id}/advance` e uno scheduler ogni minuto)
- `identity-mapping`: l'unmerge ripristina anche i saldi, ritrasferendo le unità che il merge aveva spostato; se il
  membro sopravvissuto le ha spese torna indietro solo ciò che c'è e la differenza è un ammanco dichiarato
  (`shortfall`), mai un accredito creato dal nulla

- `decision-service`: il budget giornaliero di unità della policy è finalmente applicato (`UNITS_BUDGET` fra i codici
  di scarto, giornata di programma su `Europe/Rome`, azioni contrattuali escluse), con due metriche e un alert al 90%
- `decision-service`: interruttore automatico (Resilience4j) davanti al provider di previsioni esterno; quando è aperto
  si decide con le regole invece di pagare il timeout a ogni decisione
- `decision-service`: `GET /v1/decisions/experiments/{id}/effect` — decisioni, membri, azioni e unità per variante

- `read-model`: cache del Customer 360 su Redis, condivisa fra le repliche e con TTL, al posto di una mappa per
  processo che non scadeva mai e non vedeva le scritture altrui; se Redis non risponde si legge dal database
- `read-model`: ricalcolo notturno delle finestre mobili (RFM, conteggi a 30 giorni, contatti a 7 giorni), in keyset,
  che riscrive solo i contesti davvero cambiati

- `notifier`: adattatori verso i fornitori reali di push, email e SMS (`PUSH_PROVIDER_URL`, `EMAIL_PROVIDER_URL`,
  `SMS_PROVIDER_URL` con le rispettive chiavi); dove non sono configurati resta l'invio su log. Nessun recapito passa
  dalla piattaforma: il fornitore risolve il destinatario dall'id membro
- `notifier`: coda delle consegne fallite e reinvio manuale (`GET /v1/deliveries/failed`,
  `POST /v1/deliveries/{id}/resend`, rotte operatore nel BFF); il testo si ri-renderizza dal modello corrente e
  l'operatore può imporre un canale diverso da quello che aveva fallito

- sicurezza delle API interne (RF-43): ogni servizio è un resource server OAuth2/OIDC che pretende un token con lo
  scope del programma, e ogni chiamata fra servizi porta un token client credentials rinnovato prima della scadenza;
  lo stesso vale per il BFF. Spenta per difetto, si accende per ambiente (`INTERNAL_AUTH_ENABLED`, `OIDC_*`, valori
  Helm in `global.internalAuth`): l'ambiente locale resta senza attriti

- contratti pubblicati (RF-133): la specifica OpenAPI di tutti e 14 i servizi vive in `docs/contracts/generated/`,
  generata dal codice e verificata a ogni esecuzione dei test — se un'API cambia senza aggiornare il contratto, il
  test fallisce; l'AsyncAPI copre anche decisioni, rischio, consegne, consensi e identità
- ogni specifica si presenta con il nome del servizio e la versione dell'API (`v1`) invece di «OpenAPI definition v0»

### Corretto
- con una catena di sicurezza attiva un 404 inoltrato a `/error` tornava come 401, cioè un errore di percorso che si
  presentava come un problema di credenziali; `/error` è fra i percorsi aperti
- `read-model`: i contatti a 7 giorni per canale potevano solo crescere — il contesto teneva i contatori, non le
  consegne — e un membro molto contattato restava sopra il tetto per sempre, senza più un canale disponibile. Ora il
  contesto conserva le ultime 50 consegne e la finestra si ricalcola
- `identity-mapping`: un errore fra il trasferimento delle unità e la chiusura del membro assorbito lasciava uno stato
  incoerente che nessuno recuperava, e il riprovo generava una chiave nuova trasferendo una seconda volta

## [0.6.2] — 2026-09-17

### Corretto (guasti di avvio, trovati dai test di integrazione)
- `ledger`: i repository JPA annidati in `Repositories` non venivano registrati — nessun bean, servizio che non parte
- `common`: `MetricsAutoConfiguration` valutava `@ConditionalOnBean(MeterRegistry)` prima che Micrometer registrasse
  il registro; senza `LoyaltyMetrics` nessun servizio si avvia
- `catalog-redemption`: la colonna generata `redemption.expires_at` sommava un intervallo a un `timestamptz`,
  espressione che Postgres rifiuta come non immutabile: lo schema non si creava affatto

### Corretto (addebiti senza compensazione)
- `catalog-redemption` e `contest-service`: l'addebito sul ledger è una chiamata a un altro servizio e restava
  committato anche quando il riscatto o la giocata fallivano subito dopo (lotto di codici esaurito, stock, budget).
  Ora ogni fallimento successivo all'addebito lo storna, e lo storno è idempotente
- `catalog-redemption`: `record` legava il codice del lotto al riscatto prima di inserirne la riga, violando la
  chiave esterna — nessun buono da lotto era riscattabile. La riga si scrive per prima
- `catalog-redemption` espone la porta `LedgerPort` (implementazione REST nella configurazione) come vuole la
  convenzione: prima il servizio costruiva il proprio client e la compensazione non era verificabile

### Corretto (paga con i punti)
- `catalog-redemption`: le unità da scalare si arrotondavano per eccesso al passo e lo sconto superava l'importo del
  carrello (5,55 € → 600 punti = 6,00 €). Ora si arrotonda per difetto e il resto si paga normalmente; sotto il
  taglio minimo la richiesta è rifiutata (`AMOUNT_BELOW_MINIMUM`) e unità esplicite che valgono più del carrello
  danno `UNITS_EXCEED_AMOUNT` invece di essere consumate in silenzio

### Corretto (tier)
- `tier-service` sommava i punti STATUS di ogni movimento letto dal topic senza memoria di quelli già applicati: un
  replay dell'outbox (at-least-once) gonfiava punti e tier. Ogni movimento applicato è ora registrato (migrazione
  V3) e l'azione interna `TIER_CHANGED` ha una chiave stabile invece che presa dall'orologio

### Corretto (chiavi di idempotenza del BFF)
- Le chiavi costruite dal BFF avevano cinque segmenti invece dei tre della convenzione RI-01: l'ingresso le
  respingeva tutte, quindi nessun evento comportamentale del sito né azione da sportello entrava in piattaforma.
  Ora si compongono in `web/bff/src/keys.js`, che rifiuta una chiave storta invece di spedirla
- Le chiavi non nascono più da `Date.now()`: dove l'operazione muove valore (trasferimenti, azioni da sportello,
  badge, blocchi) il `clientRef` del chiamante è obbligatorio, altrimenti 400

### Corretto (premi e classifiche)
- `engagement-service`: la chiusura di un ciclo premiante marcava il ciclo come chiuso prima di assegnare i premi;
  un errore a metà elenco lasciava i vincitori successivi senza nulla. La riga del ciclo è ora una prenotazione
  (`rewarded_at`, migrazione V2) e un ciclo non confermato viene ripreso al giro successivo
- `engagement-service`: le classifiche con metrica `ACHIEVEMENT_PROGRESS` non potevano mai segnare punti, perché il
  filtro anti-anello scartava anche `ACHIEVEMENT_PROGRESSED`. Le azioni interne ora alimentano le classifiche pur
  restando fuori dal motore; aggiunta la guardia sul riferimento nullo

### Corretto (azioni perse all'ingresso)
- `ingress-adapters` registrava la chiave di idempotenza prima di pubblicare e non attendeva l'esito dell'invio a
  Kafka: con il broker fermo la fonte riceveva 202, l'azione non entrava e ogni rinvio era respinto come duplicato.
  Ora la pubblicazione attende l'ack, la chiave viene rilasciata se il broker non conferma e il lotto risponde 503
  con gli elementi marcati `FAILED`; stesso trattamento per i check-in. Contratto OpenAPI aggiornato

### Corretto (accrediti persi)
- La chiave di idempotenza del ledger identificava l'azione e non l'effetto: due campagne che premiavano lo stesso
  wallet per lo stesso evento producevano un solo accredito (decision-service) o un conflitto sul vincolo di unicità
  che bloccava il consumo (rules-engine). Ora ogni effetto ha la sua chiave derivata e lo storno dell'azione le
  ritrova per prefisso, senza toccare scadenze e storni già emessi; un secondo storno non rompe più nulla

### Aggiunto
- Banco di prova di integrazione: `PostgresIntegrationTest` in `common` (test-jar, Postgres 16 con migrazioni vere) e
  un `ContextLoadsTest` per ciascuno dei 14 servizi — la prova che il contesto Spring si alza davvero

### Modificato
- Una sola versione per tutto il repository: `services/pom.xml`, `Chart.yaml`, `Makefile` e i cinque `package.json`
  passano da 0.5.0/0.6.1/0.1.0 a 0.6.2. Le immagini continuano a prendere la versione dal tag git

Il monorepo 0.5.0 (servizi Java, CMS, web, deploy, analytics) e il bundle UX 0.6.0 vivono nello stesso
repository; da qui tutto si costruisce e si verifica con `make check`.

### Corretto
- `rules-engine` non compilava: `RestClient.body(List.class)` restituisce un raw type e in `badgesOf`/`segmentClient`
  finiva in un ternario con `Set.of()`, risolto a `Object`. Le risposte JSON dei servizi interni hanno ora un tipo
  dichiarato (`ParameterizedTypeReference`) in tutti i client REST dei servizi
- `engagement-service` non compilava: `AchievementEngine.periodKey`, package-private, è usata dalla chiusura dei
  cicli delle classifiche; resa pubblica perché la chiave del ciclo deve essere la stessa delle achievement
- CMS: `npm install` falliva (`@payloadcms/next@3.89` richiede `payload` esatto ed esclude Next 15.5); Payload
  pinnato a 3.89.0 con Next 15.4.11 e `graphql` esplicito
- CMS: i campi `richText` senza `editor` impedivano il caricamento della configurazione (`MissingEditorProp`);
  aggiunto `lexicalEditor()`
- CMS: senza `"type": "module"` il config non risolveva i propri import
- Sito: `outputFileTracingRoot` fissato, altrimenti `.next/standalone` finisce annidato e il Dockerfile non trova
  `server.js`

### Corretto (revisione del codice, `docs/REVISIONE-CODICE-0.5.0.md`)
- `notifier`: il testo consegnato non teneva conto del canale — il corpo di una email poteva partire come SMS o push
- `tier-service`: l'assegnazione manuale accettava un codice tier inesistente e il membro finiva retrocesso a BASE
- `catalog-redemption`: l'annullo dall'area membro non verificava che il riscatto fosse del richiedente
- `web/bff`: il corpo della richiesta poteva sovrascrivere il `memberId` preso dal percorso
- `member-service`: sostituzione dei campi custom fuori transazione
- `engagement-service`: nome tabella concatenato nel SQL senza elenco chiuso di valori ammessi

### Aggiunto
- `docs/REVISIONE-CODICE-0.5.0.md`: otto punti aperti che cambiano semantica di saldi, transazioni o consegne, con
  l'analisi di ciascuno e le alternative
- Guscio Next del backoffice Payload 3 (`(payload)/admin`, `(payload)/api` REST/GraphQL, mappa import dei componenti
  custom) e `tsconfig.json`: `npm run typecheck` e `npm run build` fanno davvero il loro lavoro
- `make check`, `make check-ds`, `make check-web`
- `-Xlint:unchecked,rawtypes,deprecation` su tutti i moduli Java (build senza warning)
- CI: un solo workflow con Java, design system, web e CMS, Helm/Terraform, osservabilità, ShellCheck
- `CLAUDE.md`

### Modificato
- La configurazione ESLint della radice si applica al solo design system: applicata al sito faceva fallire
  `next build`
- Dockerfile del CMS: `npm ci` dal lockfile, generazione della mappa import, `next start`

## [0.6.1] — 2026-09-17

Il repository passa da raccolta di file a progetto verificabile: `npm ci && npm run check`
esegue lint, controllo dei tipi, test e build.

### Aggiunto
- Workspace npm alla radice (`package.json`, `tsconfig.base.json`, `eslint.config.js`, `.nvmrc`, `.editorconfig`, `.gitignore`)
- Pacchetto `@loyalty-hub/backoffice-design-system` con build TypeScript (`dist/` con `.d.ts` e source map) ed export pubblici (`.`, `./patterns`, `./tokens.css`)
- Regole eseguibili accanto ai contratti, con 61 test (Vitest):
  - macchina a stati del workflow ADR-014 (`availableTransitions`, `canTransition`, `buildWorkflowInfo`) con filtro per ruolo e verifica Legal — RF-137, RF-43
  - composizione delle frasi di condizioni e chip senza identificativi tecnici (`describeCondition`, `buildFilterChip`) — RF-138, RF-139
  - formattazione italiana di valori, contatore ad anello e confronto con il periodo precedente (`formatValue`, `formatCount`, `formatDelta`) — LG-04, LG-29, LG-40, LG-41
  - ordine fisso delle sezioni del form e blocco dell'eliminazione per dipendenze (`sortSections`, `isDeletionBlocked`) — LG-06, RF-141
- Integrazione continua GitHub Actions: lint, tipi, test, build e ShellCheck
- Token strutturali del design system: scala di spaziature, raggi, ombre e anello di focus
- Test di parità dei token: i due blocchi del tema scuro non possono più divergere

### Modificato
- `web/backoffice-design-system/patterns.ts` si articola in `src/` (un modulo per famiglia di pattern); `src/patterns.ts` resta il punto d'ingresso dei soli tipi citato dall'ADR-026
- I token CSS hanno prefisso `--lh-` per convivere con le variabili dell'admin di Payload e di Next.js (`--ink` → `--lh-ink`, `--accent` → `--lh-accent`, …)
- `scripts/publish-github.sh`: non crea più commit né tag, non scrive il token in `.git/config`, verifica i codici HTTP dell'API, è idempotente e supporta `DRY_RUN=1`

### Corretto
- I contratti dichiaravano un `React.ReactNode` locale pari a `unknown`: le prop `render` e `content` non erano utilizzabili in JSX. Ora il pacchetto usa i tipi di React (`@types/react` come peer dependency)
- `Unit` e `LocalizedText` collassavano su `string` e `Record<string, string>`, annullando i valori suggeriti: ora i letterali restano visibili e `it` è la lingua obbligatoria
- Le frasi con più valori perdevano il connettore («Tier non è uno di Elite Gold»): ora la riga si legge «Elite o Gold», con ogni valore ancora navigabile come chip separata

## [0.6.0] — 2026-09-17

### Aggiunto
- `docs/LINEE-GUIDA-UX-BACKOFFICE.md`: 48 linee guida UX con mappa video → LG → RF
- `docs/adr/ADR-026.md`
- `web/backoffice-design-system/`: contratti dei 15 pattern, token, README con ordine di costruzione
- Requisiti RF-137..RF-142 (`docs/SPECIFICA-ADDENDUM-UX.md`)

### Modificato
- Navigazione del backoffice: sei gruppi (Amministrazione, Generale, Moduli loyalty, Concorsi e programma, Contenuti, Decisioni)
- Piano di rilascio fase Core: design system prima dei moduli (+3 settimane/persona)
