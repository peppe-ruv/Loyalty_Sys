# 19 — Registro to-be: migliorie rimandate oltre la PoC

Questo registro raccoglie le migliorie di codice, sicurezza o esercizio che oggi non sono sostenibili nel contesto della PoC. Ogni voce dice cosa è già predisposto, perché non si fa adesso e quando diventa necessaria.

Lo scopo è semplice: non vanificare la PoC per requisiti che non puoi ancora sostenere, ma non perderli di vista. Una voce del registro non è un permesso di consegnare qualcosa che non funziona: la PoC gira sempre con il miglior workaround sostenibile, e il registro raccoglie solo quello che il workaround lascia scoperto.

## Come usare il registro

Consulta il registro quando pianifichi una fetta o prepari un rilascio verso un'installazione reale. Aggiungi una voce quando una revisione o una specifica chiede qualcosa che la PoC non può sostenere, per costi, infrastruttura o complessità.

Prima di aggiungere una voce, fai quattro cose:

1. **Applica il miglior workaround sostenibile.** Cerca la soluzione più vicina al requisito che regge con i vincoli della PoC: costo zero, 512 MB per servizio, nessun componente infrastrutturale nuovo senza ADR. Implementala e coprila con i test, come il resto della fetta. Se il workaround richiede una decisione (per esempio un componente nuovo), apri una domanda in `docs/15` e scegli l'opzione più conservativa che funziona.
2. **Predisponi.** Lascia nel codice il punto di estensione: un'interfaccia, un parametro di configurazione, una validazione che dichiara il limite. La miglioria deve poter entrare senza riscrivere la fetta.
3. **Dichiara il limite residuo.** Il comportamento attuale è documentato dove lo legge chi installa (note del chart, `deploy/README.md`, pagina Mintlify) ed è coerente con la regola 22: il profilo `enterprise` rifiuta una configurazione insicura invece di avvisare.
4. **Collega.** Cita le domande aperte (`Q-nnn`) e le ADR coinvolte. La decisione resta in `docs/15`; qui c'è solo il piano.

> **Nota:** «predisposto» non basta. Se la funzione non si può usare nella PoC, per nessuna via, la fetta non è finita: cerca un workaround prima di scrivere la voce.

> **Nota:** una voce to-be non è un `TODO` nel codice. Nel codice resta il riferimento `SPEC-GAP: Q-nnn` alla domanda aperta, come chiede CLAUDE.md §6.

### Formato di una voce

Ogni voce ha un identificativo `TOBE-nnn` e questi campi:

| Campo | Contenuto |
|---|---|
| Cosa | la miglioria, in una frase |
| Perché non ora | il limite della PoC che la rende non sostenibile |
| Workaround attivo | cosa funziona oggi al posto della miglioria, e quale limite resta |
| Già predisposto | cosa c'è nel codice o nella configurazione per accoglierla |
| Quando farla | la condizione che la rende necessaria (per esempio «più di una replica», «primo cliente con CA interna») |
| Riferimenti | domande aperte, ADR, fette |

## Voci aperte

### TOBE-001 — Store di sessione condiviso per il BFF del web

| Campo | Contenuto |
|---|---|
| Cosa | Tenere le sessioni del BFF in uno store condiviso (database del ruolo `web`, cifrato), così il web può girare con più repliche e sopravvivere a un riavvio senza far rifare il login. |
| Perché non ora | Il ruolo `web` non ha ancora un database proprio, e uno store condiviso richiede anche un lock per il rinnovo dei token. Nella PoC il web gira con una sola replica. |
| Workaround attivo | Il web gira con una sola replica e tiene le sessioni in memoria, cifrate. Login OIDC, rinnovo dei token e logout funzionano; il limite residuo è che un riavvio del web chiede di rifare il login. |
| Già predisposto | Interfaccia `SessionStore` con l'implementazione in memoria cifrata AES-256-GCM (`web/lib/auth/sessionStore.ts`); il rinnovo annota come rileggere la sessione dentro il blocco esclusivo (`web/lib/auth/refresh.ts`). Il chart rifiuta più di una replica del web nel profilo `enterprise`. |
| Quando farla | Prima di dichiarare l'alta disponibilità del web (ADR-036) o quando un riavvio del web che fa rifare il login non è più accettabile. |
| Riferimenti | Q-409, Q-419, ADR-027, ADR-036, M8.2 |

### TOBE-002 — Firma degli ancoraggi dell'audit ed esportazione immutabile

| Campo | Contenuto |
|---|---|
| Cosa | Firmare ogni giorno l'ultimo hash della catena dell'audit ed esportarlo su uno storage immutabile (S3 Object Lock o volume WORM). |
| Perché non ora | Richiede infrastruttura nuova (bucket con blocco degli oggetti, gestione delle chiavi di firma) che la PoC a costo zero non ha. |
| Workaround attivo | La catena di hash per servizio rileva ogni modifica o cancellazione fatta fuori dalle funzioni ammesse, e `GET /v1/audit/verify` la controlla su richiesta. Il limite residuo è che gli ancoraggi restano nello stesso database: chi ha accesso da superutente può riscrivere catena e ancoraggi insieme. |
| Già predisposto | La catena di hash per servizio, la verifica e gli ancoraggi giornalieri nella tabella `audit_anchor` (fetta M8.12a). L'esportazione si aggiunge come nuovo consumatore degli ancoraggi. |
| Quando farla | Con il primo cliente che chiede evidenze di audit opponibili a terzi, oppure con il pacchetto di conformità di M12.6. |
| Riferimenti | Q-400, ADR-043, F2-GRC-07, M8.12, M12.6 |

### TOBE-003 — Truststore per una CA interna nel logout di Keycloak

| Campo | Contenuto |
|---|---|
| Cosa | Dare a Keycloak un truststore per chiamare il back-channel logout del web quando il certificato pubblico del web è firmato da una CA aziendale. |
| Perché non ora | Il chart non gestisce truststore per Keycloak. Se la chiamata fallisce, una sessione resta valida al massimo per la durata dell'access token (5 minuti). |
| Workaround attivo | Il logout avviato dal web chiude subito la sessione del BFF e quella di Keycloak. Se Keycloak non riesce a notificare il web, la sessione del BFF cade al primo rinnovo rifiutato, al più dopo 5 minuti. |
| Già predisposto | Il web accetta già una CA interna per parlare con l'emittente (`roles.web.bff.issuerCaBundle`, fetta M8.2d). |
| Quando farla | Con la prima installazione dietro una CA interna in cui il logout immediato è un requisito. |
| Riferimenti | Q-421, M8.2 |

### TOBE-004 — TLS integrato nel compose di riferimento

| Campo | Contenuto |
|---|---|
| Cosa | Aggiungere al compose di riferimento un reverse proxy con TLS, così il login OIDC del profilo `enterprise` funziona senza componenti esterni. |
| Perché non ora | Un proxy in più è un componente infrastrutturale nuovo (regola 8-bis) e richiede certificati. Oggi il compose pubblica le porte solo su `127.0.0.1` e documenta il proxy da mettere davanti. |
| Workaround attivo | Il profilo `demo` funziona senza proxy. Per il profilo `enterprise` metti davanti al compose il reverse proxy con TLS descritto in `deploy/README.md`; senza proxy il web si ferma all'avvio invece di partire insicuro. |
| Già predisposto | Il controllo `x-lh-web-oidc-guard` ferma il web se l'emittente non è `https`; i passi del proxy sono in `deploy/README.md`. |
| Quando farla | Quando il compose diventa un modo supportato di installare in produzione, e non solo un riferimento. |
| Riferimenti | Q-392, Q-420, M8.3, M8.5 |

### TOBE-005 — Controlli sui dati personali dei tipi azione anche in ingestion

| Campo | Contenuto |
|---|---|
| Cosa | Rifiutare in `ingestion` (`POST` e `PUT /v1/event-types`) i campi e le chiavi d'esempio che fanno pensare a dati personali, con lo stesso elenco usato dal backoffice. I codici con prima parte `io` o `loyaltyhub` non fanno più parte di questa voce: `ingestion` li rifiuta già con `422` (Q-439, M8.0s). |
| Perché non ora | La fetta M8.0r tocca solo il web e la fetta M8.0s porta in `ingestion` solo i prefissi riservati. Il controllo dei dati personali è una fetta del servizio, con i suoi test di integrazione e la sua regola di errore (`422`). |
| Workaround attivo | Il backoffice blocca il salvataggio in ogni percorso (BO-09 e la scorciatoia da BO-06): nomi ed etichette dei campi, chiavi dell'esempio, prefissi riservati. Scrive `x-lh-pii: false` su ogni campo e non riscrive uno schema che dichiara `x-lh-pii: true`. `ingestion` rifiuta già i codici con prima parte `io` o `loyaltyhub` e ogni `type` in ingresso fuori da `io.loyaltyhub.action.` (Q-439). Il limite residuo è che una chiamata diretta a `POST` o `PUT /v1/event-types` con un token `MARKETING` o `ADMIN` salta i controlli sui dati personali. |
| Già predisposto | Le regole sono funzioni pure e testate (`web/lib/actiontypes/fields.ts` `looksPersonal`, `personalKeys`); la validazione del servizio passa già da un solo punto (`EventTypeService`), dove M8.0s ha aggiunto il controllo dei prefissi (`codeProblem`) e dove si aggiunge quello dei dati personali. |
| Quando farla | Prima di aprire `POST /v1/event-types` a integratori esterni o a strumenti diversi dal backoffice, oppure con la prossima fetta che tocca `ingestion`. |
| Riferimenti | Q-430, Q-435, Q-439, ADR-032, regola 10, M8.0r, M8.0s |

## Voci chiuse

Nessuna.
