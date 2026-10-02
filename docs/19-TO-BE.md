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

### TOBE-005 — Controlli sui dati personali dei tipi azione anche in ingestion

| Campo | Contenuto |
|---|---|
| Cosa | Rifiutare in `ingestion` (`POST` e `PUT /v1/event-types`) i campi e le chiavi d'esempio che fanno pensare a dati personali, con lo stesso elenco usato dal backoffice. I codici con prima parte `io` o `loyaltyhub` non fanno più parte di questa voce: `ingestion` li rifiuta già con `422` (Q-439, M8.0s). |
| Perché non ora | La fetta M8.0r tocca solo il web e la fetta M8.0s porta in `ingestion` solo i prefissi riservati. Il controllo dei dati personali è una fetta del servizio, con i suoi test di integrazione e la sua regola di errore (`422`). |
| Workaround attivo | Il backoffice blocca il salvataggio in ogni percorso (BO-09 e la scorciatoia da BO-06): nomi ed etichette dei campi, chiavi dell'esempio, prefissi riservati. Scrive `x-lh-pii: false` su ogni campo e non riscrive uno schema che dichiara `x-lh-pii: true`. `ingestion` rifiuta già i codici con prima parte `io` o `loyaltyhub` e ogni `type` in ingresso fuori da `io.loyaltyhub.action.` (Q-439). Il limite residuo è che una chiamata diretta a `POST` o `PUT /v1/event-types` con un token `MARKETING` o `ADMIN` salta i controlli sui dati personali. |
| Già predisposto | Le regole sono funzioni pure e testate (`web/lib/actiontypes/fields.ts` `looksPersonal`, `personalKeys`); la validazione del servizio passa già da un solo punto (`EventTypeService`), dove M8.0s ha aggiunto il controllo dei prefissi (`codeProblem`) e dove si aggiunge quello dei dati personali. |
| Quando farla | Prima di aprire `POST /v1/event-types` a integratori esterni o a strumenti diversi dal backoffice, oppure con la prossima fetta che tocca `ingestion`. |
| Riferimenti | Q-430, Q-435, Q-439, ADR-032, regola 10, M8.0r, M8.0s |

### TOBE-006 — Hash con chiave del contenuto dell'audit

| Campo | Contenuto |
|---|---|
| Cosa | Calcolare `content_hash` delle voci dell'audit con un HMAC e una chiave gestita (secret manager, rotazione con versione della chiave nella forma canonica), invece che con uno SHA-256 senza chiave. |
| Perché non ora | Serve una chiave che il database non conosce e che la verifica sa ritrovare anche dopo una rotazione: nella PoC non c'è un secret manager, e una chiave nella configurazione del servizio non proteggerebbe da chi ha già le credenziali. Cambiare l'hash cambia anche la forma canonica, quindi la versione dell'algoritmo di ogni voce. |
| Workaround attivo | L'anonimizzazione riscrive sintesi e diff delle voci, ma l'hash del contenuto originale resta nella voce per tenere la catena. Chi legge il database può confermare un contenuto che indovina (per esempio «Aggiornato » più un nome), non leggerlo. Per non moltiplicare i punti di conferma, la prova `REDACT` non porta più l'hash del contenuto precedente, solo quello nuovo, e l'API dell'audit non restituisce l'hash del contenuto delle voci. Il limite residuo è la conferma per tentativi di un dato già anonimizzato da parte di chi ha accesso in lettura al database o ai backup. |
| Già predisposto | La forma canonica porta la versione nelle etichette (`lh.audit.content.v1`, `lh.audit.entry.v1`) e ha un verificatore indipendente in Java (`AuditHashChain`), da cui parte una `v2` con HMAC. Manca ancora una colonna che dica la versione di ogni voce, così che `v1` e `v2` convivano nella stessa catena. |
| Quando farla | Con il primo cliente che conserva l'audit per più tempo del dato del membro, o prima di esporre backup del database a terzi. |
| Riferimenti | Q-401, Q-403, ADR-043, F2-GRC-07, M8.12 |

### TOBE-007 — Keycloak senza vulnerabilità note e bloccato per digest

| Campo | Contenuto |
|---|---|
| Cosa | Portare il ruolo `idp` a una versione di Keycloak che corregge le vulnerabilità note delle librerie incluse e fissarne l'immagine con tag e digest, come le altre immagini di terze parti. |
| Perché non ora | Keycloak 26.7.4 è l'ultima versione pubblicata e nessuna patch corregge ancora `netty-handler` (CVE-2026-75595), `freemarker` (CVE-2026-84939), `bcprov-jdk18on` (CVE-2026-8763, CVE-2026-13506) e `pcre2` di UBI 9.8. Un'immagine derivata con le librerie sostituite uscirebbe dal supporto di Keycloak. Il digest non si può fissare perché `quay.io` non è raggiungibile dall'ambiente delle fette. |
| Workaround attivo | Keycloak 26.7.4 con tag fisso nei due compose e nel chart. Dependabot propone le patch in `deploy/docker-compose.yml`; `scripts/check-helm.mjs` fa fallire il job `helm` finché compose di riferimento e values del chart non hanno lo stesso tag (Q-483); il job è obbligatorio nel ruleset, quindi la PR non si unisce finché non sono allineati. Nel compose di riferimento la porta di Keycloak è su `127.0.0.1` di default (`LH_BIND_ADDRESS`); nel chart l'Ingress espone solo `/realms/<realm>/` e `/resources/`, non la console di amministrazione. `mssql-jdbc` (CVE-2025-59250) non si carica con `KC_DB=postgres`. Il limite residuo sono le vulnerabilità delle librerie raggiungibili dal login finché non esce la patch. |
| Già predisposto | Il tag è in un solo valore per file (`image` dei compose, `roles.idp.image.tag` del chart) e il controllo di coerenza rende l'aggiornamento un cambio di tre righe. Il ruolo `idp` dell'immagine unica (Q-374) potrà sostituire l'immagine ufficiale senza cambiare i values (`image: {}`). |
| Quando farla | Appena esce una patch di Keycloak 26.x che corregge le vulnerabilità elencate, o con la prima fetta che gira in un ambiente che raggiunge `quay.io` (solo il digest). |
| Riferimenti | Q-482, Q-483, Q-374, ADR-027, ADR-044, M8.2 |

### TOBE-008 — Installazione in CI anche nel profilo enterprise

| Campo | Contenuto |
|---|---|
| Cosa | Far girare il job `helm install (kind)` anche nel profilo `enterprise`, il default del chart: login OIDC del web, token delle fonti per lo smoke, Ingress con TLS. |
| Perché non ora | Lo smoke nel profilo `enterprise` ha bisogno di un token di una fonte (client `private_key_jwt` di Keycloak con un JWKS raggiungibile dal cluster) e il web di un emittente `https` raggiungibile con l'URL del browser, quindi di un Ingress controller e di una CA di prova nota a Node. Sono tre componenti in più nel job (server del JWKS, cert-manager, Ingress controller) e un cluster kind a un nodo li regge a fatica insieme a Kafka, Postgres e Keycloak. |
| Workaround attivo | Il job installa il chart nel profilo `demo` con tutti i ruoli, Strimzi, CloudNativePG, Pod Security `restricted` e il gateway, ed esegue `scripts/smoke.sh` attraverso l'`HTTPRoute`. Le regole del profilo `enterprise` (emittente e origine `https`, Secret del BFF, una replica del web) le verifica `scripts/check-helm.mjs` sul rendering del chart, nel job `helm` obbligatorio. Restano fuori da un'installazione reale: login del web, validazione dei token nell'hub e Ingress. Sul taglio compose li copre il job `smoke enterprise (compose, OIDC)` (M8.14 V6): profilo `enterprise`, login OIDC reale con OTP dal BFF, token verificati dall'hub, proxy TLS con una CA di prova; per il chart resta da fare. |
| Già predisposto | I valori del job sono in un file solo (`deploy/helm/loyaltyhub/ci/kind-values.yaml`); il job crea già i Secret di Keycloak, e il ruolo `idp` parte e importa il realm. Per il profilo `enterprise` bastano `global.profile`, i Secret del BFF e i passi in più nel job. |
| Quando farla | Con i journey di M9 (`e2e/`), che ottengono i token, o prima del primo rilascio del chart verso un'installazione reale. |
| Riferimenti | Q-491, Q-490, ADR-026, ADR-027, F2-DIST-02, M8.3, M9 |

### TOBE-009 — Client Keycloak creato con la fonte di ingestion

| Campo | Contenuto |
|---|---|
| Cosa | Creare in automatico il client `src-<codice>` di Keycloak (client credentials `private_key_jwt`, utenza di servizio con il ruolo `SOURCE`) quando un amministratore aggiunge una fonte da BO-09, senza passare dalla console di Keycloak. |
| Perché non ora | Ingestion dovrebbe chiamare l'API di amministrazione di Keycloak: una nuova destinazione di rete in uscita, con credenziali di amministrazione del realm in un servizio di dominio (CLAUDE.md §7, *Fermati e chiedi*). Serve un'ADR, e la scelta tra provisioning da ingestion e comando della CLI `lh` (M12.2) non è ancora fatta. |
| Workaround attivo | Ogni fonte HTTP del seed ha già il suo client `src-<codice>` nel realm (`deploy/idp/realm.json`), con il solo ruolo `SOURCE`. Per una fonte creata a runtime l'amministratore esegue la procedura di `deploy/README.md`: crea il client `src-<codice>` con `private_key_jwt`, importa il JWKS o il certificato della fonte, assegna `SOURCE` all'utenza di servizio e verifica l'audience `hub`. Finché non lo fa la fonte non ha un'identità e in `enterprise` non può inviare azioni (fail-closed: nessun client, nessun token). Nel profilo `demo` non serve: basta `X-LH-Actor: SOURCE:src-<codice>`. |
| Già predisposto | La convenzione `src-<codice>` ⇔ codice della fonte è in un solo punto (`ActorContext.SOURCE_CLIENT_PREFIX`) e `scripts/check-realm.mjs` la verifica sul realm; il legame client e fonte è controllato dal servizio (`SOURCE_MISMATCH`) senza dipendere da come il client è stato creato. |
| Quando farla | Con la prima installazione in cui gli amministratori aggiungono fonti da BO-09 più spesso di quanto sia sostenibile a mano, o con la CLI `lh` (M12.2). |
| Riferimenti | Q-494, Q-492, ADR-027, ADR-042, F2-SEC-07, F2-IAM-02, M8.2f, M12.2 |

### TOBE-010 — Pacchetto di rilascio dell'immagine: VEX, SLSA livello 3 e verifica all'ammissione

| Campo | Contenuto |
|---|---|
| Cosa | Completare le evidenze di supply chain di ogni rilascio: documento VEX per le CVE note e non sfruttabili, provenienza SLSA livello 3, pacchetto pubblicato accanto all'immagine (SBOM, VEX, firme, rapporti, note di sicurezza) e verifica delle firme e dell'SBOM all'ammissione nel cluster. |
| Perché non ora | La pipeline di rilascio (M12.4) e il pacchetto di conformità (M12.6) non esistono ancora. Il livello 3 chiede un workflow di build riusabile e isolato, con la verifica delle sue garanzie. Il VEX ha senso quando c'è un processo che decide, per ogni CVE, se è sfruttabile. L'SBOM è per piattaforma (Q-511), quindi la verifica per tag all'ammissione non lo trova. |
| Workaround attivo | Su ogni PR: SBOM, scansione e rapporto. Sui tag `v*` anche firma per digest, attestazioni e verifica. Le CVE accettate sono voci con motivo, riferimento e scadenza entro 90 giorni in `deploy/image/.trivyignore.yaml`. Limite residuo: SBOM e rapporti sono artefatti del workflow per 90 giorni, non un pacchetto di rilascio; nessun VEX; nessuna verifica all'ammissione nel chart. |
| Già predisposto | Tutto è per digest, quindi il pacchetto si compone dagli stessi artefatti; il rapporto JSON di Trivy e l'SBOM sono già prodotti per piattaforma; `docs/security/supply-chain.md` e `deploy/README.md` («Verificare l'immagine») descrivono i comandi di verifica; l'identità del certificato è fissa (`image.yml` sul tag). |
| Quando farla | Con la fetta M12.4 (pipeline di rilascio) per il pacchetto e il VEX, con M12.6 per SLSA livello 3, e prima della verifica all'ammissione opzionale del chart. |
| Riferimenti | Q-510, Q-511, Q-512, Q-513, ADR-038, ADR-044, F2-SEC-03, F2-DIST-08, F2-GRC-08, M8.5, M12.4, M12.6 |

### TOBE-011 — TLS anche sulla rete interna della vetrina enterprise

| Campo | Contenuto |
|---|---|
| Cosa | Cifrare e autenticare anche il traffico HTTP dentro la rete compose della vetrina: proxy → `web` e `idp`, `web` → `hub` (con il Bearer dell'operatore), Keycloak → back-channel logout. |
| Perché non ora | Servirebbe TLS fino ai container (certificati per `web`, `hub` e Keycloak, o una mesh con mTLS): è il lavoro di M8.5 per tutti i tagli, e un sidecar per servizio non entra nei limiti della vetrina senza un componente nuovo (regola 8-bis). |
| Workaround attivo | Overlay `deploy/vetrina/compose.vetrina.yml` (fetta M8.14c): dall'esterno passa solo il proxy TLS con certificati ACME; bus e database sono in TLS con la CA locale (Kafka con certificato del client, Postgres `verify-full`, Q-621); il JWKS dell'hub e le chiamate del web all'emittente passano dal proxy in https grazie agli alias di rete; web e Keycloak ascoltano sull'host solo su `127.0.0.1`. Tutto sta su un solo host, in una rete bridge senza altri container. Il limite residuo: chi ottiene accesso alla rete compose dell'host (root sull'host o un container compromesso nella stessa rete) legge in chiaro le richieste HTTP interne, compresi i token degli operatori. |
| Già predisposto | La CA locale e il provisioning dei certificati di `vetrina.sh provision` emettono già certificati per nome di servizio; aggiungere `web`, `hub` e `idp` è una riga per certificato. Il proxy parla già con i servizi per nome. |
| Quando farla | Con M8.5 (mTLS di mesh e principal per modulo), o prima di ospitare nella vetrina altri container non nostri. |
| Riferimenti | Q-392, Q-420, Q-621, ADR-049, ADR-042, F2-DIST-09, M8.5, M8.14 |

### TOBE-012 — Host sempre acceso per la vetrina enterprise

| Campo | Contenuto |
|---|---|
| Cosa | Tenere la vetrina enterprise raggiungibile 24 ore su 24, su un host con almeno 8 GB di memoria, senza che il proprietario la accenda prima di ogni demo. |
| Perché non ora | Nessun piano gratuito disponibile al proprietario regge lo stack (circa 6,3 GB a regime): Oracle Cloud A1 non risulta utilizzabile a costo zero dal suo account, Render e Vercel gratuiti sono a 512 MB, e un host a pagamento rompe la regola 8 (ADR-050). |
| Workaround attivo | Vetrina in un GitHub Codespace accesa su richiesta dal browser, con lo stesso compose e lo stesso overlay, TLS pubblico dall'inoltro delle porte di GitHub e uso bloccato a fine quota (ADR-050, Q-660…Q-663, fetta M8.14 V7). Limite residuo: spenta fuori dalle demo, URL legato al nome del codespace. |
| Già predisposto | `deploy/vetrina/` (overlay, `vetrina.sh`, runbook, timer di azzeramento) funziona su qualunque host Linux con Docker: il passaggio a un host fisso è una nuova installazione, senza cambiare il codice. |
| Quando farla | Quando il proprietario accetta un costo con un'ADR di deroga alla regola 8, oppure quando un piano gratuito con almeno 8 GB diventa disponibile al suo account. |
| Riferimenti | ADR-049, ADR-050, Q-615, Q-660, Q-662, F2-DIST-09, M8.14 |

## Voci chiuse

Nessuna.
