# CHANGELOG

Il progetto segue [Keep a Changelog](https://keepachangelog.com/it/1.1.0/) e il versionamento semantico.

## [0.6.2] — 2026-09-17

### Corretto (guasti di avvio, trovati dai test di integrazione)
- `ledger`: i repository JPA annidati in `Repositories` non venivano registrati — nessun bean, servizio che non parte
- `common`: `MetricsAutoConfiguration` valutava `@ConditionalOnBean(MeterRegistry)` prima che Micrometer registrasse
  il registro; senza `LoyaltyMetrics` nessun servizio si avvia
- `catalog-redemption`: la colonna generata `redemption.expires_at` sommava un intervallo a un `timestamptz`,
  espressione che Postgres rifiuta come non immutabile: lo schema non si creava affatto

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
