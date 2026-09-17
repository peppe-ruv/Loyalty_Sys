# CHANGELOG

Il progetto segue [Keep a Changelog](https://keepachangelog.com/it/1.1.0/) e il versionamento semantico.

## [0.7.0] — 2026-09-17

### Cambiato (fondamenta del design system, ADR-027)
- Il design system del backoffice poggia sui [Design Tokens Italia](https://github.com/italia/design-tokens-italia)
  1.3.3 (`italia/design-tokens-italia@b59fe73`) invece che sui valori del mockup «Backoffice Loyalty Hub».
  `tokens.css` dichiara 54 primitive `--it-*` copiate alla lettera da `dist/css/variables.css` e definisce
  ogni token semantico `--lh-*` come alias verso una di esse: aggiornare la base è cambiare una riga, non
  ridipingere i componenti
- Accento del prodotto sulla rampa **teal** di Italia (`#0a6b5e` → `#05615e`); fondo pagina `#eef2f1` →
  `#f5f5f5`; sidebar `#14201c` → `#17324d`. Il blu istituzionale resta ai link (`--lh-link`), così il
  backoffice non si traveste da portale della PA
- Tipografia: **Titillium Web** e **Roboto Mono** al posto di Archivo e IBM Plex, con la scala, le
  interlinee e i pesi di Italia. I font si caricano da Google Fonts: il repository non ne ridistribuisce
  i file
- Spaziature, raggi, spessori, dimensioni icona ed elevazioni presi da `--it-spacing-*`, `--it-radius-*`,
  `--it-border-*`, `--it-icon-size-*`, `--it-elevation-*`. Il vecchio passo a 4px coincideva già: la
  mappatura delle spaziature è uno a uno
- Il tema scuro **ripunta gli alias** anziché ridefinire le primitive: Italia 1.3.3 pubblica un solo set
  semantico, costruito su fondo chiaro, e i gradini scuri vengono dalle stesse rampe ufficiali

### Aggiunto
- `--lh-line-strong`: il bordo di un controllo (campo, select, checkbox, dropzone) è ora distinto dal
  separatore decorativo `--lh-line`, che restava sotto 3:1
- Le quattro coppie `--lh-on-accent-soft`, `--lh-on-ok-soft`, `--lh-on-warn-soft`, `--lh-on-danger-soft`:
  la vecchia tavolozza non diceva quale inchiostro va sopra una chip colorata e a occhio si finiva sotto
  soglia nel tema scuro
- `--lh-link`, `--lh-icon-xs|s|m|l`, `--lh-elevation-low`
- Tre regole eseguibili in `src/tokens.test.ts` (66 test, prima 61): ogni colore `--lh-*` risolve a una
  primitiva `--it-color-*` salvo `--lh-volt`; il tema scuro non ridefinisce primitive; ogni coppia
  testo/fondo dichiarata sta sopra la soglia WCAG 2.1 AA (4,5:1 per il testo, 3:1 per bordi di controllo
  e anello di focus) **in entrambi i temi**
- `docs/adr/ADR-027-design-tokens-italia.md` con le tre deviazioni ammesse e le alternative scartate

### Rinominato (token pubblici del pacchetto)
- `--lh-accent-ink` → `--lh-on-accent`, `--lh-volt-ink` → `--lh-on-volt`
- `--lh-bad` → `--lh-danger`, `--lh-bad-soft` → `--lh-danger-soft`
- `--lh-shadow-card` → `--lh-elevation-medium`, `--lh-shadow-overlay` → `--lh-elevation-high`
- `--lh-border-width` → `--lh-border-base`, affiancato da `--lh-border-double` e `--lh-border-thick`
- `--lh-focus-ring` → `--lh-focus`: l'anello si compone con `--lh-border-double`, non è un token composito
- `--lh-font-display` e `--lh-font-body` → `--lh-font-sans`; `--lh-font-mono` invariato di nome
- Nessuna schermata consuma ancora i token: il cambio di nome non rompe nulla oggi e sarebbe costato caro
  dopo il primo modulo

### Rimosso
- `--lh-volt` resta l'unico colore letterale del sistema (il giallo-verde dei concorsi): ogni altro valore
  esadecimale fuori dalle primitive Italia fa fallire i test

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
  - macchina a stati del workflow D14 (`availableTransitions`, `canTransition`, `buildWorkflowInfo`) con filtro per ruolo e verifica Legal — RF-137, RF-43
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
- `docs/adr/ADR-026-linee-guida-ux-backoffice.md`
- `web/backoffice-design-system/`: contratti dei 15 pattern, token, README con ordine di costruzione
- Requisiti RF-137..RF-142 (`docs/SPECIFICA-ADDENDUM-UX.md`)

### Modificato
- Navigazione del backoffice: sei gruppi (Amministrazione, Generale, Moduli loyalty, Concorsi e programma, Contenuti, Decisioni)
- Piano di rilascio fase Core: design system prima dei moduli (+3 settimane/persona)
