# Vetrina enterprise: runbook

Questa cartella installa e mantiene la **vetrina enterprise** (F2-DIST-09, ADR-049): una seconda installazione di Loyalty Hub in `LH_PROFILE=enterprise`, separata dalla demo, in un GitHub Codespace acceso su richiesta (ADR-050, ADR-051). Usa il compose di riferimento (`deploy/compose/reference.yml`, F2-DIST-03) più gli overlay di questa cartella.

La vetrina serve a far vedere online login reale, MFA e audit con l'attore del token. Non è ad alta disponibilità, contiene solo dati fittizi e riparte vuota a ogni codespace nuovo, senza backup (Q-663). Il contesto per chi legge la documentazione è nella pagina «Vetrina enterprise» del sito (`concetti/vetrina-enterprise.mdx`).

## Cosa c'è nella cartella

| File | A cosa serve |
|---|---|
| `compose.vetrina.yml` | Overlay del compose di riferimento: reverse proxy in HTTP su `127.0.0.1:8000` e `8001`, TLS verso Kafka e Postgres, segreti da file, profilo fisso. |
| `postgres/pg_hba.conf` | Postgres accetta dalla rete solo connessioni TLS (Q-621). |
| `kafka/client-ssl.properties` | Client TLS del controllo di salute di Kafka. |
| `compose.codespace.yml` | Overlay aggiuntivo per il codespace: console di Keycloak (Q-670, ADR-055), utenti di test (Q-676) e controlli di salute con Node. |
| `caddy/Caddyfile.codespace` | Proxy del codespace: instrada per porta; Keycloak per i realm `loyaltyhub` e `loyaltyhub-members` con le loro console, `master` `404` salvo il permesso importato (Q-670, ADR-055). |
| `caddy/master-chiusa.caddy`, `caddy/master-aperta.caddy` | Permesso per la console del realm `master`: vuoto o con i soli percorsi della console. `vetrina.sh` copia quello giusto in `$LH_VETRINA_DIR/caddy/master-console.caddy` (ADR-055, Q-727). |
| `vetrina.sh` | Comandi per il codespace: `codespace`, `provision`, `preflight`, `up`, `down`, `reset`, `idp-reset`, `operators`, `membri`, `programma`, `compose`. Un file di configurazione già scritto con `LH_VETRINA_MODE=codespace` resta valido; ogni altro valore è rifiutato. |
| `scripts/vetrina-membri.mjs` | Registra Anna, Marco e Giulia dal portale e riporta Laura alla registrazione da zero (Q-673). |
| `operatori.py` | Crea gli account operatore nominativi con MFA (Q-618) a ogni azzeramento. |
| `vetrina.env.example` | Elenco delle chiavi di configurazione, senza segreti: nel codespace le scrive da solo `vetrina.sh codespace`. |

Il dev container del codespace è in `.devcontainer/vetrina/` (`devcontainer.json` e `avvio.sh`).

## Come è fatta l'istanza

- **Un solo componente nuovo: il reverse proxy** (Caddy, regola 8-bis, ADR-049). Nel codespace parla in HTTP su `127.0.0.1:8000` (web) e `127.0.0.1:8001` (Keycloak) e GitHub le rende pubbliche con il suo inoltro, che termina il TLS (Q-661). È l'unico servizio raggiungibile da fuori.
- **Web e Keycloak solo su `127.0.0.1`** (`LH_BIND_ADDRESS=127.0.0.1`). L'hub è su `127.0.0.1:8080` solo per lo script del programma. Postgres e Kafka restano sulla rete interna.
- **Keycloak dal proxy per i due realm di vetrina, `master` solo con il segreto del proprietario.** Il proxy apre `loyaltyhub` e `loyaltyhub-members` con le loro console; il realm `master` risponde `404` finché il segreto `LH_VETRINA_MASTER_ADMIN_PASSWORD` non c'è (ADR-055): vedi «Console di Keycloak» più sotto.
- **TLS verso bus e database con una CA locale** generata nel codespace (Q-621). Kafka ha un listener SSL con certificato del client obbligatorio (l'hub usa `KAFKA_SECURITY=SSL_PEM`). Postgres rifiuta le connessioni in chiaro e hub, migrazioni e Keycloak si collegano con `sslmode=verify-full`, che controlla anche CA e nome. Se un URL del database perde `verify-full` o Kafka non è `SSL_PEM`, il container si ferma con `INSECURE_CONFIG`.
- **Segreti solo da file** (regola 20). Ogni segreto arriva al container come `<VAR>_FILE` da `/run/secrets`. La variabile in chiaro è forzata a vuoto, e il container si ferma se la trova piena.
- **Limiti di memoria per ogni container**, per circa 6,5 GB in tutto sui 16 GB della macchina da 4 core: Postgres 1 GB, Kafka 1 GB (heap 512 MB), hub 2 GB, web 768 MB, Keycloak 1,5 GB, proxy 128 MB.

## Vetrina in un codespace

È l'hosting scelto (ADR-050, Q-660…Q-663): un GitHub Codespace del repository, acceso dal proprietario prima di una demo, che si ferma da solo dopo l'inattività. Il codespace è amd64: `preflight` controlla che ogni immagine ne abbia la variante.

### Una volta sola

1. **Quota a costo zero** (Q-660). In *Settings → Billing and licensing* dell'account GitHub verifica che Codespaces non abbia un metodo di pagamento, oppure imposta un budget a 0: a fine quota gratuita l'uso si blocca invece di addebitare.
2. **Timeout di inattività.** In *Settings → Codespaces* alza il *Default idle timeout* (fino a 240 minuti) se una demo dura più di 30 minuti: il traffico dei visitatori non conta come attività, solo il terminale.
3. **Segreti del codespace** (*Settings → Codespaces → Secrets*, con accesso a questo repository), tutti facoltativi:

   | Segreto | Contenuto |
   |---|---|
   | `LH_VETRINA_OPERATORS` | Account operatore, una riga per account o separati da `;`: `<nome utente> <RUOLO> <e-mail>` (Q-618, Q-663). |
   | `LH_IMAGE` | Immagine unica da usare; senza, `ghcr.io/<proprietario>/loyaltyhub:build-<commit>` dell'ultimo commit che ha toccato l'immagine (vedi «Aggiornare l'immagine»). |
   | `LH_HUB_DEMO_URL` | Origine https della demo per «Torna alla demo» in HUB-02. |
   | `LH_VETRINA_MASTER_ADMIN_PASSWORD` | Password dell'utente `proprietario` nel realm `master`: apre la console `master` dal browser (ADR-055, Q-727). Almeno 12 caratteri, diversa da `proprietario`. Senza, la console resta chiusa (`404`). Come impostarla: «Console di Keycloak» più sotto. |

4. **Pulsante di accensione sulla demo** (Q-674), solo se vuoi accendere la vetrina dal browser. Lo imposti **tu**, una volta, nelle variabili d'ambiente del progetto Vercel della demo (*Settings → Environment Variables*, ambiente Production), poi rifai il deploy:

   | Variabile | Contenuto |
   |---|---|
   | `LH_VETRINA_CODESPACE` | Nome del codespace della vetrina (quello nell'indirizzo `https://<nome>-8000.app.github.dev`). |
   | `LH_VETRINA_GITHUB_TOKEN` | Token *fine-grained* del tuo account GitHub (*Settings → Developer settings → Fine-grained tokens*): solo il repository Loyalty_Sys, permessi **Codespaces** in lettura (per leggere lo stato) e **Codespaces lifecycle admin** in scrittura (per avviare) e nient'altro, scadenza al massimo un anno (Q-674, Q-720). Segnalo come *Sensitive* su Vercel. |

   Serve anche `LH_HUB_ENTERPRISE_URL` (l'indirizzo del punto 4 di «Prima di una demo»). Con una delle due variabili assente il riquadro «Modalità Enterprise» mostra «Disponibile su richiesta» e la route risponde `404`. Il token non finisce mai nel browser né nei log (regola 20): lo usa solo il server della demo, e solo verso `https://api.github.com`. Prima della scadenza genera un token nuovo e sostituiscilo su Vercel.

### Prima di una demo

1. Dal repository: *Code → Codespaces → … → New with options*, configurazione **Vetrina enterprise**, macchina da 4 core. Se il codespace esiste già, riaccendilo da *Code → Codespaces*.
2. Attendi la fine di `avvio.sh` (il terminale *Creation log*, oppure `sudo cat /workspaces/.loyaltyhub-vetrina/avvio.log`). Al primo avvio servono alcuni minuti per scaricare le immagini e avviare Keycloak e Kafka. Se l'avvio si ferma, il registro riporta da solo, sotto «== diagnosi dei container della vetrina», lo stato dei container e le ultime righe di Keycloak, del proxy e di ogni container fermo o non sano: basta mandare quella parte del registro, senza altri comandi.
3. Se lo script avvisa che non riesce a rendere pubbliche le porte, nel pannello *Porte* fai clic destro su 8000 e 8001, *Visibilità della porta → Pubblica*. Le altre porte restano private.
4. L'indirizzo della vetrina è `https://<nome del codespace>-8000.app.github.dev`. Mettilo in `LH_HUB_ENTERPRISE_URL` della demo per il pulsante di HUB-01: il nome del codespace non cambia finché non lo cancelli.
5. Al primo avvio, o dopo un azzeramento, applica il programma con il pulsante **Carica il programma di esempio** del backoffice (V10, Q-673) o, come ripiego, dal terminale del codespace: `sudo --preserve-env=LH_VETRINA_CONFIG env "PATH=$PATH" bash deploy/vetrina/vetrina.sh programma` (`PATH` serve a trovare Node.js) (Q-630). Le password temporanee degli operatori sono in `/workspaces/.loyaltyhub-vetrina/operator-passwords.txt` (leggile con `sudo cat`): consegnale fuori banda e cancella il file.

Fermare e riaccendere il codespace conserva dati e account. Un codespace nuovo parte vuoto; per azzerare quello attuale usa `sudo --preserve-env=LH_VETRINA_CONFIG bash deploy/vetrina/vetrina.sh reset`.

### Tutto dal browser (ADR-051)

Dopo la configurazione una tantum non serve più il terminale. Il percorso parte dalla demo pubblica su Vercel (HUB-01) e arriva alle schermate della vetrina (HUB-02, backoffice, portale) e alle console di Keycloak:

```mermaid
flowchart TD
  accTitle: Percorso dal browser verso la vetrina enterprise
  accDescr: Il visitatore parte dalla demo pubblica, accende la modalità Enterprise con il pulsante nel riquadro, attende l'avvio del codespace e arriva a HUB-02 della vetrina. Lì sceglie la scheda di un utente di test: l'operatore entra nel realm loyaltyhub con password e codice OTP del momento e arriva al backoffice, il membro entra nel realm loyaltyhub-members e arriva al portale, gli amministratori di test aprono la console del proprio realm. La console del realm master si apre dallo stesso indirizzo di Keycloak solo con il segreto del proprietario.
  A[Demo pubblica HUB-01<br/>riquadro Modalità Enterprise] -->|Accendi| B[GitHub avvia il codespace<br/>3–5 minuti]
  B --> C[HUB-02 della vetrina<br/>schede degli utenti di test]
  C --> D{Scheda scelta}
  D -->|Operatore| E[Login nel realm loyaltyhub<br/>password e codice OTP del momento]
  D -->|Membro| F[Login nel realm loyaltyhub-members<br/>login_hint dall'utente scelto]
  D -->|Amministratore di test| G[Console del realm<br/>vetrina.admin o membri.admin]
  E --> H[Backoffice<br/>per il ruolo dell'operatore]
  F --> I[Portale dei membri<br/>il membro viene dal token]
  G --> J[Utenti, ruoli, sessioni, eventi]
  P[Console del realm master<br/>aperta solo con il segreto<br/>del proprietario] -.->|utente proprietario,<br/>solo password| K[Configurazione di Keycloak]
```

1. **Accendi.** Sulla demo, il riquadro **Modalità Enterprise** (HUB-01) mostra lo stato della vetrina: *spenta*, *in accensione*, *accesa*. Con **Accendi la modalità Enterprise** il server della demo chiede a GitHub di avviare il codespace (Q-674); l'avvio richiede 3–5 minuti e il pulsante diventa **Apri la vetrina** da solo. Il codespace si spegne dopo circa 30 minuti senza uso del terminale: riaccendilo dallo stesso pulsante. Il pulsante ammette al più un avvio ogni 60 secondi (lo stato è letto da GitHub al più ogni 5 secondi), controlla l'origine della richiesta e consuma le ore gratuite di Codespaces del proprietario.
2. **Scegli un utente di test.** HUB-02 mostra le schede degli utenti di test (tabella in «Utenti di test»): cinque operatori per il backoffice, quattro membri per il portale. Ogni scheda ha nome utente e password da copiare e un pulsante **Entra come…** che porta al login del **realm giusto** con `login_hint`: il nome utente arriva già compilato e resta da scrivere la password.
3. **Codice OTP.** Gli operatori hanno la MFA: la scheda mostra il **codice OTP del momento**, calcolato dal seme documentato più sotto, con il conto alla rovescia dei 30 secondi. Il codice è visibile solo in questo ambiente di test dichiarato (Q-676) e non in una installazione `enterprise` vera.
4. **Entra.** Dopo il login l'operatore apre il backoffice con il ruolo della scheda (`ADMIN`, `MARKETING`, `LEGAL`, `CARE`, `ANALYST`). Il membro apre il portale (fetta V9b): il membro viene solo dal token. Con una sessione da membro già aperta, la scheda del suo utente dice «Sei dentro come…» con **Apri il portale** e le altre schede di membro offrono **Esci per entrare come…** (chiude la sola sessione del membro). **Laura** non è registrata: dopo il login il portale la porta alla registrazione (PT-16) con nome ed e-mail dell'account in sola lettura; si accetta il regolamento e si invia, poi si attende la tessera e si apre la Home.
5. **Utenti membri.** Nel backoffice, `ADMIN` e `CARE` hanno la voce **Utenti membri**: apre la console del realm `loyaltyhub-members` (password, sessioni e blocchi degli account di accesso dei membri).

### Console di Keycloak

| Console | Indirizzo | Chi entra | Visibilità |
|---|---|---|---|
| Realm operatori | `https://<nome>-8001.<dominio di inoltro>/admin/loyaltyhub/console/` | `vetrina.admin` | pubblica (porta 8001) |
| Realm membri | `https://<nome>-8001.<dominio di inoltro>/admin/loyaltyhub-members/console/` | `membri.admin` | pubblica (porta 8001) |
| Realm `master` | `https://<nome>-8001.<dominio di inoltro>/admin/master/console/` | utente `proprietario`, password nel segreto del codespace `LH_VETRINA_MASTER_ADMIN_PASSWORD` | pubblica **solo con il segreto** (ADR-055, Q-727); senza, `404` |

`vetrina.sh codespace` stampa i tre indirizzi all'avvio e dice se la console `master` è *aperta* o *chiusa* (mai la password). HUB-02 linka le due console dei realm di vetrina; la console `master` non ha un pulsante.

**Come aprire la console `master` (una volta sola, dal browser).**

1. Apri `https://github.com/settings/codespaces`.
2. In *Secrets* scegli *New secret*: nome `LH_VETRINA_MASTER_ADMIN_PASSWORD`, valore una password lunga e casuale (almeno 12 caratteri, diversa da `proprietario`), *Repository access* su `Loyalty_Sys`.
3. Riavvia il codespace (dal pulsante **Accendi** del Demo Hub o dal pannello): il segreto vale dall'avvio successivo.
4. Apri l'indirizzo della tabella e accedi con `proprietario` e la tua password.

Per cambiare la password modifica il segreto e riavvia; per chiudere la console elimina il segreto e riavvia. La password non va mai nei log, negli argomenti dei comandi, in HUB-02 o nel repository: `vetrina.sh` la legge dall'ambiente, la toglie subito e la passa a `apply-overlay.sh` solo come variabile del processo figlio (regola 20).

**Cosa lascia passare il proxy sulla porta 8001** (`caddy/Caddyfile.codespace`, nell'ordine in cui lo applica):

1. `404` per i percorsi con trucchi di normalizzazione: `\`, `;` e `%` rimasto dopo la decodifica. Vengono **prima di tutto**, anche con la console `master` aperta. I segmenti `..` non hanno una regola propria: Caddy pulisce il percorso prima del confronto, quindi `/realms/loyaltyhub/../master/` diventa `/realms/master/` e segue la regola 2 (la prova con Caddy lo verifica). Limite dichiarato: un nome (utente, ruolo, gruppo) che contiene `%`, `;` o `\` non si può gestire dalla console pubblica.
2. Il realm `master`: il file importato `master-console.caddy` viene prima del `404`. **Chiuso** (`master-chiusa.caddy`, vuoto), `404` per `/admin/master*`, `/admin/realms/master*`, `/realms/master*` e `/resources/master/*`; il confronto non distingue maiuscole e minuscole e lavora sul percorso decodificato, quindi `/admin/realms/MASTER`, `%4Daster` e `%2F` non aggirano la regola. **Aperto** (`master-aperta.caddy`), passano solo `/admin/master`, `/admin/master/*`, `/realms/master`, `/realms/master/*`, `/resources/master`, `/resources/master/*`, `/admin/realms` e `/admin/realms/*` (un amministratore di `master` gestisce tutti i realm); `/admin` e `/admin/` restano `404`. Si apre solo dopo aver creato e verificato l'utente `proprietario`, ed è chiuso a ogni avvio, a ogni ricreazione del database di Keycloak, senza segreto e con una password respinta.
3. Solo i percorsi dell'elenco, uno per uno commentato nel Caddyfile: `/realms/loyaltyhub*` e `/realms/loyaltyhub-members*` (login, token, certificati), `/resources/*` (script e stili), la pagina `/admin/<realm>/console` di ciascun realm, le API `/admin/realms/<realm>` che la console chiama dal browser (`ui-ext` compreso) e `/admin/serverinfo`, che la console legge all'avvio. Le API le protegge il realm con i suoi token.
4. Tutto il resto è `404`: `/admin`, `/js/*`, la pagina iniziale e, a console `master` chiusa, l'elenco `/admin/realms`.

**Come funziona l'indirizzo di `master`.** `KC_HOSTNAME_ADMIN` non è impostato nel codespace: console e login di tutti i realm, `master` compreso, usano l'indirizzo pubblico di `KC_HOSTNAME`. Il realm `master` non ha più un `frontendUrl` proprio. Gli script dell'host continuano a chiedere il token su `127.0.0.1:8180` (porta solo su loopback, non inoltrata): con `KC_HOSTNAME` fisso l'emittente dei token non dipende dall'indirizzo con cui li si chiede, quindi token e API di amministrazione restano coerenti con la console.

**Utente del proprietario e blocco dei tentativi.** `apply-overlay.sh` crea (o aggiorna a ogni avvio) l'utente permanente `proprietario` nel realm `master` con il ruolo `admin` e la password del segreto, **non temporanea**, e la verifica rileggendo utente, ruolo e credenziale e con un login vero. Se la password non rispetta la politica del realm (almeno 12 caratteri, diversa dal nome utente) o Keycloak la rifiuta, il solo passo dell'utente fallisce (uscita 3) con un messaggio senza il valore, la console resta chiusa e la vetrina parte comunque. Non c'è OTP (ADR-055, opzione B): la protezione è la password più il blocco dei tentativi di `master.json`, cioè **5 tentativi falliti, poi un'attesa che cresce fino a 15 minuti** (nessun blocco permanente). Il blocco si azzera a ogni avvio, perché il database di Keycloak si ricrea.

**Limiti.** Con la console `master` aperta la protezione è la sola password: usane una lunga e casuale, che non usi altrove (un amministratore di `master` può configurare posta e provider d'identità). Le console dei due realm sono pubbliche e, con la password documentata, chiunque ha il link può usarle: per questo gli amministratori di test hanno solo i ruoli di gestione utenti (Q-671). Le modifiche fatte nelle console restano negli eventi di amministrazione di Keycloak e non arrivano in `audit_entry` fino al ponte di M8.12 (Q-677). Ogni avvio ripristina Keycloak dal repository (Q-672).

### Ingresso e rete

- **Ingresso.** Nessun certificato da gestire: il TLS lo termina l'inoltro delle porte di GitHub. Il proxy Caddy resta, in HTTP su `127.0.0.1:8000` (web) e `127.0.0.1:8001` (Keycloak), per tenere chiuso il realm `master` finché non c'è il segreto del proprietario, aprire le console dei due realm di vetrina e aggiungere le intestazioni di sicurezza. Sulla porta 8000 rimette anche l'origine pubblica: l'inoltro di GitHub riscrive l'intestazione `Origin` in `http(s)://localhost:8000` (discussione GitHub community 147513) e senza questa correzione il BFF rifiuterebbe ogni scrittura con `403 CSRF_REJECTED` (#235). Si corregge solo quel valore esatto; `Sec-Fetch-Site` e il token CSRF restano verificati.
- **Emittente OIDC.** Web e hub chiamano Keycloak con lo stesso indirizzo pubblico del browser, passando dall'inoltro di GitHub (Q-420): per questo la porta 8001 deve essere pubblica.
- **Console di Keycloak.** Quelle dei realm `loyaltyhub` e `loyaltyhub-members` sono pubbliche sulla porta 8001; quella del realm `master` sta sulla stessa porta ma si apre solo con il segreto del proprietario. La porta 8180 serve solo agli script dell'host e non si inoltra. Indirizzi, accessi e limiti sono nella sezione «Console di Keycloak».
- **Azzeramento.** Nessuna pianificazione (non girerebbe a codespace fermo): un codespace nuovo parte vuoto e quello attuale si azzera a mano con `vetrina.sh reset` (Q-663).

### Utenti di test (ADR-051 decisione 1)

> **Ambiente di test, dati fittizi.** Le credenziali qui sotto sono **pubbliche** e valgono solo nel codespace. Non inserire mai dati personali veri.

Gli utenti hanno id fissi (UUID) e li ricrea `deploy/idp/vetrina/apply-overlay.sh` a ogni avvio dai due overlay `realm-vetrina-overlay.json` e `realm-members-vetrina-overlay.json`, che sono l'unica fonte. Gli operatori hanno `MFA_REQUIRED_ROLE`, quindi chiedono password **e** codice OTP. Tutti hanno il ruolo `LH_TEST_USER`, l'unico che hub e web accettano per credenziali fisse e solo con `LH_TEST_USERS_ALLOWED=true` e `LH_ENVIRONMENT=test`.

| Utente | Realm | Ruolo | Password |
|---|---|---|---|
| `marta.admin` | `loyaltyhub` | `ADMIN` | `Aurora-Operatori-26!` |
| `luca.marketing` | `loyaltyhub` | `MARKETING` | `Aurora-Operatori-26!` |
| `elena.legal` | `loyaltyhub` | `LEGAL` | `Aurora-Operatori-26!` |
| `paolo.care` | `loyaltyhub` | `CARE` | `Aurora-Operatori-26!` |
| `sara.analyst` | `loyaltyhub` | `ANALYST` | `Aurora-Operatori-26!` |
| `anna.rossi` | `loyaltyhub-members` | `MEMBER` | `Aurora-Membri-26!` |
| `marco.bianchi` | `loyaltyhub-members` | `MEMBER` | `Aurora-Membri-26!` |
| `giulia.ferri` | `loyaltyhub-members` | `MEMBER` | `Aurora-Membri-26!` |
| `laura.conti` | `loyaltyhub-members` | `MEMBER` | `Aurora-Membri-26!` |
| `vetrina.admin` | `loyaltyhub` (console) | gestione utenti, senza ruoli applicativi | `Aurora-Admin-26!` |
| `membri.admin` | `loyaltyhub-members` (console) | gestione utenti, senza ruoli applicativi | `Aurora-Admin-26!` |

**Codice OTP degli operatori.** I cinque operatori condividono un solo seme TOTP (SHA-1, 6 cifre, 30 secondi). Aggiungilo a un'app di autenticazione inserendo la chiave a mano, oppure con questo URI:

- seme in base32: `KZSXI4TJNZQUC5LSN5ZGCMRQGI3EY2BB`
- URI: `otpauth://totp/Vetrina%20Aurora:marta.admin?secret=KZSXI4TJNZQUC5LSN5ZGCMRQGI3EY2BB&issuer=Vetrina%20Aurora&algorithm=SHA1&digits=6&period=30`

Non c'è un'immagine con il codice QR: nessun generatore offline è tra le dipendenze del repository, e non si usano servizi esterni per non far uscire il seme. Per la stessa finestra di 30 secondi Keycloak non accetta due volte lo stesso codice: se due persone accedono insieme, la seconda aspetta il codice successivo.

Gli amministratori `vetrina.admin` e `membri.admin` aprono la console del proprio realm e hanno solo i cinque ruoli di `realm-management` `view-users`, `query-users`, `query-groups`, `manage-users` e `view-events`: non cambiano la configurazione del realm (Q-671). Con `manage-users` possono però assegnare ruoli agli utenti del loro realm: è il limite accettato dalla decisione. Gli eventi di amministrazione di Keycloak sono attivi con i dettagli della rappresentazione in entrambi i realm, e anche in `master`. **Limite (Q-677):** le modifiche fatte da questi amministratori nelle console restano negli eventi di Keycloak e non arrivano in `audit_entry` finché non c'è il ponte di M8.12, che coprirà entrambi i realm. La console del realm `master` resta privata, solo sulla porta 8180 (Q-670).

### Keycloak da zero a ogni avvio

A ogni avvio `vetrina.sh codespace` ricrea il database `idp` di Keycloak (comando `idp-reset`), prima di avviare lo stack. Così il realm torna alla configurazione del repository e non resta nulla dei cambi fatti dalla console. Il database dell'hub non si tocca: i membri registrati restano e il loro legame con l'utente di Keycloak resta valido, perché gli id degli utenti sono fissi.

```mermaid
flowchart TD
  accTitle: Avvio della vetrina con Keycloak da zero
  accDescr: A ogni avvio del codespace lo script ricrea il solo database idp di Keycloak, avvia lo stack, applica gli overlay dei realm master, operatori e membri con gli utenti di test, crea gli account operatore mancanti e, con le porte pubbliche, registra Anna, Marco e Giulia dal portale e riporta Laura alla registrazione da zero.
  A[Avvio del codespace] --> B[idp-reset: database idp ricreato<br/>database dell'hub intatto]
  B --> C[Stack avviato:<br/>Keycloak reimporta i realm di base]
  C --> D[apply-overlay.sh: master, operatori,<br/>membri, utenti di test con id fissi]
  D --> E[Account operatore mancanti]
  E --> F[Porte 8000 e 8001 pubbliche]
  F --> G[vetrina-membri.mjs: Anna, Marco, Giulia<br/>registrati dal portale]
  G --> H[Laura riportata alla registrazione da zero<br/>anonimizzazione come ADMIN]
```

### Inviare un'azione di prova (V11)

Dopo il programma di esempio, la pagina **Import** del backoffice ha il modulo **Invia un'azione** per `ADMIN` e `CARE`: membro di prova, azione ed eventuale importo. Crea un import di una riga dalla fonte `vetrina-test` con il token dell'operatore e mostra l'esito, i punti letti dal portafoglio e la voce di audit. Serve il programma caricato (la fonte `vetrina-test` ne fa parte) e il membro registrato. In `enterprise` il menu **Demo** non compare.

### Scenario dei quattro membri

`scripts/vetrina-membri.mjs` (comando `vetrina.sh membri`) usa solo il login e le API reali, passando dal BFF all'indirizzo pubblico del web. Per questo serve che le porte 8000 e 8001 siano pubbliche: `avvio.sh` lo lancia dopo averle pubblicate e `vetrina.sh codespace` lo tenta prima in modo morbido.

- **Anna, Marco e Giulia** sono già registrati: lo script entra con il loro login e crea il profilo con i dati del seed (`POST /v1/portal/members`, idempotente). Hanno subito tessera e profilo.
- **Laura** (`laura.conti`, cognome e e-mail fittizi) esiste in Keycloak ma **non è registrata** nel portale: a ogni avvio lo script cerca il suo profilo e, se c'è, lo anonimizza con `marta.admin` (`ADMIN`, con il suo OTP). Alla prima visita Laura passa quindi dalla registrazione vera.

Se la registrazione risponde `409 EMAIL_TAKEN`, lo script lo dice in chiaro e rimanda a `vetrina.sh reset`.

## Verifica

```bash
# Dal terminale del codespace: stato dei container (tutti healthy, il proxy running)
sudo --preserve-env=LH_VETRINA_CONFIG bash deploy/vetrina/vetrina.sh compose ps
# Discovery OIDC raggiungibile dall'indirizzo pubblico della porta 8001: atteso 200 (302 = porta ancora privata)
curl -s -o /dev/null -w '%{http_code}\n' "https://<nome>-8001.<dominio di inoltro>/realms/loyaltyhub/.well-known/openid-configuration"
# Il realm master senza il segreto del proprietario non è esposto: atteso 404 (con il segreto e la console aperta: 200)
curl -s -o /dev/null -w '%{http_code}\n' "https://<nome>-8001.<dominio di inoltro>/realms/master/"
# HUB-02 della vetrina: atteso 200 (302 = porta ancora privata)
curl -s -o /dev/null -w '%{http_code}\n' "https://<nome>-8000.<dominio di inoltro>/"
```

### Smoke pianificato

Il workflow `.github/workflows/smoke-vetrina.yml` (M8.14 V6) controlla la vetrina ogni sei ore, senza credenziali e solo con richieste `GET`: health del web, HUB-02 con banner e tessere attive, login avviato dal BFF verso il realm `loyaltyhub`, discovery OIDC e JWKS, registrazione chiusa (Q-619), realm `master` in `404` (o `200` sulla sola discovery, se il proprietario ha aperto la console), API del BFF chiuse senza sessione. La health di hub, Postgres e Kafka si legge dalle tessere di HUB-02, perché il proxy non espone altro (Q-650).

Quando la vetrina è online, imposta la variabile del repository `LH_VETRINA_URL` (*Settings → Secrets and variables → Actions → Variables*) con l'origine https del web, cioè `https://<nome del codespace>-8000.app.github.dev`. Finché è vuota il workflow esce verde con un avviso. La stessa prova si lancia a mano da qualunque macchina con Node 22:

```bash
node scripts/smoke-enterprise.mjs vetrina --web https://<nome del codespace>-8000.app.github.dev
```

Il login reale di un operatore con OTP e la voce di audit con l'attore reale li prova invece il job `smoke enterprise (compose, OIDC)` di `ci.yml`, sul compose di riferimento con l'overlay di test del realm, a ogni pull request che tocca il codice.

## Esercizio

### Aggiornare l'immagine

La vetrina segue `main` da sola (Q-726). Un merge su `main` che tocca l'immagine pubblica `build-<sha>` firmata con `image.yml` (circa 10–20 minuti); al prossimo avvio del codespace, dal pulsante «Accendi» del Demo Hub o da un riavvio, `avvio.sh` porta il clone a `origin/main` (solo fast-forward, ramo `main` pulito: altrimenti avvisa e lascia `HEAD`), sceglie la `build-<sha>` dell'ultimo commit che ha toccato l'immagine (`vetrina.sh immagine --scegli`) e la scarica. Il segreto del codespace `LH_IMAGE` resta un'indicazione esplicita: se c'è vince sulla scelta dell'immagine, ma il clone si allinea comunque a `main` e vale il controllo di freschezza qui sotto (per seguire `main`, togli il segreto). `build-<sha>` esiste solo per immagini già scansionate (Trivy), firmate e verificate: la build usa un tag di lavoro `scan-<sha>` e pubblica `build-<sha>` come ultimo passo; così due esecuzioni dello stesso commit producono lo stesso risultato. Cambiare `LH_IMAGE` e riavviare il codespace: `avvio.sh` riscrive la configurazione con la nuova immagine. Le migrazioni sono expand/contract (ADR-038), quindi non serve un azzeramento.

Prima di avviare lo stack `avvio.sh` lancia `vetrina.sh immagine`: legge la revisione dell'immagine (etichetta OCI `org.opencontainers.image.revision`, scritta dalle build di `image.yml`; per un'immagine senza etichetta, il commit del tag `:vX.Y.Z` noto al repository) e la confronta con `HEAD`. Se l'immagine è di un commit più vecchio e le cartelle che finiscono nell'immagine (`web`, `services`, `libs`, `contracts`, `seed`, `deploy/hub`, `deploy/image`, `pom.xml`, `.mvn`, `mvnw`) differiscono, esce con codice 2 e dice quale immagine, quale revisione e quale `HEAD` (un ritocco ai soli script non invecchia l'immagine; se la revisione non è un antenato di `HEAD`, l'immagine è più nuova del clone o di un altro ramo e il messaggio chiede `git pull`): imposta il segreto `LH_IMAGE` su un'immagine costruita da questo commit, oppure crea un nuovo tag di rilascio (altrimenti l'ultimo `v*` può precedere gli script del repository e dare errori oscuri, come un accesso dei membri che torna al passo nome utente e password). Se `build-<sha>` non è scaricabile, `avvio.sh` usa subito l'ultimo tag `v*` se non differisce da `HEAD` nelle cartelle dell'immagine (è equivalente). Altrimenti interroga GitHub (`gh api …/actions/workflows/image.yml/runs?head_sha=…`, con il token del codespace): solo se un'esecuzione di `image.yml` è in coda o in corso stampa «immagine build-<sha> in costruzione su GitHub Actions, attendo…» e riprova ogni 30 s fino a 15 minuti (`LH_AVVIO_ATTESA_S` e `LH_AVVIO_INTERVALLO_S` per cambiarli); se l'esecuzione è fallita o non c'è, si ferma subito con la causa; se lo stato non è verificabile (gh, token o API) attende con lo stesso limite e lo dice («stato della build non verificabile»). Un errore di scaricamento diverso da «manifest unknown» (rete, accesso, GHCR) è mostrato con la sua causa e non scambiato per una build mancante. Durante l'attesa il visitatore vede la pagina di inoltro delle porte di GitHub e HUB-01 può mostrare «accesa» prima che la vetrina sia pronta (Q-721); nessuna modifica al web in questa fetta. Se la build non esiste o è fallita: nella scheda Actions, workflow «Build and Release Image», «Run workflow» da `main` (workflow_dispatch), poi riavvia il codespace. Se la revisione non è ricavabile, stampa «non verificabile» e continua. `LH_VETRINA_ALLOW_STALE_IMAGE=true` (variabile d'ambiente del codespace, non una chiave del file di configurazione) trasforma l'errore in un avviso forte: solo per diagnosi.

### Segreti e certificati

`vetrina.sh provision` genera ogni file una sola volta e non lo riscrive. Non stampa mai un valore, solo nomi e percorsi.

| File in `/workspaces/.loyaltyhub-vetrina` (`$LH_VETRINA_DIR`) | Contenuto | Proprietario e permessi | Lo legge |
|---|---|---|---|
| `secrets/db-password` | password del ruolo `loyaltyhub` | 1000, `0600` | Postgres, migrazioni, hub |
| `secrets/idp-db-password` | password del ruolo `idp` | 1000, `0600` | Postgres, Keycloak |
| `secrets/idp-admin-password` | amministratore iniziale di Keycloak | 1000, `0600` | Keycloak, `vetrina.sh` |
| `secrets/web-client-secret`, `portal-client-secret`, `widgets-client-secret`, `cms-client-secret` | segreti dei client dei due realm (`portal` e `widgets` nel realm dei membri, ADR-051) | 1000, `0600` | Keycloak, web |
| `secrets/web-session-key` | chiave delle sessioni del BFF | 1000, `0600` | web |
| `secrets/subject-key` | `LH_SUBJECT_KEY`, **immutabile** (docs/11 §16) | 1000, `0600` | hub |
| `ca/ca.key`, `ca/ca.crt` | CA locale | root, `0600` | solo `vetrina.sh` |
| `tls/ca.crt` | certificato della CA, pubblico | root, `0644` | hub, Keycloak, Kafka |
| `tls/postgres.key`, `tls/postgres.crt` | certificato del server Postgres | 999, `0600` (chiave) | Postgres |
| `tls/kafka-keystore.pem` | chiave e certificato del broker | 1000, `0600` | Kafka |
| `tls/kafka-client-*.b64` | CA, certificato e chiave dell'hub verso Kafka | 1000, `0600` | hub |

L'uid 1000 è l'utente dei container dell'immagine unica, di Keycloak e di Kafka; il 999 è l'utente `postgres` dell'immagine di Postgres. Nel codespace lo stesso uid è l'utente `vscode`, che ha già i privilegi di amministratore.

I certificati interni durano 397 giorni e l'azzeramento li rinnova quando ne mancano meno di 30. Per cambiare la CA, cancella `ca/` e rilancia `provision`: riemette tutti i certificati. Non cancellare mai `secrets/subject-key` fuori da un azzeramento: cambiare la chiave scollega i token dai membri (docs/11 §16).

> **Nota:** l'elenco dei segreti è lo stesso in `vetrina.sh` (`SECRETS`) e in `compose.vetrina.yml` (`secrets:`); `scripts/check-vetrina.mjs` verifica che coincidano.

## Limiti noti

- **Codespace acceso solo su richiesta** (ADR-050, TOBE-012): fuori dalle demo la vetrina è spenta e il pulsante di HUB-01 porta a una pagina di GitHub. L'uso si blocca a fine quota gratuita (Q-660).
- **Segreti leggibili dall'utente del codespace.** Nel codespace l'uid 1000 dei container coincide con l'utente `vscode`: chi apre il terminale del codespace, cioè solo il proprietario, può leggere i file di `secrets/`.
- **Pagina di avviso di GitHub.** Al primo accesso da un browser a una porta pubblica GitHub può mostrare una pagina di conferma prima della vetrina.
- **Non è HA.** Un codespace, un broker, un Postgres e una replica del web; gli obiettivi di ADR-036 non valgono.
- **Azzeramento non del tutto automatico** (Q-630): il programma si riapplica a mano, con il pulsante del backoffice (V10) o, come ripiego, con `vetrina.sh programma` (che non crea campagne né storie).
- **HTTP in chiaro dentro la rete compose** tra proxy, web, Keycloak e hub, dentro lo stesso codespace (TOBE-011). Bus e database sono in TLS.
- **Guardia TLS nel container, non nell'hub** (Q-631): la rifiuta l'entrypoint dell'overlay, non ancora il codice dell'hub.
- **Tempo reale in BO-24** in stato *degraded* finché non arriva il proxy SSE nel BFF (Q-622).
