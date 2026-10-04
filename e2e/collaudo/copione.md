# Copione di collaudo di release

Copione del livello 2 della strategia di test (ADR-052 decisione 2, Q-680, F2-QA-06): un agente nel browser lo esegue sulla **vetrina enterprise** prima di ogni major release (Q-681) e scrive un rapporto (`rapporto-modello.md`, Q-682). Il collaudo è **consultivo**: il proprietario decide il rilascio. Ogni difetto diventa una issue GitHub con l'etichetta `collaudo` (Q-685).

Come si avvia: `AVVIO.md`. Come si compila il rapporto: `rapporto-modello.md`. Il controllo di forma di questo file è `scripts/check-collaudo.mjs` (job `guard`).

## Convenzioni

**Indirizzi.** `<DEMO>` è la demo pubblica su Vercel (`https://loyalty-hub-web.vercel.app`). `<VETRINA>` è `https://<codespace>-8000.app.github.dev` (web, backoffice e portale) e `<IDP>` è `https://<codespace>-8001.app.github.dev` (Keycloak, solo i due realm di vetrina). Il nome del codespace lo mostra HUB-01 quando apre la vetrina.

**Credenziali.** Gli utenti di test, le password, il seme dell'OTP e le password delle console sono **pubblici** e stanno nel runbook della vetrina: `deploy/vetrina/README.md`, sezione «Utenti di test» (stessa tabella nella pagina Mintlify «Vetrina enterprise»). Questo copione non le ricopia: leggile da lì e non usare mai credenziali di un'altra installazione. Non scrivere password, seme OTP, cookie o token nel rapporto, nelle issue o nei log.

**Accesso come operatore** (vale per ogni passo con un operatore):
1. Apri HUB-02 su `<VETRINA>`, scheda dell'operatore, «Entra come <Nome>»: il login del realm `loyaltyhub` arriva con il nome utente già scritto.
2. Scrivi la password del runbook.
3. Scrivi il codice OTP: lo mostra la scheda dell'operatore in HUB-02 (codice del momento), oppure lo calcoli dal seme del runbook (TOTP SHA-1, 6 cifre, 30 secondi). Se Keycloak rifiuta un codice appena usato, aspetta il successivo.
4. Atterri sul backoffice con il ruolo dell'operatore.

**Accesso come membro**: come sopra dalla scheda del membro, sul realm `loyaltyhub-members`, senza OTP; atterri sul portale.

**Una sessione per utente.** Keycloak non autentica un altro utente sopra una sessione SSO aperta: usa un contesto del browser nuovo per ogni utente (o esci prima con «Esci per entrare come…»).

**Esiti.** Ogni passo ha un esito: `OK`, `KO` (l'esito atteso non si verifica: è un difetto), `BLOCCATO` (una precondizione manca: scrivi quale) oppure `N/A` (il passo non si applica, per esempio vetrina già accesa in CL-HUB-002: scrivi perché). Un `KO` non ferma il collaudo: prosegui con i passi che non ne dipendono.

**Evidenze.** Per ogni passo: almeno uno screenshot (`<ID>-<n>.png`), gli URL visitati, gli errori della console, le richieste di rete fallite o con stato `4xx/5xx` non attesi e, dove richiesto, la voce di audit (identificativo, attore, ruolo, azione, oggetto). Non registrare mai il corpo delle richieste né le intestazioni `Cookie` e `Authorization`.

**Dati.** Solo dati fittizi. Non creare né modificare utenti e non inviare azioni oltre quelle indicate: il collaudo lascia la vetrina utilizzabile da chi viene dopo. Le modifiche fatte nelle console di Keycloak spariscono al riavvio (ADR-051 decisione 10).

**Ordine.** I passi di un'area sono in ordine: i successivi possono dipendere dai precedenti (le precondizioni li citano). L'ordine delle aree è HUB, BO, PT, KC, AUD: le scritture dei passi BO alimentano i passi PT e AUD.

**Scrittura di configurazione.** I passi che scrivono configurazione dichiarano `Scrittura di configurazione: sì` e ogni passo con `sì` è verificato da un passo AUD (regola 21 di CLAUDE.md, F-AUD-01).

## Area HUB — accensione della vetrina e riquadro Enterprise sulla demo

### CL-HUB-001 — Il riquadro «Modalità Enterprise» sulla demo pubblica

- **Utente di test**: nessuno (visitatore anonimo della demo `<DEMO>`).
- **Precondizioni**: la demo risponde (se i servizi dormono usa «Accendi la demo» e attendi «pronti 10/10»).
- **Azioni**:
  1. Apri `<DEMO>/`.
  2. Individua il riquadro «Modalità Enterprise» accanto al pannello di stato.
  3. Leggi titolo, pillola di stato, i tre passi (accendi, scegli un utente di test, entra) e il collegamento «Guida e credenziali su Mintlify».
- **Esito atteso**: il riquadro c'è, con uno stato tra *spenta*, *in accensione*, *accesa* oppure la nota «Disponibile su richiesta» se il pulsante non è configurato (in quel caso segna `BLOCCATO` per CL-HUB-002 e riporta la nota). Nessun errore in console. Su telefono (390 px) il riquadro si impila sotto il pannello di stato e la pagina non scorre in orizzontale.
- **Evidenze**: screenshot a 1280 px e a 390 px, testo della pillola di stato, errori di console.
- **Specifiche**: `HUB-01`, `F2-DIST-09`, `ADR-051`, `Q-662`.

### CL-HUB-002 — Accendere la vetrina dal browser

- **Utente di test**: nessuno (visitatore anonimo).
- **Precondizioni**: CL-HUB-001 con pulsante configurato. Se la vetrina è già accesa («Apri la vetrina» visibile) segna `N/A` e passa a CL-HUB-003: l'accensione consuma le ore gratuite di Codespaces del proprietario.
- **Azioni**:
  1. Fai clic su «Accendi la modalità Enterprise».
  2. Osserva la pillola e la barra con il tempo trascorso; non premere di nuovo il pulsante (è ammesso un avvio al minuto).
  3. Attendi fino a 10 minuti che compaia «Apri la vetrina».
- **Esito atteso**: lo stato passa da *spenta* a *in accensione* (pulsante disabilitato, barra, annuncio in `aria-live`) e, dopo 3–5 minuti, ad *accesa* con «Apri la vetrina». Nessuno stato d'errore; se compare «Riprova», rifallo una volta e annota la risposta di `/api/vetrina/codespace`.
- **Evidenze**: screenshot dei tre stati, tempo impiegato, risposta di `GET <DEMO>/api/vetrina/codespace` (solo `state` e `url`).
- **Specifiche**: `HUB-01`, `ADR-051`, `ADR-050`, `Q-674`.

### CL-HUB-003 — La guida collegata dal riquadro

- **Utente di test**: nessuno.
- **Precondizioni**: CL-HUB-001.
- **Azioni**:
  1. Fai clic su «Guida e credenziali su Mintlify».
  2. Cerca nella pagina la tabella degli utenti di test e le due console di Keycloak.
- **Esito atteso**: la pagina si apre (non `404`) e descrive utenti di test, OTP e console, con l'avviso «ambiente di test, dati fittizi».
- **Evidenze**: URL finale, screenshot della sezione degli utenti di test.
- **Specifiche**: `HUB-01`, `ADR-051`.

### CL-HUB-004 — HUB-02: la pagina iniziale della vetrina

- **Utente di test**: nessuno (visitatore anonimo della vetrina).
- **Precondizioni**: CL-HUB-002 (vetrina accesa). Al primo accesso GitHub può mostrare una pagina di conferma per la porta pubblica: confermala e annotalo.
- **Azioni**:
  1. Da «Apri la vetrina» apri `<VETRINA>/`.
  2. Se compare lo stato degradato, attendi fino a 90 secondi (riprova automatica ogni 5 secondi).
  3. Leggi i banner e le tessere di stato.
  4. Fai clic su «Torna alla demo».
- **Esito atteso**: HUB-02 mostra il banner «non HA, solo dati fittizi, accesa su richiesta» e il banner «Ambiente di test: credenziali pubbliche»; le tessere `hub`, `web`, `idp`, Postgres e Kafka sono in verde e `cms` dice «non installato». Non ci sono persone né «Accendi la demo». «Torna alla demo» porta a `<DEMO>`.
- **Evidenze**: screenshot di HUB-02 con le tessere, errori di console, tempo di arrivo allo stato verde.
- **Specifiche**: `HUB-02`, `F2-DIST-09`, `Q-624`, `ADR-051`.

### CL-HUB-005 — HUB-02: schede degli utenti di test

- **Utente di test**: nessuno.
- **Precondizioni**: CL-HUB-004.
- **Azioni**:
  1. Scorri le tre sezioni: Operatori, Membri, Console di Keycloak.
  2. Per un operatore guarda il codice OTP, il conto alla rovescia e il pulsante «Copia».
  3. Attendi il cambio del codice.
  4. Confronta i nomi utente con la tabella del runbook.
- **Esito atteso**: cinque schede di operatori (`marta.admin` ADMIN, `luca.marketing` MARKETING, `elena.legal` LEGAL, `paolo.care` CARE, `sara.analyst` ANALYST), quattro di membri (`anna.rossi`, `marco.bianchi`, `giulia.ferri` registrati; `laura.conti` «da registrare») e le due console; ogni scheda ha il pulsante «Entra come…». Il codice OTP è di 6 cifre, cambia ogni 30 secondi e il conto alla rovescia scende. Nella sezione delle console non c'è nessun collegamento al realm `master`.
- **Evidenze**: screenshot delle tre sezioni, due codici OTP consecutivi (non il seme), nomi utente trovati.
- **Specifiche**: `HUB-02`, `ADR-051`, `Q-673`, `Q-676`.

### CL-HUB-006 — La demo non espone ciò che è solo della vetrina

- **Utente di test**: nessuno.
- **Precondizioni**: CL-HUB-001.
- **Azioni**:
  1. Apri `<DEMO>/api/vetrina/totp`.
  2. Apri `<DEMO>/api/vetrina/programma`.
  3. Apri `<DEMO>/api/vetrina/codespace` e leggi le chiavi della risposta.
- **Esito atteso**: le prime due rispondono `404`. La terza, se la route esiste, contiene solo `state` e `url`: nessun token, nome del codespace in chiaro oltre all'URL pubblico o corpo grezzo di GitHub.
- **Evidenze**: stato HTTP e corpo delle tre risposte (nessuna intestazione).
- **Specifiche**: `HUB-01`, `F2-DIST-09`, `ADR-051`, `Q-674`.

## Area BO — backoffice, un operatore per ruolo

La matrice dei permessi è in `docs/08 §2` (`<Can capability>` nell'interfaccia, `@RequiresRole` nel backend). Dove un'azione è «negata» verifica l'interfaccia (nascosta o disabilitata con tooltip che indica il ruolo richiesto): se il browser lo permette senza trucchi, tenta anche la chiamata e aspetta `403 FORBIDDEN_ROLE`.

### CL-BO-001 — marta.admin: accesso con OTP e guscio del backoffice

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Esegui «Accesso come operatore» (convenzioni).
  2. Sulla dashboard guarda la sidebar, la barra alta e il riquadro «Carica il programma di esempio».
  3. Nel gruppo Clienti cerca la voce «Utenti membri».
- **Esito atteso**: atterri su BO-01 con il ruolo ADMIN. La sidebar ha i gruppi Panoramica, Clienti, Programma, Premi, Gioco, Contenuti, Governance, Osservabilità; il gruppo Demo non c'è. Il riquadro del programma di esempio è visibile. «Utenti membri» è visibile e apre in una nuova scheda.
- **Evidenze**: screenshot della dashboard e della sidebar, URL dopo il login, errori di console.
- **Specifiche**: `BO-01`, `F2-IAM-01`, `F2-SEC-06`, `ADR-051`.

### CL-BO-002 — marta.admin: carica il programma di esempio

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-BO-001. Programma non ancora caricato (altrimenti l'anteprima lo dichiara: segna `N/A` e prosegui, annotando che il resto parte da dati già presenti).
- **Azioni**:
  1. Nel riquadro leggi l'anteprima (cosa si crea, cosa è escluso).
  2. Avvia «Carica il programma di esempio» e segui l'avanzamento fino all'esito.
  3. Apri Campagne (BO-05) e Catalogo premi (BO-10).
- **Esito atteso**: il lavoro termina senza errori. Premi e campagne esistono e sono in `DRAFT` (nessuno è stato sottomesso né approvato). Il riquadro invita un altro operatore (Elena o un altro ADMIN) ad approvare e offre «Carica le storie» solo dopo che le campagne richieste sono `LIVE` («in attesa delle campagne attive»).
- **Evidenze**: screenshot di anteprima, avanzamento ed esito; elenco di campagne e premi con lo stato; errori di console e richieste fallite.
- **Specifiche**: `BO-01`, `F2-DIST-09`, `Q-723`, `ADR-051`.
- **Scrittura di configurazione**: sì.

### CL-BO-003 — marta.admin: sottomette i premi per la revisione

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-BO-002.
- **Azioni**:
  1. Apri il premio `RWD-DONATION-TREE` (BO-10), barra del ciclo di vita, «Invia in revisione», commento, conferma.
  2. Ripeti per `RWD-COFFEE-5`.
  3. Apri Approvazioni (BO-21), scheda «Inviate da me».
- **Esito atteso**: i due premi passano a `IN_REVIEW` e compaiono in «Inviate da me». Se per un premio «Invia in revisione» non è offerto (la policy non richiede approvazione) annota lo stato offerto e usa «Pubblica»: i passi CL-BO-004, CL-BO-007 (parte del premio) e CL-BO-009 per quel premio diventano `N/A`. SPEC-GAP: Q-715 (nessuna scheda servizio dichiara il default della policy di approvazione).
- **Evidenze**: screenshot della barra del ciclo di vita prima e dopo, della scheda «Inviate da me» e dello Storico.
- **Specifiche**: `BO-10`, `BO-21`, `F-APR-01`, `F2-GRC-03`.
- **Scrittura di configurazione**: sì.

### CL-BO-004 — marta.admin: non può approvare ciò che ha sottomesso

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-BO-003.
- **Azioni**:
  1. Da BO-21 apri la sintesi di `RWD-DONATION-TREE`.
  2. Prova «Approva» (se l'interfaccia lo offre come override di ADMIN, conferma).
- **Esito atteso**: l'approvazione è rifiutata con `422 SELF_APPROVAL_FORBIDDEN` (quattro occhi, anche per ADMIN): messaggio leggibile, il premio resta `IN_REVIEW`.
- **Evidenze**: screenshot del messaggio, risposta di rete con stato e codice d'errore, stato del premio dopo il tentativo.
- **Specifiche**: `F2-GRC-03`, `ADR-044`, `BO-21`.

### CL-BO-005 — luca.marketing: accesso con OTP e guscio

- **Utente di test**: `luca.marketing` (MARKETING).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Esegui «Accesso come operatore» in un contesto nuovo.
  2. Guarda la sidebar e il gruppo Clienti.
- **Esito atteso**: atterri su BO-01 come MARKETING. Il riquadro «Carica il programma di esempio» non c'è. «Utenti membri» non è visibile (solo ADMIN e CARE). Il gruppo Demo non c'è.
- **Evidenze**: screenshot della dashboard e della sidebar.
- **Specifiche**: `BO-01`, `F2-IAM-01`, `ADR-051`.

### CL-BO-006 — luca.marketing: sottomette le campagne

- **Utente di test**: `luca.marketing` (MARKETING).
- **Precondizioni**: CL-BO-002, CL-BO-005.
- **Azioni**:
  1. Apri Campagne (BO-05) e individua le campagne che assegnano punti alle azioni delle storie di Marco e Giulia nel programma di esempio: `CMP-PURCHASE-BASE` e quelle degli altri tipi di azione usati (lettura del contatore, accesso all'app, bolletta digitale e simili).
  2. Per ognuna apri l'editor (BO-06) e usa «Invia in revisione».
  3. Apri BO-21, scheda «Inviate da me».
- **Esito atteso**: le campagne passano a `IN_REVIEW` e compaiono in «Inviate da me». Se la policy non richiede approvazione per una campagna, annotalo come in CL-BO-003.
- **Evidenze**: screenshot dell'editor con la barra del ciclo di vita, elenco delle campagne sottomesse con i codici `CMP-…`.
- **Specifiche**: `BO-05`, `BO-06`, `BO-21`, `F-APR-01`.
- **Scrittura di configurazione**: sì.

### CL-BO-007 — luca.marketing: azioni negate

- **Utente di test**: `luca.marketing` (MARKETING).
- **Precondizioni**: CL-BO-003, CL-BO-006.
- **Azioni**:
  1. In BO-21 apri la sintesi di `RWD-COFFEE-5`: cerca «Approva».
  2. Apri Livelli (BO-07): prova a modificare una soglia.
  3. Apri Webhook (BO-23): cerca «Nuovo webhook».
  4. Apri la scheda 360° di un membro (BO-03): cerca «Rettifica punti».
  5. Apri Import (BO-32): cerca «Carica».
- **Esito atteso**: «Approva» non c'è o è disabilitato con «richiede ruolo LEGAL»; i livelli sono in sola lettura; nessun «Nuovo webhook»; nessuna «Rettifica punti»; «Carica» non è disponibile. Nessuna di queste operazioni lascia modifiche.
- **Evidenze**: uno screenshot per ogni azione negata, con tooltip dove c'è; eventuali risposte `403 FORBIDDEN_ROLE`.
- **Specifiche**: `BO-21`, `BO-07`, `BO-23`, `BO-03`, `BO-32`, `F-APR-01`.

### CL-BO-008 — elena.legal: accesso con OTP e guscio

- **Utente di test**: `elena.legal` (LEGAL).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Esegui «Accesso come operatore» in un contesto nuovo.
  2. Guarda sidebar, contatore su Approvazioni e gruppo Clienti.
- **Esito atteso**: atterri su BO-01 come LEGAL; il contatore di Approvazioni mostra gli oggetti `IN_REVIEW` di CL-BO-003 e CL-BO-006; «Utenti membri» non è visibile.
- **Evidenze**: screenshot della sidebar con il contatore.
- **Specifiche**: `BO-01`, `BO-21`, `F2-IAM-01`.

### CL-BO-009 — elena.legal: approva premi e campagne

- **Utente di test**: `elena.legal` (LEGAL).
- **Precondizioni**: CL-BO-003, CL-BO-006, CL-BO-008.
- **Azioni**:
  1. Apri BO-21, scheda «Da approvare».
  2. Per ogni premio e campagna sottomessi leggi il riepilogo e usa «Approva» con un commento.
  3. Verifica lo stato in BO-10 e BO-05.
- **Esito atteso**: ogni oggetto passa a `APPROVED`; lo Storico riporta Elena come autrice e il commento. Nessun errore di quattro occhi, perché chi approva è diverso da chi ha sottomesso.
- **Evidenze**: screenshot di BO-21 prima e dopo, dello Storico di un oggetto, elenco dei codici approvati.
- **Specifiche**: `BO-21`, `F-APR-01`, `F2-GRC-03`.
- **Scrittura di configurazione**: sì.

### CL-BO-010 — elena.legal: azioni negate

- **Utente di test**: `elena.legal` (LEGAL).
- **Precondizioni**: CL-BO-009.
- **Azioni**:
  1. Apri Campagne (BO-05): cerca «Nuova campagna».
  2. Apri una campagna `APPROVED` (BO-06): cerca «Pubblica».
  3. Apri la scheda 360° di un membro: cerca «Rettifica punti».
  4. Apri Import (BO-32): cerca «Invia un'azione» e «Carica».
- **Esito atteso**: nessuna creazione, nessuna pubblicazione (serve `object.edit`: ADMIN o MARKETING), nessuna rettifica e nessun invio di azioni; i controlli sono nascosti o disabilitati con il ruolo richiesto.
- **Evidenze**: screenshot di ciascun controllo negato.
- **Specifiche**: `BO-05`, `BO-06`, `BO-03`, `BO-32`, `F-APR-01`.

### CL-BO-011 — marta.admin: pubblica ciò che è approvato

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-BO-009.
- **Azioni**:
  1. Per ogni premio e campagna `APPROVED` usa «Pubblica» dalla barra del ciclo di vita.
  2. Controlla gli stati in BO-10 e BO-05.
- **Esito atteso**: gli oggetti diventano `LIVE` (o `SCHEDULED` se la data di inizio è futura: segnalalo). Il riquadro «Carica le storie» non dice più «in attesa delle campagne attive».
- **Evidenze**: screenshot degli stati finali, del riquadro delle storie.
- **Specifiche**: `BO-05`, `BO-10`, `F-APR-01`.
- **Scrittura di configurazione**: sì.

### CL-BO-012 — marta.admin: carica le storie dei membri di prova

- **Utente di test**: `marta.admin` (ADMIN).
- **Precondizioni**: CL-BO-011; Anna, Marco e Giulia risultano «registrati» in HUB-02.
- **Azioni**:
  1. Nel riquadro di BO-01 usa «Carica le storie» e segui anteprima, conferma, avanzamento ed esito.
  2. Apri Import (BO-32) e il dettaglio del lavoro.
  3. Apri la scheda 360° di Anna, Marco e Giulia (BO-03), scheda `ledger`.
- **Esito atteso**: l'import della fonte `vetrina-test` termina `DONE` con righe accettate e nessuna non valida. Le storie danno livello BASE ad Anna (0 STS), SILVER a Marco (1.420 STS) e SILVER a Giulia (2.880 STS, 120 sotto GOLD). I PTS non sono quelli di `docs/10 §2` (Q-724): annotali, non sono un difetto. Il movimento compare entro 20 secondi.
- **Evidenze**: screenshot dell'esito e del dettaglio dell'import, dei tre saldi (PTS, STS, livello), note sui PTS.
- **Specifiche**: `BO-01`, `BO-32`, `BO-03`, `F2-ING-02`, `Q-724`.
- **Scrittura di configurazione**: sì.

### CL-BO-013 — paolo.care: accesso con OTP e guscio

- **Utente di test**: `paolo.care` (CARE).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Esegui «Accesso come operatore» in un contesto nuovo.
  2. Guarda la sidebar e il gruppo Clienti.
- **Esito atteso**: atterri su BO-01 come CARE. Il riquadro del programma di esempio non c'è. «Utenti membri» è visibile.
- **Evidenze**: screenshot della dashboard e del gruppo Clienti.
- **Specifiche**: `BO-01`, `F2-IAM-01`, `ADR-051`.

### CL-BO-014 — paolo.care: invia un'azione che dà punti

- **Utente di test**: `paolo.care` (CARE).
- **Precondizioni**: CL-BO-012, CL-BO-013. Giulia ha livello SILVER e 2.880 STS.
- **Azioni**:
  1. Apri Import (BO-32), modulo «Invia un'azione».
  2. Scegli membro Giulia, azione acquisto, importo 130 €, «Invia l'azione».
  3. Attendi l'esito (fino a 20 secondi) e leggi punti e livello.
  4. Apri la scheda 360° di Giulia, scheda `ledger` e `tiers`.
- **Esito atteso**: l'esito mostra l'import accettato, la variazione dei punti letta dal portafoglio (positiva) e la voce di audit. Il livello di Giulia passa a GOLD (130 € superano la soglia, `SCN-TIER-UP`, Q-724); se resta SILVER annota i punti status prima e dopo. Il libro mastro ha il nuovo movimento con la scomposizione.
- **Evidenze**: screenshot dell'esito, del libro mastro e dello storico dei livelli, punti prima e dopo, identificativo della voce di audit.
- **Specifiche**: `BO-32`, `BO-03`, `F2-ING-02`, `F-TIER-02`, `Q-725`.
- **Scrittura di configurazione**: sì.

### CL-BO-015 — paolo.care: azioni negate

- **Utente di test**: `paolo.care` (CARE).
- **Precondizioni**: CL-BO-013.
- **Azioni**:
  1. In BO-01 cerca «Carica il programma di esempio».
  2. In BO-05 e BO-10 cerca la creazione e la pubblicazione di campagne e premi.
  3. Nella scheda 360° di un membro cerca «Anonimizza».
  4. In BO-23 cerca la creazione di un webhook.
  5. In BO-27 cerca «Riprocessa» e «Scarta».
- **Esito atteso**: nessuna di queste azioni è disponibile per CARE (sono di ADMIN o di MARKETING). Resta disponibile «Rettifica punti» (non eseguirla).
- **Evidenze**: screenshot di ogni controllo negato e della «Rettifica punti» disponibile.
- **Specifiche**: `BO-01`, `BO-05`, `BO-10`, `BO-03`, `BO-23`, `BO-27`.

### CL-BO-016 — sara.analyst: accesso con OTP e lettura

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Esegui «Accesso come operatore» in un contesto nuovo.
  2. Apri Dashboard (BO-01), Membri (BO-02), Campagne (BO-05), Catalogo premi (BO-10), Audit (BO-22).
- **Esito atteso**: tutte le schermate si leggono (ANALYST legge tutto); «Utenti membri» non è visibile; nessun errore di console.
- **Evidenze**: screenshot delle cinque schermate, errori di console.
- **Specifiche**: `BO-01`, `BO-02`, `BO-05`, `BO-10`, `BO-22`, `F2-IAM-01`.

### CL-BO-017 — sara.analyst: ogni scrittura è negata

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-BO-016.
- **Azioni**:
  1. In BO-02 cerca «Nuovo membro».
  2. In BO-03 cerca «Rettifica punti».
  3. In BO-05 e BO-10 cerca la creazione e le transizioni del ciclo di vita.
  4. In BO-21 cerca «Approva».
  5. In BO-32 cerca «Invia un'azione» e «Carica».
- **Esito atteso**: nessuna scrittura è offerta o abilitata, e dove si arriva alla chiamata il backend risponde `403 FORBIDDEN_ROLE`. Nessuna modifica ai dati.
- **Evidenze**: screenshot dei controlli negati, eventuali risposte `403`.
- **Specifiche**: `BO-02`, `BO-03`, `BO-05`, `BO-10`, `BO-21`, `BO-32`.

### CL-BO-018 — Uscita, sessione e separazione dei due realm

- **Utente di test**: `marta.admin` (ADMIN), poi un contesto senza sessione.
- **Precondizioni**: CL-BO-001.
- **Azioni**:
  1. Da `marta.admin` apri `<VETRINA>/portal`.
  2. Esci dal backoffice.
  3. In un contesto senza cookie apri `<VETRINA>/backoffice`.
  4. Nello stesso contesto apri `<VETRINA>/api/lh/insight/v1/audit?size=1`.
- **Esito atteso**: con la sessione da operatore il portale dice «Il portale è riservato ai membri». Dopo l'uscita, `/backoffice` porta al login del realm `loyaltyhub`; la chiamata API senza sessione risponde `401 UNAUTHENTICATED`.
- **Evidenze**: screenshot del rifiuto del portale e del login, stato e codice della risposta API.
- **Specifiche**: `F2-SEC-06`, `F2-SEC-09`, `F2-IAM-03`, `ADR-051`.

## Area PT — portale, tre membri

Il membro viene solo dal token: in nessuna richiesta di rete verso `/api/lh/` compare un `memberId` come parametro o nel corpo; i percorsi sono quelli `/v1/portal/me/…`. Verificalo in ogni passo PT (rete) e segna `KO` se lo trovi.

### CL-PT-001 — Anna: accesso e Home di una iscritta di ieri

- **Utente di test**: `anna.rossi` (membro Anna Rossi, `MBR-000001`).
- **Precondizioni**: CL-BO-012.
- **Azioni**:
  1. Esegui «Accesso come membro» in un contesto nuovo.
  2. Sulla Home leggi saluto, tessera, saldo, livello e barra verso il livello successivo.
  3. Apri Attività (PT-07).
- **Esito atteso**: atterri su PT-01 con il nome di Anna, nessun passaggio da registrazione (è già registrata). Livello BASE, 0 punti status. Il saldo PTS coincide con quello letto da `marta.admin` in CL-BO-012. Attività mostra i movimenti delle sue due azioni.
- **Evidenze**: screenshot di Home e Attività, saldo e livello, richieste di rete verso `/v1/portal/me/…`.
- **Specifiche**: `PT-01`, `PT-07`, `F-WAL-01`, `F2-IAM-03`, `ADR-048`.

### CL-PT-002 — Marco: saldo, livello Silver e movimenti

- **Utente di test**: `marco.bianchi` (membro Marco Bianchi, `MBR-000002`).
- **Precondizioni**: CL-BO-012.
- **Azioni**:
  1. Esegui «Accesso come membro» in un contesto nuovo.
  2. Leggi la tessera, il saldo e la barra verso GOLD in PT-01.
  3. Apri Profilo e livello (PT-08) e Attività (PT-07); tocca un movimento d'acquisto.
- **Esito atteso**: livello SILVER con 1.420 punti status e il testo «Ti mancano … punti status per GOLD» coerente con la soglia di 3.000. PT-08 spiega moltiplicatore e regola di permanenza. Il dettaglio di un acquisto mostra la scomposizione (base × moltiplicatore) e la data di scadenza del lotto.
- **Evidenze**: screenshot di PT-01, PT-08 e del dettaglio del movimento, saldo PTS e STS.
- **Specifiche**: `PT-01`, `PT-07`, `PT-08`, `F-TIER-02`, `F-WAL-01`.

### CL-PT-003 — Giulia: a 120 punti status dal livello Gold

- **Utente di test**: `giulia.ferri` (membro Giulia Ferri, `MBR-000003`).
- **Precondizioni**: CL-BO-012. Esegui questo passo **prima** di CL-BO-014 oppure usa i valori di CL-BO-014: dopo l'acquisto da 130 € il livello atteso è GOLD.
- **Azioni**:
  1. Esegui «Accesso come membro» in un contesto nuovo.
  2. Leggi la barra verso il livello successivo in PT-01.
  3. Apri PT-08.
- **Esito atteso**: prima dell'acquisto livello SILVER, 2.880 STS e «Ti mancano 120 punti status per GOLD»; dopo, livello GOLD con la tessera nel materiale del nuovo livello.
- **Evidenze**: screenshot della barra e della tessera, punti status letti, quale dei due casi hai verificato.
- **Specifiche**: `PT-01`, `PT-08`, `F-TIER-02`, `Q-724`.

### CL-PT-004 — Anna: il catalogo premi per fasce

- **Utente di test**: `anna.rossi`.
- **Precondizioni**: CL-BO-011.
- **Azioni**:
  1. Apri il Catalogo premi (PT-03).
  2. Leggi le fasce, i premi e lo stato rispetto al saldo.
  3. Apri il dettaglio di `RWD-DONATION-TREE` (PT-04).
- **Esito atteso**: i premi pubblicati in CL-BO-011 compaiono nella fascia F1 (500 punti) con «ti mancano N punti» per Anna; i premi rimasti in `DRAFT` o `IN_REVIEW` non compaiono. Nel dettaglio la barra d'azione dice il motivo del blocco (punti insufficienti) e non permette la richiesta.
- **Evidenze**: screenshot del catalogo e del dettaglio con il motivo del blocco.
- **Specifiche**: `PT-03`, `PT-04`, `F-RWD-05`.

### CL-PT-005 — Giulia: l'azione inviata dall'assistenza dà punti

- **Utente di test**: `giulia.ferri`.
- **Precondizioni**: CL-BO-014 (acquisto da 130 € inviato da `paolo.care`).
- **Azioni**:
  1. Apri il portale come Giulia (o ricaricalo se già aperto).
  2. Osserva la Home e Attività: cerca la riga «in arrivo…» o il nuovo movimento.
  3. Confronta la variazione con quella mostrata dall'esito di CL-BO-014.
- **Esito atteso**: entro 20 secondi compare il movimento d'acquisto con la scomposizione; il saldo sale della variazione dichiarata da CL-BO-014 e il livello è GOLD (o quello annotato in CL-BO-014).
- **Evidenze**: screenshot di Attività con il movimento, saldo prima e dopo, tempo di arrivo.
- **Specifiche**: `PT-01`, `PT-07`, `F-WAL-06`, `F2-ING-02`.

### CL-PT-006 — Marco: richiede un premio

- **Utente di test**: `marco.bianchi`.
- **Precondizioni**: CL-BO-011; il saldo PTS di Marco è almeno quello della fascia F1 (500). Altrimenti segna `BLOCCATO` e annota il saldo.
- **Azioni**:
  1. Apri il Catalogo (PT-03) e il dettaglio di `RWD-DONATION-TREE` (PT-04).
  2. Fai clic su «Richiedi per 500 punti» e leggi il foglio di conferma (saldo prima → dopo).
  3. Conferma e attendi l'esito (fino a 20 secondi).
- **Esito atteso**: richiesta accettata, schermata d'attesa, poi esito positivo; il saldo scende di 500 punti, con lo stesso valore del foglio di conferma. In caso di rifiuto il messaggio indica la causa e il saldo non cambia.
- **Evidenze**: screenshot di conferma, attesa ed esito; saldo prima e dopo; stato finale della richiesta.
- **Specifiche**: `PT-03`, `PT-04`, `F-RWD-05`, `F-WAL-01`.

### CL-PT-007 — Marco: i miei premi e il movimento di spesa

- **Utente di test**: `marco.bianchi`.
- **Precondizioni**: CL-PT-006.
- **Azioni**:
  1. Apri «I miei premi» (PT-13).
  2. Apri Attività (PT-07) e cerca la riga della spesa.
- **Esito atteso**: la richiesta compare con il suo stato; Attività ha una riga «Premio richiesto: Pianta un albero» con quantità di spesa (−500) e colore di spesa.
- **Evidenze**: screenshot di PT-13 e della riga di Attività.
- **Specifiche**: `PT-13`, `PT-07`, `F-RWD-05`.

### CL-PT-008 — Un membro non entra nel backoffice

- **Utente di test**: `anna.rossi`.
- **Precondizioni**: CL-PT-001 (sessione di Anna aperta).
- **Azioni**:
  1. Con la sessione di Anna apri `<VETRINA>/backoffice`.
  2. Apri `<VETRINA>/api/lh/insight/v1/audit?size=1`.
  3. Esci dal portale e apri `<VETRINA>/portal`.
- **Esito atteso**: il backoffice non si apre (rifiuto o login del realm `loyaltyhub`, dove Anna non ha un account); l'API risponde `401` o `403 FORBIDDEN_ROLE`, mai dati. Dopo l'uscita `/portal` porta al login del realm `loyaltyhub-members`.
- **Evidenze**: screenshot del rifiuto, stato delle risposte.
- **Specifiche**: `F2-SEC-09`, `F2-IAM-03`, `ADR-051`.

## Area KC — console di Keycloak dei due realm

Le console di `loyaltyhub` e `loyaltyhub-members` sono pubbliche ma limitate; il realm `master` è chiuso al pubblico (ADR-051 decisioni 8 e 7). Indirizzi e amministratori di test: runbook, sezione «Console di Keycloak». In questi passi la console è **solo da leggere**: non modificare utenti, ruoli o sessioni.

### CL-KC-001 — Console del realm operatori

- **Utente di test**: `vetrina.admin` (amministratore di test del realm `loyaltyhub`).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Da HUB-02 (sezione Console) fai clic su «Apri la console ↗» del realm `loyaltyhub`, oppure apri `<IDP>/admin/loyaltyhub/console/`.
  2. Accedi con le credenziali del runbook.
  3. Apri Users e Events.
- **Esito atteso**: la console si apre in `<IDP>/admin/loyaltyhub/console/`; Users elenca i cinque operatori e `vetrina.admin`; gli eventi sono consultabili.
- **Evidenze**: screenshot di Users e Events, URL, errori di console.
- **Specifiche**: `ADR-051`, `Q-671`, `F2-IAM-01`.

### CL-KC-002 — Console del realm membri

- **Utente di test**: `membri.admin` (amministratore di test del realm `loyaltyhub-members`).
- **Precondizioni**: CL-HUB-005.
- **Azioni**:
  1. Apri la console del realm `loyaltyhub-members` da HUB-02 e accedi.
  2. Apri Users.
  3. Da `marta.admin` (ADMIN) controlla anche la voce «Utenti membri» del backoffice.
- **Esito atteso**: Users elenca i quattro membri (`anna.rossi`, `marco.bianchi`, `giulia.ferri`, `laura.conti`). La voce «Utenti membri» del backoffice apre la stessa console in una nuova scheda.
- **Evidenze**: screenshot di Users e della voce del backoffice, URL.
- **Specifiche**: `ADR-051`, `Q-671`, `F2-IAM-03`.

### CL-KC-003 — I limiti degli amministratori di test

- **Utente di test**: `vetrina.admin` e `membri.admin`.
- **Precondizioni**: CL-KC-001, CL-KC-002.
- **Azioni**:
  1. In ciascuna console cerca Realm settings, Clients, Identity providers, User federation e le impostazioni di posta.
  2. Prova ad aprirle (anche dall'indirizzo).
- **Esito atteso**: queste sezioni sono assenti o rifiutate (`403`): gli amministratori di test gestiscono utenti, gruppi, ruoli, sessioni ed eventi, non la configurazione del realm, i client, i provider d'identità né la posta.
- **Evidenze**: screenshot del menu e dei rifiuti, stato delle risposte.
- **Specifiche**: `ADR-051`, `Q-671`.

### CL-KC-004 — Il realm master è chiuso al pubblico

- **Utente di test**: nessuno (visitatore anonimo).
- **Precondizioni**: CL-HUB-004.
- **Azioni**:
  1. Apri `<IDP>/realms/master/` e `<IDP>/realms/master/.well-known/openid-configuration`.
  2. Apri `<IDP>/admin/master/console/` e `<IDP>/admin/`.
  3. Apri `<IDP>/realms/loyaltyhub/.well-known/openid-configuration` e `<IDP>/realms/loyaltyhub-members/.well-known/openid-configuration`.
- **Esito atteso**: le prime quattro rispondono `404`; le due discovery dei realm di vetrina rispondono `200` con JSON. Non esiste nessun pulsante o collegamento a `master` nella vetrina. La console del realm `master` (ADR-055) la apre solo il segreto del proprietario: se risulta aperta (`200` sulle pagine di `master`), non provare ad accedere, non hai le credenziali: segna `N/A` per quella parte.
- **Evidenze**: stato HTTP di ogni indirizzo.
- **Specifiche**: `ADR-051`, `Q-670`.

### CL-KC-005 — Il filtro del proxy non si aggira

- **Utente di test**: nessuno (visitatore anonimo).
- **Precondizioni**: CL-KC-004.
- **Azioni**:
  1. Apri `<IDP>/admin/realms/MASTER`, `<IDP>/admin/realms/%4Daster` e `<IDP>/realms/loyaltyhub/../master/`.
  2. Apri `<IDP>/` e `<IDP>/js/`.
- **Esito atteso**: tutti rispondono `404`: il confronto non distingue maiuscole, lavora sul percorso decodificato e le pagine iniziali sono chiuse.
- **Evidenze**: stato HTTP di ogni indirizzo.
- **Specifiche**: `ADR-051`, `Q-670`, `F2-SEC-12`.

## Area AUD — audit delle scritture di configurazione

Ogni scrittura fatta nei passi BO deve comparire in Audit (BO-22, `GET /v1/audit`) con l'**attore reale** dal token: il nome utente dell'operatore e il suo ruolo, mai un utente di servizio o un nome generico. Si controlla con `marta.admin` o `sara.analyst` (ANALYST legge tutto). Filtra per attore, servizio e periodo.

### CL-AUD-001 — Il caricamento del programma lascia le sue voci

- **Utente di test**: `sara.analyst` (ANALYST), in sola lettura.
- **Precondizioni**: CL-BO-002.
- **Azioni**:
  1. Apri Audit (BO-22), filtra per attore `marta.admin` e per il periodo del collaudo.
  2. Apri il dettaglio di alcune voci (campagna, premio).
- **Esito atteso**: una voce per ciascuna scrittura di CL-BO-002, con attore `marta.admin` e ruolo ADMIN, servizio, azione e oggetto; il dettaglio mostra il confronto prima/dopo.
- **Evidenze**: screenshot dell'elenco filtrato e di un dettaglio, numero di voci, identificativi di due voci.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F2-DIST-09`, `ADR-051`.

### CL-AUD-002 — Sottomissioni di marta.admin e luca.marketing

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-BO-003, CL-BO-006.
- **Azioni**:
  1. Filtra Audit per attore `marta.admin` e cerca le transizioni dei due premi.
  2. Filtra per attore `luca.marketing` e cerca le transizioni delle campagne.
- **Esito atteso**: una voce per ogni invio in revisione, con attore, ruolo (ADMIN, MARKETING), oggetto e commento.
- **Evidenze**: screenshot dei due elenchi, identificativi delle voci.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F-APR-01`.

### CL-AUD-003 — Approvazioni di elena.legal e pubblicazioni di marta.admin

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-BO-009, CL-BO-011.
- **Azioni**:
  1. Filtra Audit per attore `elena.legal` e cerca le approvazioni.
  2. Filtra per attore `marta.admin` e cerca le pubblicazioni.
- **Esito atteso**: una voce `APPROVE` per ogni oggetto approvato, con attore `elena.legal` e ruolo LEGAL; una voce `PUBLISH` per ogni oggetto pubblicato, con attore `marta.admin`. Le approvazioni non sono marcate come *override*.
- **Evidenze**: screenshot dei due elenchi, identificativi delle voci.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F-APR-01`, `F2-GRC-03`.

### CL-AUD-004 — Storie e azione inviata dall'assistenza

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-BO-012, CL-BO-014.
- **Azioni**:
  1. Filtra Audit per attore `marta.admin` e cerca l'import delle storie.
  2. Filtra per attore `paolo.care` e cerca l'import dell'azione di Giulia.
  3. Confronta la voce di `paolo.care` con l'identificativo mostrato dall'esito di CL-BO-014.
- **Esito atteso**: le voci esistono con gli attori reali (`marta.admin` ADMIN, `paolo.care` CARE) e riportano la fonte `vetrina-test`; la voce di CL-BO-014 coincide con quella indicata dall'esito.
- **Evidenze**: screenshot delle voci, identificativi, corrispondenza con l'esito.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F2-ING-02`, `Q-675`.

### CL-AUD-005 — Le azioni negate non lasciano scritture

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-BO-004, CL-BO-007, CL-BO-010, CL-BO-015, CL-BO-017.
- **Azioni**:
  1. Filtra Audit per `APPROVE` sui due premi sottomessi da `marta.admin`.
  2. Filtra per attore `luca.marketing`, `paolo.care` e `sara.analyst` e cerca scritture diverse da quelle dichiarate nei passi.
- **Esito atteso**: nessuna voce `APPROVE` a nome di `marta.admin` sugli oggetti che ha sottomesso; nessuna scrittura di `luca.marketing`, `elena.legal`, `paolo.care` o `sara.analyst` oltre a quelle dei passi che le prevedono (CL-BO-006, CL-BO-009, CL-BO-014); `sara.analyst` nessuna.
- **Evidenze**: screenshot dei filtri e dei risultati vuoti, elenco delle voci trovate.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F2-GRC-03`, `ADR-044`.

### CL-AUD-006 — Lo stesso audit dall'API, e solo con la sessione

- **Utente di test**: `sara.analyst` (ANALYST), poi un contesto senza sessione.
- **Precondizioni**: CL-AUD-001.
- **Azioni**:
  1. Con la sessione di Sara apri `<VETRINA>/api/lh/insight/v1/audit?size=20` e confronta le voci con la schermata.
  2. In un contesto senza cookie apri lo stesso indirizzo.
  3. Cerca nella schermata Audit un modo per modificare o cancellare una voce.
- **Esito atteso**: l'API restituisce le stesse voci della schermata (paginazione `{items,page}`); senza sessione risponde `401 UNAUTHENTICATED`; l'interfaccia non offre né modifica né cancellazione (la tabella è sola inserzione; l'assenza di `UPDATE` a livello di database non si verifica dal browser: annotalo come non verificabile).
- **Evidenze**: stato e forma delle due risposte (solo i campi `id`, `actor`, `action`, `object`), screenshot della schermata.
- **Specifiche**: `BO-22`, `F-AUD-01`, `F2-SEC-06`.

### CL-AUD-007 — Limite dichiarato: le console di Keycloak non sono ancora in Audit

- **Utente di test**: `sara.analyst` (ANALYST).
- **Precondizioni**: CL-KC-001 (sola lettura: nessuna modifica fatta nelle console).
- **Azioni**:
  1. Filtra Audit per servizio `idp`.
  2. Leggi la nota della sezione Console di Keycloak in HUB-02.
- **Esito atteso**: finché non c'è il ponte di M8.12 non ci sono voci del servizio `idp` e HUB-02 lo dichiara («gli eventi di Keycloak non sono ancora nell'audit»). Se le voci ci sono (ponte attivo), annota `OK` con la verifica dell'attore reale; se non ci sono e HUB-02 non lo dichiara è un `KO`.
- **Evidenze**: screenshot del filtro e della nota di HUB-02.
- **Specifiche**: `Q-677`, `F2-SEC-14`, `ADR-051`.
