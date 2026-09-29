# TB-SEC — Testbook funzionale: sicurezza applicativa

Dominio **sicurezza applicativa** del testbook funzionale (`docs/16 §10sexies`): input ostili sulle API vere dell'hub consolidato (iniezione SQL, byte NUL, input lunghi, path traversal, CRLF, espressioni di template) ed errori che non rivelano nulla dell'interno. È il minimo deterministico che gira a ogni `./mvnw verify`; il fuzzing di Schemathesis e lo ZAP API scan del workflow notturno `security-nightly` estendono le stesse regole a ogni operazione di `contracts/api` ([`docs/security/dast.md`](../security/dast.md)). Servizio: l'hub consolidato `deploy/hub` (profili `demo, inproc`).

- **Oracolo**: `docs/18 §3.10` punti 4 (iniezione SQL e accesso ai dati), 5 (validazione dell'input e degli output: template senza valutazione di espressioni, errori RFC 9457 senza stack trace né dettagli interni) e 12 (verifica continua: «sì su 5xx») · ADR-042 · `docs/06 §2` (errori) · `docs/12` M8 (accettazione: «payload di iniezione SQL dal fuzzing Schemathesis → nessun `5xx` né effetto») · OWASP ASVS 5.0 V1, V4, V16 (`docs/security/asvs.md`) · scelte registrate in `docs/15` (Q-530…Q-538). Mai «quello che il codice fa oggi».
- **AMBIGUO**: la specifica tace e `docs/15` non registra una scelta → la riga asserisce il comportamento attuale, il test lo dichiara con `// TESTBOOK: ambiguo, vedi …` e la riga è elencata in una sezione dedicata. Nessuna riga di questo dominio è ambigua: le due righe con stato «qualsiasi» (TB-SEC-ERR-006 e TB-SEC-ERR-007) non lasciano indeterminata la specifica, ne asseriscono solo la parte che la specifica decide (R-03: nessuna fuga).
- **Divergenza**: il test asserisce la specifica. Quando la correzione sta in codice fuori dalla fetta (`libs/lh-common/.../web/**` è riservato a M8.10f, o nei servizi) la riga porta `divergenza` = `Q-532` nel CSV e il test la **salta** (`Assumptions.abort`, esito «saltata» nel rapporto, mai «passata») finché non si corregge; l'elenco è in §6 e in `docs/16 §12` (Q-538).

## 0. Esecuzione

| Classe | Tipo | Dati (`src/test/resources/testbook/sec/`) | Aree |
|---|---|---|---|
| `deploy/hub` · `TestbookSecHubIT` | integrazione tra servizi (hub consolidato, profili `demo, inproc`, Postgres incorporato, un solo contesto Spring) | `fuz.csv`, `err.csv` | FUZ, ERR |

Comando: `./mvnw -pl deploy/hub -am verify -Dit.test=TestbookSecHubIT` (circa un minuto). Ogni caso ha nome `[<ID>] <descrizione>`; una riga = un caso eseguito. Le righe FUZ sono tre fabbriche di casi in ordine fisso: `hostileReads` (letture ostili, con impronta dello stato prima e dopo), `hostileWrites` (scritture ostili) ed `errorsWithoutLeaks` (righe ERR). Il test non chiama gli endpoint `/v1/portal/**`: li sta rifacendo M8.10f (il portale con `MemberPrincipal`), e la prova di BOLA arriva lì (§5).

## 1. Inventario delle regole

| Regola | Enunciato (sintesi) | Fonte | Righe |
|---|---|---|---|
| R-01 | Nessun input produce un `5xx`: SQL, NUL, valori enormi, percorsi e intestazioni ostili ricevono un errore del client o un esito normale, mai un errore del server | docs/18 §3.10 p.4, 5, 12 («sì su 5xx») · docs/12 M8 (accettazione) | FUZ (tutte), ERR-001…005 |
| R-02 | Un'iniezione non ha effetto: le letture lasciano invariato lo stato (impronta identica prima e dopo), un filtro ostile non allarga il risultato (nessun record del seed contiene quei testi), i template e i campi non si valutano come espressioni (`${7*7}` non diventa `49`), nessuna intestazione iniettata compare nella risposta | docs/18 §3.10 p.4, 5 · ADR-042 | FUZ (tutte) |
| R-03 | Le risposte d'errore non contengono stack trace né dettagli interni: nessun `trace`, `exception`, riga di stack (`at io.loyaltyhub`, `at org.`, `at java.`), classe di framework o driver (`org.springframework`, `org.postgresql`, `PSQLException`, `SQLSTATE`, `jdbc:`, `Hikari`), nome del contenitore (`Apache Tomcat`), percorso dell'immagine (`/opt/lh`) né testo SQL (`select … from`) | docs/18 §3.10 p.5 · docs/06 §2 | ERR (tutte), FUZ (tutte) |

## 2. Controlli notturni e righe

Il workflow `security-nightly` prova le stesse regole su ogni operazione; le righe di questo dominio sono la loro forma deterministica.

| Controllo notturno | Cosa cerca | Riga del testbook |
|---|---|---|
| Schemathesis `not_a_server_error` su ogni operazione di `contracts/api` | un `5xx` a input di forma o valore ostile (R-01) | FUZ (una riga per carico e bersaglio) |
| ZAP, regola 40018 (SQL Injection) e 90020 (Remote OS Command Injection) | iniezione SQL e di comandi nei parametri (R-01, R-02) | FUZ-001…003, 009…011, 017…019, 025…027, 033…035, 041…043, 049…051, 057…059 |
| ZAP, regola 6 (Path Traversal) | lettura di file fuori dalla radice (R-02) | FUZ-006, 014, 022, 030, 038, 046, 054, 062 |
| ZAP, regola 40003 (CRLF Injection) | intestazioni iniettate nella risposta (R-02) | FUZ-007, 015, 023, 031, 039, 047, 055, 063 |
| ZAP, regola 90035 e 90036 (Server-Side Template Injection) | valutazione di espressioni di template (R-02) | FUZ-008, 016, 024, 032, 040, 048, 056, 064 |
| ZAP, regola 90022 (Application Error Disclosure) e 10023 (Information Disclosure, debug error messages) | stack trace e dettagli interni nelle risposte d'errore (R-03) | ERR-001…007 e ogni riga FUZ con esito d'errore |

## 3. TB-SEC-FUZ — input ostili

Il valore del carico si legge nei CSV con questi nomi (il test li decodifica in `TestbookSecHubIT#payload`; in query e percorso si codificano in UTF-8 con lo spazio come `%20`, nel corpo JSON il byte NUL diventa `\u0000`):

| Nome | Testo | Cosa prova |
|---|---|---|
| `SQL_TAUTOLOGIA` | `' OR '1'='1' --` | filtro allargato da una tautologia |
| `SQL_IMPILATA` | `x'; DROP TABLE member.member; --` | seconda istruzione nel testo SQL |
| `SQL_UNION` | `') UNION SELECT current_user, version() --` | lettura di dati di altre tabelle |
| `NUL` | `a` NUL `b` (U+0000) | byte NUL in un testo che arriva a Postgres |
| `LUNGO_10K` | 10 000 volte `a` | input oltre ogni limite ragionevole |
| `TRAVERSAL` | `../../../../etc/passwd` | percorso fuori dalla radice |
| `CRLF` | `x`, a capo, `X-Injected: 1` | intestazione iniettata nella risposta |
| `ESPRESSIONE` | `${7*7}#{7*7}{{7*7}}` | espressione valutata da un motore di template |

Bersagli: le quattro ricerche libere `q` (membri, campagne, premi, eventi di insight), il dettaglio di un membro con il carico nel percorso, l'anteprima di un template messaggio (`titleTpl` e `bodyTpl`), la creazione di un membro (`firstName`, unica email `tb-sec-<id>@example.org`) e l'ingresso di un evento come la fonte `ecommerce` (`data.orderId`). Tabella decisionale completa 8 bersagli × 8 carichi (64 righe, `docs/16 §1.3`). **Atteso**: `NO_5XX` (stato < 500, nessuna fuga, nessuna intestazione `X-Injected`); `INVARIATO` (le quattro ricerche con stato 200 restituiscono un elenco vuoto, e l'impronta dello stato è identica prima e dopo la chiamata); `NON_VALUTATO` (nel template il risultato non contiene `49`); per la creazione del membro, se ha successo, il nome riletto non contiene `49`. Perché le righe non diventino vuote se un'autorizzazione cambia, il test verifica anche che il carico arrivi all'applicazione: le ricerche con carico SQL rispondono `200`, le due scritture (creazione del membro con `ADMIN`, ingresso come fonte `ecommerce`) non ricevono `401`, `403`, `404` né `429`.

**Limite del carico `LUNGO_10K` in query e percorso.** Per i bersagli in query o nel percorso (le quattro ricerche `q` e il dettaglio di un membro: `TB-SEC-FUZ-005`, `013`, `021`, `029`, `037`) 10 000 caratteri stanno nella riga di richiesta e superano il limite di Tomcat: risponde `400` prima che Spring o l'applicazione vedano la richiesta. Queste righe provano quindi il limite del contenitore e l'assenza di fughe nella sua pagina d'errore (R-01, R-03), non la gestione dell'input nell'applicazione; il carico lungo arriva all'applicazione solo nel corpo (`TB-SEC-FUZ-045`, `053`, `061`).

| Riga | Bersaglio | Carico | Atteso | Fonte | Test |
|---|---|---|---|---|---|
| TB-SEC-FUZ-001 | `GET /v1/members?q=` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-002 | `GET /v1/members?q=` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-003 | `GET /v1/members?q=` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-004 | `GET /v1/members?q=` | `NUL` | NO_5XX+INVARIATO — **divergenza Q-532** | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-005 | `GET /v1/members?q=` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-006 | `GET /v1/members?q=` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-007 | `GET /v1/members?q=` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-008 | `GET /v1/members?q=` | `ESPRESSIONE` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-009 | `GET /v1/campaigns?q=` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-010 | `GET /v1/campaigns?q=` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-011 | `GET /v1/campaigns?q=` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-012 | `GET /v1/campaigns?q=` | `NUL` | NO_5XX+INVARIATO — **divergenza Q-532** | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-013 | `GET /v1/campaigns?q=` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-014 | `GET /v1/campaigns?q=` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-015 | `GET /v1/campaigns?q=` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-016 | `GET /v1/campaigns?q=` | `ESPRESSIONE` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-017 | `GET /v1/rewards?q=` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-018 | `GET /v1/rewards?q=` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-019 | `GET /v1/rewards?q=` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-020 | `GET /v1/rewards?q=` | `NUL` | NO_5XX+INVARIATO — **divergenza Q-532** | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-021 | `GET /v1/rewards?q=` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-022 | `GET /v1/rewards?q=` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-023 | `GET /v1/rewards?q=` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-024 | `GET /v1/rewards?q=` | `ESPRESSIONE` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-025 | `GET /v1/events?q=` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-026 | `GET /v1/events?q=` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-027 | `GET /v1/events?q=` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-028 | `GET /v1/events?q=` | `NUL` | NO_5XX+INVARIATO — **divergenza Q-532** | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-029 | `GET /v1/events?q=` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-030 | `GET /v1/events?q=` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-031 | `GET /v1/events?q=` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-032 | `GET /v1/events?q=` | `ESPRESSIONE` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-033 | `GET /v1/members/{id}` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-034 | `GET /v1/members/{id}` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-035 | `GET /v1/members/{id}` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-036 | `GET /v1/members/{id}` | `NUL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-037 | `GET /v1/members/{id}` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-038 | `GET /v1/members/{id}` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-039 | `GET /v1/members/{id}` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-040 | `GET /v1/members/{id}` | `ESPRESSIONE` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-041 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `SQL_TAUTOLOGIA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-042 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `SQL_IMPILATA` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-043 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `SQL_UNION` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-044 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `NUL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-045 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `LUNGO_10K` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-046 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `TRAVERSAL` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-047 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `CRLF` | NO_5XX+INVARIATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-048 | `POST /v1/message-templates/MSG-POINTS-EARNED/render` | `ESPRESSIONE` | NO_5XX+NON_VALUTATO | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileReads` · `fuz.csv` |
| TB-SEC-FUZ-049 | `POST /v1/members` (`firstName`) | `SQL_TAUTOLOGIA` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-050 | `POST /v1/members` (`firstName`) | `SQL_IMPILATA` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-051 | `POST /v1/members` (`firstName`) | `SQL_UNION` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-052 | `POST /v1/members` (`firstName`) | `NUL` | NO_5XX — **divergenza Q-532** | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-053 | `POST /v1/members` (`firstName`) | `LUNGO_10K` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-054 | `POST /v1/members` (`firstName`) | `TRAVERSAL` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-055 | `POST /v1/members` (`firstName`) | `CRLF` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-056 | `POST /v1/members` (`firstName`) | `ESPRESSIONE` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-057 | `POST /v1/events` (`data.orderId`) | `SQL_TAUTOLOGIA` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-058 | `POST /v1/events` (`data.orderId`) | `SQL_IMPILATA` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-059 | `POST /v1/events` (`data.orderId`) | `SQL_UNION` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-060 | `POST /v1/events` (`data.orderId`) | `NUL` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-061 | `POST /v1/events` (`data.orderId`) | `LUNGO_10K` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-062 | `POST /v1/events` (`data.orderId`) | `TRAVERSAL` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-063 | `POST /v1/events` (`data.orderId`) | `CRLF` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |
| TB-SEC-FUZ-064 | `POST /v1/events` (`data.orderId`) | `ESPRESSIONE` | NO_5XX | R-01, R-02 · docs/18 §3.10 p.4, 5, 12 | `TestbookSecHubIT#hostileWrites` · `fuz.csv` |

## 4. TB-SEC-ERR — errori senza fughe

Errori di forma e di percorso su API vere: la risposta ha lo stato della specifica (`docs/06 §2`; 405 e 415 sono `bad-request`, Q-333) e nessun frammento della lista di R-03. Le righe 006 e 007 portano un byte NUL codificato (`%00`) in query e percorso: lo stato non è deciso dalla specifica (oggi 500 in query, per la divergenza di §6, e 400 nel percorso), e la riga asserisce solo la regola R-03.

| Riga | Richiesta | Atteso | Fonte | Test |
|---|---|---|---|---|
| TB-SEC-ERR-001 | `POST /v1/campaigns` | 400, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-002 | `GET /v1/members?page=abc` | 400, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-003 | `GET /v1/non-esiste` | 404, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-004 | `DELETE /v1/campaigns` | 405, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5; Q-333 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-005 | `POST /v1/members` | 415, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5; Q-333 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-006 | `GET /v1/members?q=a%00b` | qualsiasi stato, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |
| TB-SEC-ERR-007 | `GET /v1/members/a%00b` | qualsiasi stato, senza fughe | R-03 · docs/06 §2; docs/18 §3.10 p.5 | `TestbookSecHubIT#errorsWithoutLeaks` · `err.csv` |

## 5. Aree di `docs/18 §3.13` e dove si provano

`TB-SEC` copre le iniezioni e gli errori senza fughe. Le altre aree del testbook di sicurezza previsto da `docs/18 §3.10` punto 12 si provano dove indicato; quelle non ancora eseguibili sono pianificate con la fetta che introduce la funzione (nessuna riga finta oggi).

| Area | Dove si prova | Stato |
|---|---|---|
| Iniezioni e input ostili (SQL, NUL, lunghezza, traversal, CRLF, template) | `TB-SEC-FUZ` (questo documento) e il fuzzing notturno | eseguibile (M8.11c) |
| Errori senza stack trace né dettagli interni | `TB-SEC-ERR` (questo documento) e lo ZAP notturno | eseguibile (M8.11c) |
| BOLA (API1): il portale legge un membro qualunque | pianificato con M8.10f e M8.2 (Q-410, `MemberPrincipal`): il test `TB-SEC` non chiama `/v1/portal/**`, che M8.10f sta rifacendo | pianificato |
| BFLA (API5): un ruolo esegue azioni di un altro | `TB-GOV-GRD` (60 righe, guardia `@RequiresRole` e deny by default) e `TB-GOV-MAT` (100 righe, matrice capacità × ruolo di `docs/08 §2`) | eseguibile |
| *Mass assignment* di campi interni | pianificato con M8.10 (regola ArchUnit sui DTO e riga per campo interno) | pianificato |
| SSRF: destinazioni dei webhook | `TB-ENG-WURL` (62 righe, politica dell'URL) e `TB-ENG-WPRF` (9 righe, profili) | eseguibile |
| Firme del bus e produttori ammessi | pianificato con M8.10 (`PRODUCER_NOT_ALLOWED`, `SIGNATURE_INVALID`) | pianificato |
| Limite di frequenza | `TB-PLT-RLM` (5 righe, per IP sull'ingresso); il limite per membro (giocate, riscatti, registrazioni) arriva con M8.10 | parziale |
| Idempotenza (`Idempotency-Key`) | pianificato con M8.10 (F2-SEC-11): oggi nessuna riga di `TB-ING` la prova | pianificato |
| File caricati | pianificato con M8.10 (dimensione, tipo reale, antivirus) | pianificato |

## 6. Divergenze

Le righe con `divergenza` nel CSV hanno esito «saltata» finché la correzione non arriva. Ogni riga elenca la specifica, il comportamento osservato e la causa; il registro completo è `docs/16 §12`.

| Riga | Specifica | Comportamento osservato | Causa | Esito |
|---|---|---|---|---|
| TB-SEC-FUZ-004 · D-1 | docs/18 §3.10 p.4, 5, 12: nessun 5xx | `GET /v1/members?q=` con un byte NUL: `500 INTERNAL_ERROR` (la risposta non rivela nulla, R-03 rispettata) | `PSQLException: invalid byte sequence for encoding "UTF8": 0x00` da `MemberRepository.search` (ILIKE con il testo di `q`); il byte NUL non è mai rifiutato prima del database | **saltata**, Q-532 |
| TB-SEC-FUZ-012 · D-2 | docs/18 §3.10 p.4, 5, 12: nessun 5xx | `GET /v1/campaigns?q=` con un byte NUL: `500 INTERNAL_ERROR` (la risposta non rivela nulla, R-03 rispettata) | stessa causa, da `CampaignRepository.search`; il byte NUL non è mai rifiutato prima del database | **saltata**, Q-532 |
| TB-SEC-FUZ-020 · D-3 | docs/18 §3.10 p.4, 5, 12: nessun 5xx | `GET /v1/rewards?q=` con un byte NUL: `500 INTERNAL_ERROR` (la risposta non rivela nulla, R-03 rispettata) | stessa causa, da `RewardRepository.search`; il byte NUL non è mai rifiutato prima del database | **saltata**, Q-532 |
| TB-SEC-FUZ-028 · D-4 | docs/18 §3.10 p.4, 5, 12: nessun 5xx | `GET /v1/events?q=` con un byte NUL: `500 INTERNAL_ERROR` (la risposta non rivela nulla, R-03 rispettata) | stessa causa, da `EventStoreRepository.search` (`payload::text ILIKE`); il byte NUL non è mai rifiutato prima del database | **saltata**, Q-532 |
| TB-SEC-FUZ-052 · D-5 | docs/18 §3.10 p.4, 5, 12: nessun 5xx | `POST /v1/members` (`firstName` con un byte NUL: `500 INTERNAL_ERROR` (la risposta non rivela nulla, R-03 rispettata) | stessa causa, dall'inserimento in `member.member`; il byte NUL non è mai rifiutato prima del database | **saltata**, Q-532 |

Una sola causa spiega le cinque righe: il byte NUL (U+0000) arriva intatto a Postgres, che lo rifiuta. La correzione è un rifiuto con `400` (nel binding dei parametri o con un filtro di richiesta) e sta in codice condiviso o nei servizi, fuori dalla fetta. **Scelte registrate:** Q-532 (difetti trovati dal fuzzing, tra cui questi) e Q-538 (righe con divergenza: saltate, non tolte né lasciate rosse). Il fuzzing notturno trova la stessa causa su altre operazioni: sono nella baseline con scadenza (`.dast/schemathesis-baseline.json`, [dast.md](../security/dast.md)).

## 7. Verifica a mutazione

Mutazioni locali, **non committate**, ripristinate con `git checkout` subito dopo (nessuna entra nel repository): ogni mutazione introduce nel codice di produzione il difetto che una riga deve rilevare, e la riga deve diventare rossa.

| Mutazione | Codice mutato | Righe che diventano rosse |
|---|---|---|
| M1 — il dettaglio della risposta 500 riporta `ex.toString()` invece del messaggio generico | `GlobalExceptionHandler.onUnexpected` (`libs/lh-common`) | TB-SEC-ERR-006 (la `500` per il byte NUL mostra `org.springframework.dao.DataIntegrityViolationException` e il testo SQL) |
| M2 — la ricerca dei membri ignora il filtro se `q` contiene un apice: l'effetto di una tautologia SQL | `MemberRepository.filters` (`member-service`) | TB-SEC-FUZ-001, 002, 003 (l'elenco non è più vuoto: il filtro è stato allargato) |
| M3 — l'anteprima dei template valuta `${7*7}` e scrive `49` | `TemplateEngine.render` (`engagement-service`) | TB-SEC-FUZ-048 (il risultato contiene `49`) |
| M4 — la ricerca dei membri scrive nella risposta l'intestazione `X-Injected` quando c'è `q` | `MembersController.list` (`member-service`) | TB-SEC-FUZ-001, 002, 003, 006, 007, 008 (intestazione iniettata; la 004 è saltata e la 005 non arriva al controller: 400 di Tomcat per la riga di richiesta troppo lunga) |

Le quattro mutazioni sono state rilevate; le altre righe (percorsi, template, creazione, ingresso) restano verdi con ogni mutazione. Il rilevamento di una lettura che scrive (R-02) è affidato all'impronta dello stato di `TestbookPltSupportIT#fingerprint`, la stessa dell'oracolo del reset demo (`TB-PLT-RST`), e non a una mutazione dedicata.
