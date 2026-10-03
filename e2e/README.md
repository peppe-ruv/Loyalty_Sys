# e2e — harness Playwright e Cypress

Cancello end-to-end di Loyalty Hub (ADR-052 livello 1, M9.1, F2-QA-01). Un browser vero (Chromium) fa il login OIDC reale del backoffice, con la MFA del realm, sullo stesso stack del job CI `smoke enterprise (compose, OIDC)`: compose di riferimento in profilo `enterprise`, proxy TLS di prova, overlay di test del realm.

Pacchetto a sé (pnpm, con il suo `pnpm-lock.yaml`): non c'è un workspace radice, quindi si lancia da `e2e/` o con `pnpm --dir e2e`.

## Prove

| File | Cosa verifica |
| --- | --- |
| `tests/accesso-operatore.setup.ts` | `marta.admin` entra dal login di Keycloak con cambio della password temporanea, OTP e arriva alla dashboard (BO-01). Salva la sessione per le altre prove. |
| `tests/senza-sessione.spec.ts` | Senza sessione le API del BFF rispondono `401 UNAUTHENTICATED` e `/backoffice` porta al login (client `web`, PKCE S256). |
| `tests/audit-configurazione.spec.ts` | Una scrittura di configurazione lascia una voce in `GET /v1/audit` con l'attore reale (regola 21), visibile anche nella schermata Audit. |
| `tests/codice-errore.spec.ts` | Un premio inesistente dà un 404 del servizio: il riquadro d'errore mostra «Codice dell'errore», uguale all'intestazione `X-Correlation-Id` e alla proprietà `correlationId` della risposta (F2-QA-06, Q-716). |
| `tests/membro-backoffice.spec.ts` | `testmember` non apre il backoffice né le sue API (rifiutato dal BFF, oppure `403 FORBIDDEN_ROLE`). |
| `tests/journey-casuali.spec.ts` | 20 journey casuali con seme (500 di notte): sequenze di azioni dalle API vere e invarianti di M9 dopo ogni ciclo (ADR-053, Q-693). Vedi «Journey casuali con invarianti». |
| `tests/journey-regressioni.spec.ts` | Rigioca ogni `journeys/regressioni/*.json`: i semi che hanno fallito diventano journey fisse. Cartella senza file = nessun test. |

La scrittura di configurazione della terza prova è «crea categoria premi». Il backoffice non ha una schermata per crearla, quindi parte dal contesto della pagina autenticata, dal proxy del BFF e con il token CSRF, come le chiamate del frontend. La verifica dell'audit è invece anche nell'interfaccia.

## Come si lancia in locale

Servono Docker, Node 22, pnpm 10, l'immagine unica e i nomi `web.lh.test` e `idp.lh.test` verso `127.0.0.1` in `/etc/hosts`.

```bash
docker build -f deploy/image/Dockerfile -t lh-image:ci .
echo "127.0.0.1 web.lh.test idp.lh.test" | sudo tee -a /etc/hosts
bash scripts/smoke-enterprise.sh up        # segreti, TLS di prova, stack, overlay del realm
cd e2e && pnpm install --frozen-lockfile
pnpm exec playwright install chromium      # una volta sola
pnpm test
bash ../scripts/smoke-enterprise.sh down   # smonta e cancella segreti e certificati
```

Il primo accesso dell'operatore cambia la sua password e configura l'OTP: dopo una corsa lo stack va ricreato (`down` poi `up`) prima di rilanciare i test. Il report HTML è in `playwright-report/`.

## Variabili d'ambiente

| Variabile | Predefinito | Significato |
| --- | --- | --- |
| `LH_E2E_WEB_URL` | `https://web.lh.test:8443` | Origine del web (BFF). |
| `LH_CI_DIR` | `$RUNNER_TEMP/lh-smoke-enterprise` o `/tmp/lh-smoke-enterprise` | Cartella dello smoke con `secrets/` e `tls/`. |
| `LH_E2E_OPERATOR`, `LH_E2E_MEMBER` | `marta.admin`, `testmember` | Utenti di prova. |
| `LH_E2E_OPERATOR_CREDENTIALS`, `LH_E2E_MEMBER_CREDENTIALS` | `<LH_CI_DIR>/secrets/operatori.txt`, `membro.txt` | File `utente: password` con permessi 0600. |
| `LH_E2E_SERVER_CERT` | `<LH_CI_DIR>/tls/server.crt` | Certificato del proxy di prova per la fiducia TLS. |
| `LH_JOURNEY_SEED` | casuale | Seme principale delle journey casuali (intero da 1 a 4294967295). Lo sceglie `playwright.config.ts` una volta sola per tutti i processi; in CI lo sceglie il workflow prima della corsa. |
| `LH_JOURNEY_COUNT` | `20` | Quante journey casuali (da 1 a 5000). |
| `LH_JOURNEY_STEPS` | `8` | Azioni per journey (da 1 a 200); i checkpoint si aggiungono. |
| `LH_JOURNEY_BUDGET_MIN` | assente | Tetto di minuti per la sequenza delle journey (solo notte, Q-718): le journey che non partono entro il tetto risultano «non eseguite». |

## Scelte di sicurezza

- **Credenziali.** Non stanno nel codice, negli argomenti o nei log: si leggono dai file 0600 dello smoke (stesso formato di `deploy/idp/bootstrap.sh`). La nuova password dell'operatore è casuale e resta in memoria; il seme OTP si legge dalla pagina di Keycloak e il codice si calcola nel processo. Gli errori del login non riportano mai i valori digitati.
- **Trace, video e screenshot spenti** dove si digita una password o si vede il segreto OTP: il progetto `setup` e la prova del membro. Una traccia registra il corpo delle richieste. In caso di errore del login la pagina viene svuotata prima dell'istantanea.
- **Sessione dell'operatore** (cookie) in una cartella `0700` di `os.tmpdir()`, fuori da `test-results/` e da `playwright-report/`; la cancella `global-teardown.ts`.
- **TLS di prova.** Chromium non legge `NODE_EXTRA_CA_CERTS`. Il browser accetta solo la chiave del certificato del proxy di prova (`--ignore-certificate-errors-spki-list` con l'impronta SPKI di `server.crt`): ogni altro certificato resta rifiutato e la verifica non si disattiva in generale. Le chiamate HTTP delle prove partono dal browser, quindi Node non deve fidarsi di nessuna CA.
- **Nessun retry** (`retries: 0`): un test rosso non si nasconde. Un solo worker, perché le prove condividono l'operatore.

## Journey casuali con invarianti

ADR-053 decisione 4 e Q-693 (M9.7d, F2-QA-01, F2-QA-07): oltre alle prove a esempi, ogni pull request esegue 20 journey generate a caso da un seme, e il workflow `e2e-nightly` ne esegue 500 di notte. Una journey è una sequenza di passi dati (JSON) fatti con le API vere dal proxy del BFF, dalla sessione dell'operatore; dopo ogni ciclo, cioè a ogni `checkpoint` (ogni 3-5 azioni e alla fine), si verificano le invarianti.

| File | Ruolo |
| --- | --- |
| `journeys/generator.ts` | Generatore puro e deterministico (mulberry32 scritto qui, nessuna dipendenza): stesso seme, stessi passi. Validatore dei file di regressione. |
| `journeys/runner.ts` | Esegue i passi dal contesto della pagina autenticata (`lib/bff.ts`) e verifica le invarianti. Gli errori riportano solo stato HTTP e codice breve, mai corpi di risposta. |
| `journeys/seed.ts`, `journeys/riepilogo.mjs` | Seme casuale; riepilogo Markdown per `$GITHUB_STEP_SUMMARY`. |
| `journeys/regressioni/` | Una journey fissa per ogni seme che ha fallito (formato nel suo `README.md`). |
| `journeys/generator.test.ts`, `journeys/runner.mock.spec.ts` | Prove senza stack: `pnpm test:unit` (determinismo, validità, regressioni) e `pnpm test:mock` (runner e invarianti contro uno stack simulato, con un guasto per ogni invariante). |

**Azioni.** Quelle che un `ADMIN` può fare dalle API pubbliche di `contracts/api/` (Q-719): registrare un membro (`POST /v1/members`), rettificare il saldo in accredito o addebito e tentare un addebito oltre il saldo, che il servizio rifiuta (`POST /v1/wallets/{id}/adjustments`), leggere il portafoglio, creare e rinominare una categoria premi, creare un premio in bozza (con la fascia della journey) e cambiarne lo stock. Ogni codice creato porta il tag del seme (`J<seme in base 36>`), così le journey non si pestano i piedi. I membri di prova hanno indirizzi `@example.test` e nome «Prova»: nessun dato personale.

**Invarianti dopo ogni ciclo** (Q-717):

| Invariante | Dove si legge |
| --- | --- |
| Σ lotti = saldo (attivo e in attesa), e il saldo è quello del modello della journey | `GET /v1/wallets/{id}`, `GET /v1/wallets/{id}/lots` |
| Σ mesi di scadenza = in circolazione, e il totale copre i saldi dei membri della journey | `GET /v1/liability` |
| Stock ≤ totale (su tutto il catalogo) e stock dei premi della journey come impostato | `GET /v1/rewards` |
| Nessun doppione nel registro dei messaggi: (membro, evento di origine, modello) è univoco | `GET /v1/messages?memberId=` |
| Una voce di audit per ogni scrittura riuscita, con l'attore reale (regola 21) | `GET /v1/audit` |
| Nessuna voce nuova in DLQ durante la journey (la parte «senza DLQ» di `contest.won`) | `GET /v1/dlq` |

Non verificabili oggi, con `// SPEC-GAP: Q-717` nel runner: il tracciato completo di ogni `contest.won` (nessuna scheda definisce «completo»), la somma dei lotti su tutti i wallet (nessun elenco globale) e l'inbox dal lato del membro (serve il suo token). Riscatti, concorsi e accrediti da campagna non sono azioni delle journey finché non ci sono un secondo operatore e un membro con il suo token (Q-719).

**Seme e regressioni.** Seme principale e passi di ogni journey sono sempre stampati (e allegati al report come `journey.json`); non contengono segreti né dati personali. Un seme che fallisce scrive `journeys/regressioni/<seme>.json`; il job lo carica come artefatto e lo elenca nel riepilogo. Per farne una journey fissa si committa il file in una pull request insieme alla correzione.

```bash
# Prove senza stack: generatore e runner contro uno stack simulato
cd e2e && pnpm test:unit && pnpm test:mock
# Journey casuali sullo stack (dopo `bash scripts/smoke-enterprise.sh up`), con seme, numero e azioni
LH_JOURNEY_SEED=42 LH_JOURNEY_COUNT=5 LH_JOURNEY_STEPS=8 pnpm exec playwright test tests/journey-casuali.spec.ts
```

**In CI.** Nel job `e2e-pr` le 20 journey fanno parte del passo «Journey Playwright» (tetto di 15 minuti, Q-694: se non stanno si riducono le azioni per journey, mai si salta il controllo); il seme è scelto prima della corsa e scritto nel riepilogo del job. Il workflow `.github/workflows/e2e-nightly.yml` parte alle 01:17 UTC e a richiesta (`workflow_dispatch` con `seed` e `count` facoltativi, validati e passati dall'ambiente), alza lo stesso stack, esegue 500 journey da 12 azioni con un tetto di 55 minuti (Q-718), con permessi di sola lettura. In caso di errore carica il report (`e2e-nightly-report-<run_id>`) e i semi falliti (`e2e-nightly-seed-<run_id>`). Dopo dieci test falliti in CI la corsa si ferma (`maxFailures`).

## Matrice

Oggi c'è il solo progetto `desktop-chromium`. La matrice di browser e viewport è M9.3.

## Cypress sulla demo pubblica

Nello stesso pacchetto c'è anche Cypress (ADR-054, Q-710…Q-713, M9.8a, F2-QA-08). I due strumenti non si sovrappongono: **Playwright** verifica il profilo `enterprise` nelle PR (login vero, MFA, invarianti); **Cypress** verifica il profilo `demo` sulla demo pubblica su Vercel, con le persone simulate (`X-LH-Actor`, `memberId`), senza login. Non gira nelle PR: la demo pubblica è quella in produzione, non la build della PR.

| File | Cosa verifica |
| --- | --- |
| `cypress/e2e/01-demo-hub.cy.ts` | Demo Hub: «Accendi la demo» e attesa del risveglio (`pronti 10/10`), le 10 tessere UP, gli ingressi (5 persone del backoffice, i membri del portale), l'ingresso come Luca e come Marco. |
| `cypress/e2e/02-backoffice.cy.ts` | Per ognuno dei 5 ruoli (ADMIN, MARKETING, LEGAL, CARE, ANALYST): guscio e menu, e azioni abilitate o disabilitate secondo la matrice di `docs/08 §2`. Poi le schermate principali: dashboard, membri, scheda membro, campagne, catalogo premi, audit, livelli. Nota: tutte le persone leggono tutto, quindi il menu è lo stesso per ogni ruolo; cambiano le azioni. |
| `cypress/e2e/03-portale-membri.cy.ts` | Portale come Anna, Marco e Giulia (`MBR-000001…3`): home con saldo e livello, catalogo per fasce, attività, cambio membro dal pannello demo. Parte dai dati di `seed/` (reset in `before`). |
| `cypress/e2e/04-scenari-e-premi.cy.ts` | `SCN-SMOKE` dal menu Demo (+5 punti a Marco entro 15 s), il movimento nel portale, il simulatore eventi, il premio che Anna non può chiedere e il riscatto di «Pianta un albero» (saldo −500). |
| `cypress/e2e/05-reset-demo.cy.ts` | Reset della demo dalla Console demo («Ripristina tutto», parola `RESET`) e ritorno ai saldi di `seed/`. |

Al più 50 test per corsa e `retries: 0` (Q-710, Q-711): il piano gratuito di Cypress Cloud conta i risultati registrati (500 al mese). Selettori per ruolo, etichetta e testo che esistono nel codice del web, nessuna attesa fissa: il risveglio dei servizi gratuiti si aspetta con un sondaggio e tempi larghi.

### Come si lancia

```bash
cd e2e && pnpm install --frozen-lockfile     # scarica anche il binario di Cypress
pnpm cy:run                                  # tutte le prove contro la demo pubblica
pnpm cy:run --spec cypress/e2e/03-portale-membri.cy.ts
pnpm cy:open                                 # interfaccia interattiva
LH_DEMO_URL=http://localhost:3000 pnpm cy:run   # contro un'altra istanza in profilo demo
pnpm typecheck                               # Playwright e Cypress (tsconfig separati)
```

Le prove scrivono dati fittizi nella demo e chiudono con il reset; se una corsa si interrompe a metà, si ripristina dalla Console demo (`/backoffice/demo/console`, ruolo ADMIN).

### Come non disturba Playwright

- `cypress.config.ts` e `cypress/` hanno un `tsconfig` proprio (`cypress/tsconfig.json`, tipi `cypress`) e sono esclusi da `tsconfig.json`: i due `expect` globali non si incontrano.
- `testDir` di Playwright è `./tests`, fuori da `cypress/`.
- Script separati: `test` (Playwright), `cy:run`, `cy:open`; `typecheck` controlla entrambi.
- Il job `e2e-pr` installa le dipendenze con `CYPRESS_INSTALL_BINARY=0`: il binario di Cypress non si scarica nelle PR.

### Workflow `cypress-demo` e Cypress Cloud

`.github/workflows/cypress-demo.yml` parte ogni cinque giorni (`cron: 0 3 */5 * *`, giorni 1, 6, 11, 16, 21, 26 e 31) e a richiesta (`workflow_dispatch`, con un indirizzo facoltativo), con permessi di sola lettura. Il progetto su Cypress Cloud è `v1g3bz` (`projectId` in `cypress.config.ts`).

La **record key** non sta in nessun file (regola 20, Q-712): solo nel segreto del repository `CYPRESS_RECORD_KEY`, che imposta il proprietario da Settings → Secrets and variables → Actions. Il workflow aggiunge `--record` solo se il segreto c'è; altrimenti scrive un avviso nel riepilogo ed esegue senza registrare. Il riepilogo riporta quanti test sono stati eseguiti e se la corsa è stata registrata. Le schermate dei test falliti si allegano come artefatto.

| Variabile | Predefinito | Significato |
| --- | --- | --- |
| `LH_DEMO_URL` | `https://loyalty-hub-web.vercel.app` | Origine della demo da provare (profilo `demo`). |
| `CYPRESS_RECORD_KEY` | assente | Solo da segreto del repository; abilita `--record`. |

## Workflow `collaudo` e log dei container

`.github/workflows/collaudo.yml` (ADR-052 decisione 3, Q-683) parte a mano (`workflow_dispatch`) e sui tag `v*.0.0`, con permessi di sola lettura. Alza lo stesso stack del job `e2e-pr` (compose `enterprise` con l'overlay di test del realm, `scripts/smoke-enterprise.sh up`), esegue `pnpm test` e, anche se le journey falliscono, salva i log di ogni container con `bash scripts/smoke-enterprise.sh dump DIR` (`docker compose logs --timestamps`, un file `<servizio>.log` per container).

Prima del caricamento gitleaks controlla la cartella dei log (`gitleaks dir`, configurazione `.gitleaks.toml`, `--redact`). Se segnala qualcosa i log non si caricano e il job fallisce (regola 20). Altrimenti si caricano come artefatto `collaudo-log-<run_id>` (14 giorni), accanto al report Playwright `collaudo-report-<run_id>`. Il riepilogo del job riporta l'esito delle journey, il numero di container, l'artefatto e il risultato di gitleaks. I log vengono dallo stack di CI, mai dalla vetrina.

## Collaudo di release con un agente nel browser

La cartella `collaudo/` è il livello 2 della strategia di test (ADR-052 decisione 2, Q-680…Q-685, M9 T2, F2-QA-06): non è un test automatico e non è un cancello. Prima di ogni major release un agente nel browser esegue un copione sulla **vetrina enterprise** con le sole credenziali di test pubbliche, e scrive un rapporto. Il collaudo è consultivo: il proprietario decide il rilascio.

| File | A cosa serve |
| --- | --- |
| `collaudo/copione.md` | Circa quaranta passi con ID `CL-<AREA>-NNN` in cinque aree: `HUB` (accensione e riquadro Enterprise), `BO` (un operatore per ruolo), `PT` (Anna, Marco, Giulia), `KC` (console dei due realm, `master` chiuso), `AUD` (ogni scrittura di configurazione in Audit con l'attore reale). Ogni passo ha utente di test, precondizioni, azioni, esito atteso, evidenze e ID di specifica. Le credenziali non sono nel copione: stanno nel runbook `deploy/vetrina/README.md`. |
| `collaudo/AVVIO.md` | Quando si esegue, prerequisiti, il prompt di avvio per una sessione cloud di Claude (Chromium senza interfaccia) e il percorso alternativo con Claude in Chrome. |
| `collaudo/rapporto-modello.md` | Modello del rapporto: esito per passo (`OK`, `KO`, `BLOCCATO`, `N/A`), evidenze, difetti e verdetto. Ogni rapporto è un file `collaudo/rapporti/<vX.0.0>.md` portato nel repository con una pull request. |

Ogni difetto è una issue GitHub con l'etichetta `collaudo` e l'ID del passo; si chiude solo con un test deterministico che lo riproduce (una journey Playwright in `tests/` o una riga del testbook) e con la correzione, così il collaudo alimenta il cancello (Q-685).

```bash
# Controllo di forma del copione: ID unici, campi obbligatori, ID di specifica esistenti,
# scritture di configurazione coperte da un passo di audit, nessuna credenziale ricopiata
node --test scripts/check-collaudo.test.mjs && node scripts/check-collaudo.mjs
```

Lo stesso controllo gira nel job `guard` di `ci.yml`. Il workflow `collaudo` (livello 3) è un'altra cosa: alza lo stack di CI e conserva i log dei container; il collaudo del livello 2 gira invece sulla vetrina.
