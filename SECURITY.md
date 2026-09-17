# Sicurezza · Security

## Segnalare una vulnerabilità · Reporting a vulnerability

Non aprire una issue pubblica. Usa la segnalazione privata di sicurezza del repository (GitHub → Security →
Report a vulnerability) oppure il contatto indicato nella pagina del progetto. Indica: componente, versione, passi per
riprodurre, impatto atteso. Risposta iniziale entro 5 giorni lavorativi; coordinamento della divulgazione con chi
segnala.

**EN** — Do not open a public issue. Use the repository's private security advisory (GitHub → Security → Report a
vulnerability) or the contact listed on the project page. Include: component, version, reproduction steps, expected
impact. Initial response within 5 business days; disclosure is coordinated with the reporter.

---

## Superficie di attacco e presidi · Attack surface and controls

| Superficie · Surface | Presidio · Control |
| --- | --- |
| API di ingestione · Ingestion API | Autenticazione per fonte, quote, validazione di schema, chiave di idempotenza obbligatoria · per-source authentication, quotas, schema validation, mandatory idempotency key |
| API di membro · Member API | Token del provider di identità; un membro accede solo ai propri dati · identity provider token; a member accesses only their own data |
| Rotte operatore · Operator routes | Prefisso dedicato e controllo di ruolo su ogni rotta · dedicated prefix and role check on every route |
| Backoffice · Back office | SSO aziendale, permessi per collezione e campo, workflow di approvazione, versioni · corporate SSO, per-collection and per-field permissions, approval workflow, versioning |
| Espressioni scritte dagli utenti · User-authored expressions | Contesto di sola lettura, nessuna riflessione, nessuna I/O · read-only context, no reflection, no I/O |
| Webhook in uscita · Outbound webhooks | Firma HMAC-SHA256, segreto rotabile, timestamp, tentativi con backoff · HMAC-SHA256 signature, rotatable secret, timestamp, backoff retries |
| Segreti · Secrets | Mai nel repository; gestore segreti del cloud + iniezione in cluster; la chiave dei concorsi è separata · never in the repository; cloud secret manager + in-cluster injection; contest key kept separate |
| Immagini · Images | Firma delle immagini, SBOM, scansione delle vulnerabilità in integrazione continua · image signing, SBOM, CI vulnerability scanning |
| Dati personali · Personal data | Fuori dal dominio loyalty; pseudonimizzazione nei segnali; warehouse senza anagrafica · outside the loyalty domain; pseudonymisation in telemetry; no master data in the warehouse |
| Concorsi · Contests | Generatore crittografico, registro a hash concatenato, configurazione bloccata a concorso avviato · cryptographic RNG, hash-chained log, configuration frozen while running |

---

## Buone pratiche per chi integra · Good practice for integrators

- Riprova sempre con la **stessa** chiave di idempotenza; una chiave nuova su un retry crea un doppio accredito.
- Verifica la firma dei webhook con confronto a tempo costante e accetta due segreti durante la rotazione.
- Non inviare dati personali negli attributi delle azioni: solo identificatori, hash e valori tecnici.
- Tieni i token fuori da log e URL.

**EN**

- Always retry with the **same** idempotency key; a fresh key on retry creates a duplicate credit.
- Verify webhook signatures with a constant-time comparison and accept two secrets during rotation.
- Do not send personal data in action attributes: identifiers, hashes and technical values only.
- Keep tokens out of logs and URLs.
