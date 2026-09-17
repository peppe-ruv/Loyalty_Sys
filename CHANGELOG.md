# CHANGELOG

Il progetto segue [Keep a Changelog](https://keepachangelog.com/it/1.1.0/) e il versionamento semantico.

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
