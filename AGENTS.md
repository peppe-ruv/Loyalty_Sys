# Guida di stile per la documentazione

Questa guida vale per chiunque scriva o modifichi la documentazione di Loyalty Hub: persone, l'agente di Mintlify e Claude Code. Copre le pagine Mintlify (`*.mdx` alla radice del repository) e le specifiche in `docs/`.

Leggila prima di ogni modifica. Le regole della seconda parte sono vincolanti: una modifica che le viola non si unisce.

## Il tuo ruolo

Agisci come un Senior Technical Writer esperto della [Google Developer Documentation Style Guide](https://developers.google.com/style).

Il tuo obiettivo è una documentazione di livello aziendale, pensata per l'onboarding di nuovi utenti.

## Stile di scrittura

- **Usa la voce attiva.** Il tono è diretto, amichevole ma oggettivo.
- **Scrivi per compiti.** Organizza il testo in base a ciò che il lettore vuole ottenere, non a come è scritto il codice. Scrivi «Come installare il modulo X», non «Il modulo X».
- **Rivolgiti al lettore con il «tu» imperativo.** Scrivi «Esegui questo comando per…», non «Questo comando viene eseguito per…».
- **Tieni brevi frasi e paragrafi.** Un paragrafo esprime un'idea.
- **Evita il gergo inutile.** Se un termine tecnico serve, spiegalo alla prima occorrenza.

## Pensa ai nuovi utenti

- **Rivela i dettagli per gradi.** Parti dalla panoramica e dai concetti semplici. Sposta i dettagli complessi e le configurazioni avanzate in fondo alla pagina o in pagine separate.
- **Prima il perché, poi il come.** Per ogni macro-funzione o modulo spiega a cosa serve e quale problema risolve. Solo dopo spiega come si usa.
- **Includi le sezioni fondamentali.** Dove servono, la pagina ha:
  - «Prerequisiti»: cosa deve avere il lettore prima di iniziare;
  - «Quickstart» o «Primi passi»: il percorso più breve verso un risultato visibile;
  - «Esempi d'uso pratico»: casi reali, completi e copiabili.

## Formattazione Markdown

- **Usa una gerarchia di titoli chiara.** Il titolo della pagina è l'H1; dentro la pagina usa H2 e H3, senza saltare livelli.
- **Indica sempre il linguaggio nei blocchi di codice** (```` ```bash ````, ```` ```json ````, ```` ```java ````…). Aggiungi commenti che spiegano i passaggi dentro il codice:

  ```bash
  # Avvia Kafka, Postgres e Kafka UI in background
  docker compose -f deploy/docker-compose.yml up -d kafka postgres kafka-ui
  ```

- **Segnala gli errori comuni con note e avvisi.** Nelle pagine Mintlify usa i componenti `<Note>`, `<Warning>`, `<Tip>` e `<Info>`:

  ```mdx
  <Warning>
    Il reset elimina i movimenti generati durante la sessione.
  </Warning>
  ```

  Nei file Markdown puri (per esempio in `docs/`) scrivi `> **Nota:**` o `> **Attenzione:**`.

## Regole del progetto (vincolanti)

Queste regole nascono dagli errori di una bozza precedente (PR #97). Rispettale tutte.

### Modifica solo ciò che serve

- Una modifica tocca solo il testo che serve. Non riscrivere e non duplicare interi paragrafi.
- Rileggi il diff prima di salvare. Controlla che non ci siano:
  - parole ripetute o fuse, come «Gestione campagGestione campagne» o «LEGALEGAL»;
  - sezioni, note o paragrafi doppi.
- Non sovrascrivere mai una pagina con il contenuto di un'altra.
- Il titolo nel frontmatter (`title`) descrive quella pagina, non è lo slug del file.

### Rispetta la fonte di verità

- La fonte di verità è `docs/` (CLAUDE.md, regola 17, ADR-040).
- Non modificare a mano le pagine `specifiche/*.mdx` nell'editor di Mintlify. Per cambiarle, modifica `docs/` in una pull request.

### Scrivi in italiano corretto

- Usa gli accenti: «è», «già», «perché», «attività», «funzionalità».
- Conserva i simboli tipografici: →, ×, ≤, ≈, °. Non sostituirli con equivalenti ASCII come `->` o `x`.

### Link e terminologia

- Scrivi i link interni come percorsi assoluti dalla radice del sito: `/getting-started/quickstart`, non `getting-started/quickstart`.
- Chiama «membro» chi partecipa al programma, non «cliente» né «utente».
- Scrivi i ruoli in maiuscolo, come nel codice: `ADMIN`, `MARKETING`, `LEGAL`, `CARE`, `ANALYST`.
- Formatta come `code` i nomi di campi, endpoint, eventi e variabili d'ambiente: `memberId`, `POST /v1/events`, `lh.actions.v1`, `DB_URL`.
- Non citare aziende reali (CLAUDE.md, regola 7). Il brand demo è «Club Aurora».

### Diagrammi e MDX

- Ogni diagramma Mermaid ha `accTitle` e `accDescr`. Verifica con:

  ```bash
  # Controlla i diagrammi di una o più cartelle
  node scripts/check-mermaid.mjs <cartella>
  ```

- In MDX, fuori dai blocchi di codice, scrivi le graffe come `\{ \}` e il segno di minore come `&lt;`. Altrimenti la pagina non compila.

### Pubblica con una pull request

- Ogni modifica passa per una pull request verso `main` (CLAUDE.md, regola 16). Non salvare direttamente su `main`.
- Compila il modello `.github/pull_request_template.md`.
