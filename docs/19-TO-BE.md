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
| Workaround attivo | Ogni ancoraggio giornaliero (`DAILY`) e ogni ancora della retention (`PURGE`) finisce anche nei log della piattaforma, fuori dal database: una riga `audit-anchor` sul logger dedicato `io.loyaltyhub.audit.anchor`, senza dati personali. `GET /v1/audit/verify?service=&seq=&hash=` confronta la catena con un'ancora copiata dai log e dice se coincide, se è stata riscritta, se le voci mancano o quando sono state cancellate; una voce cancellata non risulta mai presente (fetta M8.12a, testato in `AuditChainIT`). Il database fissa da sé l'istante di ogni ancora e crea le ancore `PURGE` solo quando cancella voci, con l'età della voce più recente cancellata: una cancellazione di voci più giovani di 180 giorni la verifica la segnala. Nel profilo `enterprise` insight rifiuta di partire con il job delle ancore spento o con quel logger sotto INFO; `deploy/README.md` dice all'installatore di instradarlo verso un archivio durevole. Così una riscrittura coerente di catena e ancore fatta con le credenziali del database si vede. Il limite residuo è che i log non sono firmati né immutabili: chi può riscrivere anche i log della piattaforma non lascia traccia; e fino alla contract di Q-403 chi ha le credenziali applicative può ancora alzare i controlli di sessione delle funzioni dell'audit. |
| Già predisposto | La catena di hash per servizio, la verifica e gli ancoraggi giornalieri nella tabella `audit_anchor` (fetta M8.12a). L'esportazione si aggiunge come nuovo consumatore degli ancoraggi (`AuditAnchorJob`, `AuditAnchorLog`); la verifica accetta già un'ancora esterna; il controllo di avvio `AuditAnchorGuard` è il punto in cui rendere obbligatoria l'esportazione. |
| Quando farla | Con il primo cliente che chiede evidenze di audit opponibili a terzi, oppure con il pacchetto di conformità di M12.6. |
| Riferimenti | Q-400, Q-403, ADR-043, F2-GRC-07, M8.12, M12.6 |

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

### TOBE-005 — Hash con chiave del contenuto dell'audit

| Campo | Contenuto |
|---|---|
| Cosa | Calcolare `content_hash` delle voci dell'audit con un HMAC e una chiave gestita (secret manager, rotazione con versione della chiave nella forma canonica), invece che con uno SHA-256 senza chiave. |
| Perché non ora | Serve una chiave che il database non conosce e che la verifica sa ritrovare anche dopo una rotazione: nella PoC non c'è un secret manager, e una chiave nella configurazione del servizio non proteggerebbe da chi ha già le credenziali. Cambiare l'hash cambia anche la forma canonica, quindi la versione dell'algoritmo di ogni voce. |
| Workaround attivo | L'anonimizzazione riscrive sintesi e diff delle voci, ma l'hash del contenuto originale resta nella voce per tenere la catena. Chi legge il database può confermare un contenuto che indovina (per esempio «Aggiornato » più un nome), non leggerlo. Per non moltiplicare i punti di conferma, la prova `REDACT` non porta più l'hash del contenuto precedente, solo quello nuovo, e l'API dell'audit non restituisce l'hash del contenuto delle voci. Il limite residuo è la conferma per tentativi di un dato già anonimizzato da parte di chi ha accesso in lettura al database o ai backup. |
| Già predisposto | La forma canonica porta la versione nelle etichette (`lh.audit.content.v1`, `lh.audit.entry.v1`) e ha un verificatore indipendente in Java (`AuditHashChain`), da cui parte una `v2` con HMAC. Manca ancora una colonna che dica la versione di ogni voce, così che `v1` e `v2` convivano nella stessa catena. |
| Quando farla | Con il primo cliente che conserva l'audit per più tempo del dato del membro, o prima di esporre backup del database a terzi. |
| Riferimenti | Q-401, Q-403, ADR-043, F2-GRC-07, M8.12 |

## Voci chiuse

Nessuna.
