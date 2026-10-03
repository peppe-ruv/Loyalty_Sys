# Avvio del collaudo di release

Come far partire il collaudo di release (ADR-052 decisione 2, Q-680…Q-685, F2-QA-06). Il copione è `copione.md`, il modello del rapporto è `rapporto-modello.md`. La guida per chi legge la documentazione è nella pagina «Qualità e collaudo» del sito (`concetti/qualita-e-collaudo.mdx`).

Il collaudo è **consultivo**: produce un rapporto e delle issue, la decisione di rilascio resta al proprietario.

## Quando

- Prima di ogni tag `vX.0.0`, con l'immagine candidata `build-<sha>` sulla vetrina accesa (Q-681).
- A richiesta del proprietario alla chiusura di una milestone di Fase 2.

Non serve per ogni tag `v*`: un collaudo dura decine di minuti e consuma le ore gratuite di Codespaces del proprietario.

## Prerequisiti

- La candidata è sulla vetrina: il segreto del codespace `LH_IMAGE` punta a `build-<sha>` e il codespace è stato riavviato (runbook `deploy/vetrina/README.md`, «Aggiornare l'immagine»). Se la vetrina è spenta, il copione la accende dal Demo Hub (CL-HUB-002).
- Il livello 1 è verde sulla candidata: `e2e-pr` e `e2e-nightly` non sono rossi (ADR-052 decisione 1). Una major release non si tagga con il cancello rosso.
- La rete dell'ambiente cloud ammette `*.app.github.dev` (Q-682, aggiunta dal proprietario il 2026-10-02) e `*.vercel.app` per la demo.

## Percorso principale: una sessione cloud di Claude

Il proprietario apre una sessione cloud di Claude in un thread del progetto e incolla il prompt qui sotto, con la versione e la candidata. L'agente accende o trova accesa la vetrina, guida Chromium senza interfaccia (Playwright, già nell'ambiente) seguendo il copione con le sole credenziali di test pubbliche, raccoglie console, rete e screenshot e apre una pull request con il rapporto. Il proprietario non tiene aperto nulla.

```text
Esegui il collaudo di release di Loyalty Hub per la versione <vX.0.0>, candidata <build-sha>.

Leggi prima CLAUDE.md, e2e/collaudo/AVVIO.md, e2e/collaudo/copione.md, e2e/collaudo/rapporto-modello.md e il runbook della vetrina (deploy/vetrina/README.md): da lì prendi utenti di test, password, seme OTP e indirizzi. Non ricopiare mai le credenziali nei file, nelle issue o nei messaggi.

Regole:
1. Lavora da sessione cloud headless: Chromium senza interfaccia con Playwright. Se la rete non raggiunge `*.app.github.dev` (risposta 403 del proxy), fermati, scrivi nel rapporto «non eseguibile da qui» e proponi il percorso alternativo con Claude in Chrome (e2e/collaudo/AVVIO.md).
2. Esegui TUTTI i passi di copione.md in ordine, area per area (HUB, BO, PT, KC, AUD). Per ogni passo usa l'utente indicato, in un contesto del browser nuovo, e raccogli le evidenze richieste: screenshot, URL, errori di console, richieste di rete fallite o 4xx/5xx inattesi, voci di audit. Non registrare mai cookie, token, intestazioni Authorization né corpi di richieste.
3. Esito per passo: OK, KO, BLOCCATO oppure N/A, con una riga di motivo se non è OK. Un KO non ferma il collaudo.
4. Solo dati fittizi. Nelle console di Keycloak non modificare nulla. Non inviare azioni oltre quelle del copione. Non toccare nulla fuori dalla vetrina e dalla demo pubblica.
5. Compila e2e/collaudo/rapporto-modello.md come e2e/collaudo/rapporti/<vX.0.0>.md (esito per passo, evidenze, difetti, verdetto). Gli screenshot, ridotti e senza dati sensibili, vanno in e2e/collaudo/rapporti/<vX.0.0>/<ID>-<n>.png. (SPEC-GAP: Q-714, in uso finché il proprietario non decide.)
6. Per ogni KO apri una issue GitHub con l'etichetta `collaudo` (creala se manca), titolo «[CL-…] sintesi», con l'ID del passo, la candidata, i passi per riprodurlo, l'esito atteso e osservato e le evidenze. Prima cerca se esiste già una issue aperta per lo stesso passo e aggiungi un commento invece di duplicarla. Cita le issue nel rapporto.
7. Crea il ramo `fase2/M9.6-rapporto-collaudo-<vX.0.0>` da origin/main aggiornato e apri UNA pull request verso main intitolata `test(collaudo): rapporto <vX.0.0> [F2-QA-06, ADR-052, Q-682]` con il modello .github/pull_request_template.md. Niente push su main, niente force push, nessuna modifica fuori da e2e/collaudo/rapporti/.
8. Il verdetto è «Nessun blocco», «Blocchi» (con l'elenco dei KO che il proprietario deve guardare prima del tag) oppure «Non eseguibile». La decisione di rilasciare non è tua.

Alla fine rispondi con il link della pull request, il verdetto, il conteggio degli esiti e le issue aperte.
```

### Cosa fa l'agente, in breve

```text
1. accende o trova accesa la vetrina (CL-HUB-001…006)
2. esegue il copione per area, un contesto per utente
3. raccoglie evidenze per passo
4. scrive rapporti/<vX.0.0>.md e apre la PR del rapporto
5. apre una issue `collaudo` per ogni difetto
```

## Percorso alternativo: Claude in Chrome nella scheda del proprietario

Si usa quando il proprietario vuole guardare il collaudo, oppure quando la rete cloud non raggiunge la vetrina.

1. Apri una scheda con la demo pubblica e avvia Claude in Chrome dall'estensione.
2. Incolla lo stesso prompt, aggiungendo: «Lavora nella mia scheda di Chrome. Per ogni utente apri una finestra in incognito, perché Keycloak non cambia utente sopra una sessione aperta».
3. L'estensione legge DOM, console e rete della sola scheda: i campi «errori di console» e «richieste di rete» possono essere meno completi. Scrivilo nel rapporto.
4. Se l'agente non può aprire una pull request, incolla il rapporto compilato in un thread del progetto: lo porterà nel repository una sessione cloud.

## Difetti: da issue a test

Ogni KO diventa una issue GitHub con l'etichetta `collaudo` e l'ID del passo (`CL-…`) nel titolo (Q-685). La issue si chiude solo con **un test deterministico che riproduce il difetto** (una journey Playwright in `e2e/tests/` o una riga del testbook di `docs/16`) e con la correzione. Così il collaudo esplorativo alimenta il cancello del livello 1 a ogni release.

## Dopo il collaudo

1. Il proprietario legge la pull request del rapporto e decide il rilascio.
2. I rapporti si conservano in `e2e/collaudo/rapporti/` (uno per release) e non si modificano a posteriori.
3. Se la vetrina non serve più, il codespace si ferma da solo dopo l'inattività.
