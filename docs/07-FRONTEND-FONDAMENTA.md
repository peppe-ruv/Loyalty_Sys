# 07 — Frontend: fondamenta

Vale per tutta l'app `web/`. Le schermate sono in `docs/08` (backoffice) e `docs/09` (portale).

## 1. Stack
| Cosa | Scelta |
|---|---|
| Framework | Next.js (App Router) · React 19 · TypeScript `strict` · `pnpm` |
| Stile | Tailwind CSS v4 con token in `@theme` · shadcn/ui (copiato in `components/ui`) · `lucide-react` |
| Dati | TanStack Query v5 (cache, polling, invalidazioni) · TanStack Table v8 |
| Form | `react-hook-form` + `zod` (gli schemi zod sono anche i tipi delle API) |
| Grafici | Recharts |
| Animazione | CSS + `motion` solo per ruota/gratta e tessera; tutto rispetta `prefers-reduced-motion` |
| Test | Vitest + Testing Library (unità), Playwright (3 percorsi E2E: `docs/12`) |
| Tipi API | scritti a mano in `lib/api/types/<servizio>.ts` (generazione da OpenAPI: P1) |

**Una sola app**, tre aree. `backoffice/` e `portal/` non si importano a vicenda; il codice comune vive in `components/shared` e `lib/`.

## 2. Struttura
```
web/
  app/
    page.tsx                      # HUB-01 Demo Hub
    backoffice/(shell)/…          # layout con sidebar, selettore persona, rail eventi
    portal/(shell)/…              # layout mobile-first con tab bar e tray demo
    api/lh/[service]/[...path]/route.ts   # proxy verso i microservizi
    api/demo/status/route.ts      # stato aggregato
    api/demo/wake/route.ts        # risveglio
  components/{ui,shared,backoffice,portal}/
  lib/
    api/{client.ts, keys.ts, types/*, hooks/*}
    persona/{personas.ts, cookie.ts, permissions.ts}
    realtime/{sse.ts, useLiveEvents.ts, usePendingTrace.ts}
    format/{points.ts, dates.ts}   # Intl it-IT, Europe/Rome
  styles/{tokens.css, backoffice.css, portal.css}
  public/demo/                    # immagini seed
```

## 3. Proxy e accesso ai servizi
- Il browser chiama **sempre** `/api/lh/<service>/v1/...`; il route handler inoltra a `LH_SVC_<SERVICE>_URL` (es. `LH_SVC_WALLET_URL`), copia metodo, query, corpo, e **aggiunge** `X-LH-Actor` leggendo il cookie persona (profilo `demo`) oppure `Authorization: Bearer` dalla sessione del BFF (profilo `enterprise`, §4-bis), `X-Correlation-Id` (nuovo ULID se assente). Dal browser passano solo `content-type` e `idempotency-key` (`lib/api/proxyHeaders.ts`): mai `Authorization`, `X-LH-Actor`, `X-LH-Member` o cookie. **`X-LH-Member` (profilo `demo`, ADR-048, Q-555):** sulle sole API del portale (`/v1/portal/**`) il proxy aggiunge `X-LH-Member: MBR-nnnnnn` col membro della persona: quello della persona `MEMBER` e, **senza cookie** (visitatore anonimo del portale), `MBR-000002` (`lib/persona/demoMember.ts`, lo stesso id che il layout del portale mostra, §4); con una **persona da operatore (`BO`) l'header non c'è mai** (Q-560, ADR-048 punto 6: un operatore non agisce mai come membro). Un id del cookie che non ha la forma `MBR-nnnnnn` non si inoltra: senza header i servizi si comportano come prima. Sugli altri percorsi l'header non c'è, nemmeno nelle chiamate che il proxy fa in proprio (i soprannomi a member-service), e il percorso a valle è validato in **entrambi i profili** (F2-SEC-03, ADR-042, `lib/api/proxyPath.ts`): ogni segmento solo `[A-Za-z0-9._~-]`, mai vuoto, `.` o `..` (Next decodifica `%2F` e `%2e%2e`, `new URL()` normalizza `..`), altrimenti `400 INVALID_PATH` senza chiamare il servizio; identità e `X-LH-Member` si decidono quindi sul percorso esatto che parte. Così BO-17 (`/v1/portal/campaigns?codes=`, `OPTIONAL` in docs/06 §3.4) riceve la vista generica (Q-560, decisa da Giuseppe il 2026-09-29, alternativa A); il portale aperto con una persona BO mostra comunque `MBR-000002` (solo interfaccia) e lavora col `memberId` esplicito, che in `demo` resta valido. Quello scelto dal browser, in qualunque grafia, si scarta sempre. Il `memberId` in query, percorso o corpo resta valido in `demo` e le pagine usano lo stesso id, che viene dalla stessa persona: se un servizio vede due membri diversi risponde `400 MEMBER_MISMATCH` (docs/06 §3.4). Nel profilo `enterprise` l'header non viaggia mai (§4-bis). Eccezione del solo profilo `demo` (Q-492): sull'ingresso delle azioni (`POST /v1/events`, `/v1/events/batch`, `/v1/transactions`), che accetta solo il ruolo `SOURCE`, il proxy presenta `X-LH-Actor: SOURCE:src-<codice>` con la fonte che l'evento dichiara, al posto della persona (`lib/api/demoSource.ts`, usato dal pannello demo del portale); `SOURCE` non è mai una persona del web. `Accept` lo decide il proxy: `application/json`, oppure `text/csv, application/problem+json` per i file `….csv` (vincitori BO-14, rapporto import BO-32). `HEAD` si inoltra come `HEAD`, senza corpo come `GET` e con la stessa richiesta al servizio; al browser arriva il `content-length` del servizio, se il proxy non riscrive il corpo. Le risposte `204`, `205` e `304` del servizio arrivano al browser con lo stesso stato e senza corpo. Timeout 25 s. Niente CORS sui servizi per le chiamate REST.
- Eccezione: **SSE** va diretto a `NEXT_PUBLIC_LH_INSIGHT_URL/v1/stream/events` (le funzioni serverless non reggono connessioni lunghe). Se l'SSE fallisce 3 volte → **polling** di `/v1/events?from=<ultimo>` ogni 3 s, con indicatore "live ridotto".
- `503/502/504` o errore di rete dal proxy → risposta `{type: "SERVICE_ASLEEP", service}`: l'UI mostra lo stato *degraded* (§6) e innesca `wake`.

```mermaid
sequenceDiagram
  accTitle: Identità del proxy verso i servizi nei due profili
  accDescr: Il browser chiama il proxy con il cookie persona; il proxy scarta ogni header d'identità del browser, compreso X-LH-Member. Nel profilo demo aggiunge X-LH-Actor e, solo sulle API del portale, X-LH-Member col membro della persona MEMBER (MBR-000002 senza cookie, mai con una persona da operatore); sugli altri percorsi solo X-LH-Actor. Nel profilo enterprise manda solo il Bearer della sessione e mai X-LH-Member.
  autonumber
  actor B as Browser
  participant W as web (proxy /api/lh)
  participant S as Servizio

  B->>W: richiesta con cookie persona e, se ostile, X-LH-Member
  W->>W: elenco chiuso di header, X-LH-Member del browser scartato
  alt profilo demo, percorso /v1/portal/**, persona MEMBER o senza cookie
    W->>S: X-LH-Actor e X-LH-Member (id della persona MEMBER, senza cookie MBR-000002)
  else profilo demo, percorso /v1/portal/**, persona da operatore
    W->>S: solo X-LH-Actor, mai X-LH-Member (Q-560)
  else profilo demo, altro percorso
    W->>S: solo X-LH-Actor
  else profilo enterprise
    W->>S: solo Authorization Bearer, mai X-LH-Member
  end
  S-->>W: risposta
  W-->>B: risposta
```

## 4. Identità simulata (nessuna login)
- Cookie `lh_persona` (JSON, 30 giorni): `{kind: "BO", username, role}` oppure `{kind: "MEMBER", memberId}`. Default: `marta.admin` nel backoffice, `MBR-000002` nel portale. Il **membro attivo** del portale (`lib/persona/demoMember.ts`) è quello della persona `MEMBER`, altrimenti `MBR-000002`: lo usa il layout del portale per mostrare e per le chiavi di cache. Il proxy lo manda ai servizi in `X-LH-Member` sulle API del portale con una persona `MEMBER` e senza cookie, **mai con una persona `BO`**: per un operatore `MBR-000002` è solo interfaccia (un ripiego per non mostrare un portale vuoto), non un'identità (§3, Q-555, Q-560).
- **Selettore persona** sempre visibile: nel backoffice in alto a destra (avatar con iniziali + ruolo in chiaro); nel portale dentro il tray demo (PT-14). Cambiare persona invalida tutta la cache di Query.
- I permessi (`lib/persona/permissions.ts`, matrice in `docs/08 §2`) **nascondono o disabilitano** le azioni; il backend le rifiuta comunque con `403` (`@RequiresRole`). Un'azione disabilitata mostra in tooltip il ruolo richiesto.
- Banner fisso in fondo al Demo Hub: "Ambiente dimostrativo: dati fittizi, nessuna autenticazione".

## 4-bis. Identità reale nel profilo `enterprise`: BFF con sessione lato server (M8.2, ADR-027)
Vale solo con `LH_PROFILE=enterprise`; senza, tutto il §4 resta com'è (la demo ospitata non cambia). Il server Next è il client OIDC **confidential** `web` del realm (`deploy/idp/realm.json`) e fa da *Backend-for-Frontend* (docs/18 §3.2). Codice in `web/lib/auth/`.

- **Configurazione** (variabili in `docs/11 §8`): `LH_OIDC_ISSUER`, `LH_WEB_CLIENT_ID` (predefinito `web`), `LH_WEB_CLIENT_SECRET`, `LH_WEB_URL`, `LH_WEB_SESSION_KEY` (32 byte casuali in base64); i segreti anche da `*_FILE`. Configurazione assente o insicura (segreto corto o segnaposto, chiave non casuale, `http` fuori da `localhost`, `LH_PROFILE` sconosciuto) ⇒ il server **non parte** (`INSECURE_CONFIG`, `instrumentation.ts`) e, se ci arriva comunque, ogni richiesta autenticata risponde `500 INSECURE_CONFIG`: mai un ripiego sul demo (regola 22).
- **Login**: `GET /api/auth/login?returnTo=/percorso` → Authorization Code con PKCE `S256`, `state` e `nonce`; lo stato del login viaggia in un cookie `__Host-lh_auth` cifrato (AES-256-GCM, 10 minuti). `returnTo` è accettato solo come percorso relativo della stessa origine, fuori da `/api` (niente open redirect); altrimenti `/`. `GET /api/auth/callback` verifica `state` e `iss`, scambia il codice, valida l'ID token (firma, `iss`, `aud`, scadenza, `nonce`) e apre una sessione **nuova** (l'eventuale sessione precedente del browser si chiude: niente session fixation). Errori → `/auth/error?reason=expired|denied|rejected|idp_unavailable` con «Riprova».
- **Sessione**: nel browser solo `__Host-lh_session` (id opaco da 256 bit, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`) e `__Host-lh_csrf` (leggibile dal JavaScript, per l'header CSRF). Token (access, refresh, ID) solo lato server, nello store delle sessioni cifrato con una chiave derivata da `LH_WEB_SESSION_KEY` e legato all'id; l'id stesso è conservato come impronta SHA-256. Inattività 30 minuti e durata massima 10 ore (`LH_WEB_SESSION_IDLE_SECONDS`, `LH_WEB_SESSION_MAX_SECONDS`, Q-354); al massimo 10 sessioni per account (esce la sua più vecchia) e `LH_WEB_SESSION_MAX_COUNT` in tutto. Store in memoria per una replica (Q-409), condiviso da route handler e pagine dello stesso processo: la configurazione si riconosce dall'impronta dei valori, non dall'oggetto (Next carica i moduli in più istanze).
- **Rinnovo**: trasparente, 30 s prima della scadenza dell'access token (5 minuti nel realm), con il refresh token a rotazione; **un solo rinnovo per sessione** anche con molte richieste parallele (con la rotazione, due rinnovi in gara farebbero revocare la sessione dall'IdP). Refresh token rifiutato ⇒ sessione chiusa e `401`; IdP irraggiungibile ⇒ `503 IDP_UNAVAILABLE`, la sessione resta.
- **Proxy `/api/lh`**: senza sessione ⇒ `401 {code: "UNAUTHENTICATED"}` e la UI (`app/providers.tsx`) porta al login riportando alla pagina corrente; con sessione ⇒ `Authorization: Bearer <access token>` aggiunto lato server; risposte `Cache-Control: private, no-store`. Il percorso inoltrato è esattamente quello chiesto: ogni segmento solo `[A-Za-z0-9._~-]`, mai vuoto, `.` o `..` (niente `%2F`, `%2e%2e`, `;` di matrice, `\`) ⇒ altrimenti `400 INVALID_PATH`. `X-Correlation-Id` del browser accettato solo se `[A-Za-z0-9-]{1,64}`, altrimenti nuovo ULID (in entrambi i profili). Un account di solo membro chiama solo `/v1/portal/**` (`403 FORBIDDEN_ROLE` altrove). Sulle API del portale **il membro viene solo dal token** (regole 6-bis e 18) e il BFF **rifiuta**, non ripulisce: `memberId` in query (ogni grafia) ⇒ `400 MEMBER_FROM_TOKEN`; corpo non vuoto solo `application/json` (`415` per form, multipart e altro) e un solo oggetto JSON (`400 INVALID_BODY` con testo in coda, array, JSON non valido); `memberId` a qualunque livello del corpo ⇒ `400 MEMBER_FROM_TOKEN`; `memberId` nel percorso (`/v1/portal/wallets/{id}`, `/v1/portal/members/{id}`) ⇒ `403 MEMBER_FROM_TOKEN`. `X-LH-Member` non esiste in questo profilo (ADR-048, Q-555): il proxy non lo manda mai, con nessun token, e quello del browser si scarta come ogni header fuori elenco; su un `@MemberEndpoint`, o con un token di solo membro, un servizio che lo ricevesse risponderebbe `400 MEMBER_FROM_TOKEN` (docs/06 §3.4), e resta il rifiuto di `memberId` fatto qui. **Il portale enterprise non va esposto finché M8.10 non lega il membro al token nei servizi** (Q-410): questi controlli sono difesa in profondità, non il controllo di proprietà.
- **CSRF**: su ogni richiesta che cambia stato (`POST`, `PUT`, `PATCH`, `DELETE`) verso `/api/lh` e `/api/auth/logout`: `Sec-Fetch-Site` (se presente) = `same-origin`, `Origin` = origine di `LH_WEB_URL`, token = HMAC dell'id di sessione, nell'header `X-LH-CSRF` (lo aggiunge `lhFetch` dal cookie `__Host-lh_csrf`) o, per il logout, nel campo `csrf` del modulo. Errore ⇒ `403 CSRF_REJECTED` (logout: 303 verso `/auth/error?reason=logout_failed`).
- **Logout**: «Esci» nella barra del backoffice e del portale è un modulo inviato a pagina intera a `POST /api/auth/logout`: il BFF chiude la sessione e risponde `303` verso il logout dell'IdP con `id_token_hint` (RP-initiated logout). L'ID token va dal server all'IdP nell'header `Location` e non passa mai dal JavaScript (regola 20). **Back-channel logout**: l'IdP chiama `POST /api/auth/backchannel-logout` con un logout token firmato (verificati firma dal JWKS, `iss`, `aud=web`, `iat` recente, `exp`, `jti` non già visto, evento di back-channel logout, niente `nonce`); il BFF chiude le sessioni con quel `sid` o, senza `sid`, tutte quelle del `sub` (deprovisioning, docs/18 §3.15). Token non valido o già usato ⇒ `400`; JWKS dell'IdP non scaricabile ⇒ `503` (l'IdP può riprovare).
- **Due realm (ADR-051)**: con `LH_OIDC_MEMBER_ISSUER` (più `LH_WEB_MEMBER_CLIENT_ID`, predefinito `portal`, e `LH_WEB_MEMBER_CLIENT_SECRET` o `_FILE`) il portale fa login nel realm `loyaltyhub-members` con il client `portal`, il backoffice resta nel realm `loyaltyhub` con `web` (`web/lib/auth/realm.ts`). Le due sessioni sono separate: cookie `__Host-lh_msession`, `__Host-lh_mcsrf` e `__Host-lh_mauth` per il membro, chiavi CSRF e di stato del login distinte, store distinti. Il realm si sceglie dalla pagina (`/api/auth/login?returnTo=/portal…` o `realm=members`), dalle API (`/v1/portal/**` con la sessione del membro, le altre con quella dell'operatore; `lhFetch` manda il token CSRF del realm giusto), con callback `/api/auth/callback/members`, back-channel `/api/auth/backchannel-logout/members` e logout `POST /api/auth/logout?realm=members`. Un account del tipo sbagliato per il realm (operatore nel realm dei membri, solo membro in quello degli operatori) è rifiutato alla callback (`/auth/error?reason=rejected`). Emittente dei membri uguale a `LH_OIDC_ISSUER`, segreto mancante o debole, `http` fuori da `localhost` ⇒ `INSECURE_CONFIG`. Senza `LH_OIDC_MEMBER_ISSUER` resta un solo realm, come prima (regola 14).
- **UI**: backoffice e portale senza sessione mostrano «Accesso in corso…» e vanno al login; un account del tipo sbagliato (membro nel backoffice, operatore nel portale) vede «Accesso non consentito» con «Esci». Nome e ruolo mostrati vengono dai claim (`preferred_username`, `name`, `lh_roles` con la regola di Q-365); selettore persona, tray demo del portale e `POST /api/persona` non esistono.

```mermaid
sequenceDiagram
  accTitle: Login, chiamate e logout attraverso il BFF
  accDescr: Il web avvia il login OIDC con PKCE, state e nonce, apre una sessione lato server e dà al browser solo cookie opachi; ogni chiamata ai servizi passa dal proxy con controllo CSRF e access token rinnovato una volta sola; il logout avviato dall'utente e quello inviato dall'IdP chiudono la sessione.
  autonumber
  actor B as Browser
  participant W as web (BFF)
  participant K as idp (Keycloak)
  participant S as Servizi
  B->>W: GET /backoffice senza sessione
  W-->>B: pagina «Accesso in corso…»
  B->>W: GET /api/auth/login?returnTo=/backoffice
  W-->>B: 303 verso Keycloak con code_challenge, state, nonce e cookie __Host-lh_auth cifrato
  B->>K: login (MFA per gli operatori)
  K-->>B: redirect a /api/auth/callback con code, state, iss
  B->>W: callback con cookie __Host-lh_auth
  W->>K: scambio del codice con code_verifier e segreto del client
  K-->>W: access token, refresh token, ID token
  W->>W: ID token validato, sessione nuova cifrata nello store
  W-->>B: 303 a /backoffice con __Host-lh_session HttpOnly e __Host-lh_csrf
  B->>W: POST /api/lh/... con cookie, Origin e X-LH-CSRF
  opt access token in scadenza
    W->>K: refresh token (un solo rinnovo per sessione)
    K-->>W: nuovo access token e refresh token ruotato
  end
  W->>S: stessa richiesta con Authorization Bearer, senza memberId del browser sul portale
  S-->>W: risposta
  W-->>B: risposta, Cache-Control private no-store
  alt logout dall'interfaccia
    B->>W: modulo POST /api/auth/logout con il token CSRF
    W-->>B: 303 verso il logout dell'IdP con id_token_hint, cookie cancellati
  else logout o disattivazione nell'IdP
    K->>W: POST /api/auth/backchannel-logout con logout token firmato
    W->>W: chiude le sessioni con quel sid o sub
  end
```

```mermaid
stateDiagram-v2
  accTitle: Ciclo di vita di una sessione del BFF
  accDescr: Una sessione nasce dalla callback del login, resta attiva finché è usata entro il limite di inattività e la durata massima, rinnova l'access token con il refresh token e si chiude per logout, back-channel logout, refresh token rifiutato o scadenza.
  [*] --> Attiva: callback del login valida
  Attiva --> InRinnovo: access token entro 30 s dalla scadenza
  InRinnovo --> Attiva: nuovo access token e refresh token ruotato
  InRinnovo --> Attiva: IdP irraggiungibile, 503 e nuovo tentativo alla richiesta dopo
  InRinnovo --> Chiusa: refresh token rifiutato
  Attiva --> Chiusa: Esci, logout RP-initiated
  Attiva --> Chiusa: back-channel logout per sid o sub
  Attiva --> Chiusa: inattività oltre 30 min o durata oltre 10 h
  Attiva --> Chiusa: tetto di sessioni, esce la meno recente
  Chiusa --> [*]
```

## 5. Direzione visiva
Due caratteri distinti, stessa famiglia tipografica di base. Evitare: gradienti viola generici, card tutte uguali con ombra, eyebrow in maiuscolo ovunque, Inter di default, palette crema/terracotta.

### 5.1 Tipografia
| Ruolo | Font | Uso |
|---|---|---|
| UI | **Hanken Grotesk** (400/500/600/700) | tutto il backoffice, testi del portale |
| Display | **Bricolage Grotesque** (600/800) | solo portale: saldo, titoli hero, nome tier |
| Mono | **JetBrains Mono** | ID, codici, payload JSON, `correlationId` |
Caricati con `next/font`. Numeri in `tabular-nums` ovunque ci siano importi.

### 5.2 Backoffice — "sala controllo"
| Token | Valore |
|---|---|
| `--bo-bg` | `#F4F6F8` · superfici `#FFFFFF` · bordo `#DCE1E7` |
| `--bo-ink` | `#0F1B2D` · secondario `#51607A` |
| `--bo-accent` | `#0B7A75` (teal) |
| Sidebar | `#0F1B2D` con testo `#C9D3E0`, voce attiva con barra teal a sinistra |
| Topic | actions `#1D4ED8` · effects `#6D28D9` · facts `#0B7A75` · audit `#64748B` · dlq `#BE123C` |
| Semantica punti | earn `#15803D` · spend `#B45309` · expire `#B91C1C` · STS `#6D28D9` |
- **Bordi, non ombre**; raggio 6 px; densità alta (righe tabella 40 px); titoli pagina 20 px/600.
- **Elemento firma: il rail eventi live** — colonna destra richiudibile (320 px) presente in tutto il backoffice: ogni evento è una riga con pallino del colore del topic, tipo breve, membro, ora; clic → tracciato (BO-25). In pausa al passaggio del mouse. È ciò che rende visibile l'architettura a eventi.
- Stati oggetto come *pill* con punto colorato: `DRAFT` grigio, `IN_REVIEW` ambra, `APPROVED` blu, `SCHEDULED` indaco, `LIVE` verde, `PAUSED` arancio, `ENDED` slate, `REJECTED` rosso, `ARCHIVED` grigio chiaro.

### 5.3 Portale — tema "Aurora" (sostituibile a runtime)
| Token | Default | Fonte |
|---|---|---|
| `--pt-night` | `#0E1B2C` | `theme.colors.night` |
| `--pt-primary` | `#1FB98F` | `theme.colors.primary` |
| `--pt-secondary` | `#7A5CFA` | `theme.colors.secondary` |
| `--pt-coin` | `#FFB547` | `theme.colors.coin` |
| `--pt-bg` | `#F3F7F9` | `theme.colors.bg` |
Il layout del portale legge `GET /portal/theme` e imposta le variabili CSS sull'elemento radice; fallback ai default se il servizio dorme.
- **Elemento firma: la tessera membro** — in cima a PT-01: fondo `night` con sfumatura aurorale (primary→secondary, 12 % opacità), **bordo inferiore perforato** (maschera radiale ripetuta), nome, numero tessera in mono, saldo in display 44 px, e una fascia col **materiale del tier**: BASE carta opaca, SILVER spazzolato chiaro, GOLD gradiente caldo, PLATINUM iridescente leggero (`conic-gradient`). Al tier-up la tessera si capovolge (rotazione Y 600 ms) e mostra il nuovo materiale.
- Raggio 16 px, ombre morbide solo su elementi sollevati (tessera, fogli modali), bersagli tocco ≥ 44 px, tab bar fissa con 5 voci.
- Saldo con **count-up** 600 ms al cambio; coriandoli solo per vincita e tier-up (disattivati con `prefers-reduced-motion`).

## 6. Stati di interfaccia (obbligatori per ogni vista con dati)
| Stato | Comportamento |
|---|---|
| **Loading** | skeleton della forma finale (mai spinner a pagina intera); tabelle: 8 righe scheletro |
| **Empty** | icona tenue + frase che spiega *perché* è vuoto + azione primaria (es. "Nessuna campagna in bozza. Crea la prima") |
| **Error** | riquadro in linea con `title` del problema RFC 9457, `detail`, `correlationId` copiabile, "Riprova" |
| **Degraded** (`SERVICE_ASLEEP`) | riquadro ambra: "Il servizio *wallet* si sta svegliando…" con barra indeterminata; riprova automatica ogni 5 s fino a 90 s; il resto della pagina resta usabile |
| **Forbidden** | azione disabilitata + tooltip "Richiede ruolo LEGAL"; pagina intera vietata → schermata con invito a cambiare persona |
| **Validation** | errori di campo dal backend (`errors[]` del problema) mappati sui campi del form |
| **Stale** | dato più vecchio di 60 s con SSE assente → etichetta "aggiornato alle 10:42" + ricarica |

## 7. Asincronia visibile: "in elaborazione"
Le scritture che producono eventi rispondono `202` con `correlationId`. Schema unico (`usePendingTrace(correlationId)`):
1. l'UI mostra subito una riga/segnaposto **"in elaborazione"** (pulsazione tenue);
2. ascolta l'SSE filtrato per `correlationId` (fallback: polling di `/insight/v1/traces/{id}` ogni 2 s);
3. all'arrivo del fatto atteso (es. `wallet.points.earned`, `reward.redemption.fulfilled`) invalida le query interessate e sostituisce il segnaposto col dato reale, con evidenziazione 1,5 s;
4. **timeout 20 s** → "Ci sta mettendo più del solito" + collegamento al tracciato; voce DLQ sullo stesso `correlationId` → errore con causa.
Mappa *azione UI → fatto atteso → query da invalidare* in `lib/realtime/expectations.ts`.
Nessun aggiornamento ottimistico dei saldi: il saldo cambia solo quando lo dice il wallet.

## 8. HUB-01 — Demo Hub (`/`)
Scopo: accendere la demo, capire lo stato, scegliere da dove entrare.
- **Intestazione**: nome progetto, una riga di pitch, link al repo.
- **Pannello stato** (elemento centrale): griglia di 10 tessere — 8 servizi + Kafka + Postgres — ciascuna `SLEEPING` (grigio) / `WAKING` (ambra pulsante) / `UP` (verde) / `DOWN` (rosso), con tempo di risposta. Sotto: barra "pronti 6/10" e tempo trascorso.
- **Pulsante "Accendi la demo"** → `POST /api/demo/wake` (lancia in parallelo `GET <svc>/actuator/health/liveness` verso tutti, senza attendere), poi polling di `/api/demo/status` ogni 3 s. Testo di attesa onesto: "Il primo avvio richiede 1–3 minuti: i servizi gratuiti si addormentano quando nessuno li usa". Kafka `DOWN` per più di 2 min → riquadro "Il cluster Kafka gratuito potrebbe essere stato spento per inattività: va riacceso dalla console del fornitore" con link a `docs/11 §3`.
- **Due ingressi** (attivi da 4/10 pronti con ingestion, member, campaign, wallet `UP`): *Backoffice* con scelta della persona (5 schede: nome, ruolo, cosa può fare) e *Portale* con scelta del membro (12 schede: nome, tier, saldo, "storia" in una riga).
- **Percorso consigliato**: 5 passi numerati (è una sequenza reale) che collegano a BO-29 scenari, BO-24 live, PT-01, BO-06, BO-30.
- `/api/demo/status` → `{services[] {name, state, latencyMs, version?}, kafka: {state}, db: {state}, readyCount, checkedAt}`; Kafka e DB si ricavano da `ingestion /actuator/health` (componenti `kafka`, `db`). Cache 2 s.
- **Keep-alive gentile** (F-DEMO-07): hook `useKeepAlive` montato nei layout — ogni 4 min chiama `/api/demo/wake` **solo se** `document.visibilityState === "visible"`; si ferma dopo 45 min senza interazione. Nessun pinger esterno, mai.
- **Riquadro «Modalità Enterprise»** (M8.14 V9, Q-674, ADR-051 decisione 9; solo profilo `demo`, accanto al pannello stato: due colonne da `md`, impilato sul telefono; sostituisce il pulsante in testata): titolo, pillola di stato, tre passi numerati (accendi, scegli un utente di test, entra), area d'azione e il collegamento «Guida e credenziali su Mintlify ↗» (`VETRINA_GUIDE_URL` in `lib/hub/links.ts`). Compare solo con `LH_HUB_ENTERPRISE_URL` valida (server component, mai `NEXT_PUBLIC_`), altrimenti nulla. Stati: *loading* (scheletro) · *spenta* («Accendi la modalità Enterprise») · *in accensione* (pulsante disabilitato col tempo trascorso, barra, polling ogni 10 s **solo** in questo stato; dopo il proprio avvio «spento» di GitHub vale ancora «in accensione» per 90 s e il polling si ferma dopo 10 minuti con l'errore «Riprova») · *accesa* («Apri la vetrina ↗» verso `LH_HUB_ENTERPRISE_URL`) · *errore* (`role="alert"` e «Riprova») · *non configurata* (nota tratteggiata «Disponibile su richiesta…», Q-662) quando mancano token o codespace. L'area d'azione è `aria-live="polite"`. SPEC-GAP Q-721: manca lo stato «Quasi pronta» (vetrina accesa, IdP non ancora pronto): da Vercel non si rileva, perché l'unica destinazione in uscita ammessa è GitHub e il CORS blocca una sonda dal browser; «Apri la vetrina» compare appena GitHub dice «disponibile» e HUB-02 mostra il suo stato degradato mentre i servizi si avviano.
- **Route `/api/vetrina/codespace`** (Q-674): esiste solo nel profilo `demo` e solo con `LH_VETRINA_CODESPACE` e `LH_VETRINA_GITHUB_TOKEN` entrambe impostate (altrimenti `404`); il nome del codespace passa una regex stretta. `GET` → `GET https://api.github.com/user/codespaces/{nome}` (Bearer, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, timeout 10 s, `no-store`) e risponde **solo** `{state: "available"|"starting"|"shutdown"|"unknown", url}` (`Available`→`available`; `Starting`, `Provisioning`, `Queued`, `Rebuilding`, `Awaiting…`→`starting`; `Shutdown`, `ShuttingDown`→`shutdown`; il resto `unknown`; `url` solo se `https://*.github.dev`). `POST` → `Origin` uguale all'origine della richiesta (altrimenti `403`), al più un avvio ogni 60 secondi per processo (la finestra parte solo da un avvio riuscito; dentro la finestra risponde lo stato corrente, «in accensione» se GitHub dice ancora «spento»), già acceso ⇒ nessun avvio, altrimenti `POST …/start` e `starting`. Lo stato è letto da GitHub al più ogni 5 secondi (cache di processo di `{state, url}`, svuotata da un avvio riuscito). Il token ha i permessi «Codespaces» in lettura e «Codespaces lifecycle admin» in scrittura sul solo repository (Q-720). Errori di GitHub → `502` `CODESPACE_UNAVAILABLE` (RFC 9457). Il token e il corpo grezzo di GitHub non escono mai, né in risposta né nei log (regola 20); `api.github.com` è l'unica destinazione in uscita.
- **HUB-02** (stessa pagina `/` con `LH_PROFILE=enterprise`, ADR-049, M8.14): niente persone, `/api/persona`, `/v1/demo/personas`, «Accendi la demo», keep-alive né percorso consigliato. Lo stato si calcola sul server durante il rendering, senza un endpoint nuovo: tessere di ruolo `hub` (`/actuator/health`), `web`, `idp` (discovery OIDC dell'emittente) e `cms` «non installato», più Postgres e Kafka dai componenti dell'hub. Hub irraggiungibile ⇒ *error*, un componente non attivo ⇒ *degraded* con riprova ogni 5 s fino a 90 s. L'ingresso del backoffice è il login del BFF (`/api/auth/login?returnTo=/backoffice`); il portale non ha ingresso finché Q-619 non lo apre. Banner «non HA, solo dati fittizi, accesa su richiesta e spenta dopo l'inattività, azzerabile in qualunque momento senza backup» (Q-624, Q-640, Q-662, Q-663) e «Torna alla demo» da `LH_HUB_DEMO_URL`, validata lato server come `LH_HUB_ENTERPRISE_URL`.
- **HUB-02, utenti di test** (M8.14 V9, ADR-051, Q-673, Q-676, Q-677): la sezione compare **solo** con `getAuthConfig().testUsersAllowed` (`LH_TEST_USERS_ALLOWED=true` e `LH_ENVIRONMENT=test`); altrimenti HUB-02 resta com'è, col solo login. Sopra, oltre al banner della vetrina, il banner «Ambiente di test: credenziali pubbliche». Al posto dei due ingressi tre gruppi di schede: **Operatori** (password condivisa con «Copia»; scheda con il codice OTP del momento — 6 cifre, barra dei 30 s, «cambia tra N s», «Copia» — e il seme abbreviato con rimando alla guida; `marta.admin` ADMIN, `luca.marketing` MARKETING, `elena.legal` LEGAL, `paolo.care` CARE, `sara.analyst` ANALYST, ciascuno con «Entra come <Nome>»), **Membri** (password condivisa; `anna.rossi`, `marco.bianchi`, `giulia.ferri` «registrata/o» e `laura.conti` tratteggiata «da registrare», PT-16; storie, livelli e punti arrivano col programma di esempio, V10) e **Console di Keycloak** (realm `loyaltyhub` con `vetrina.admin` e `loyaltyhub-members` con `membri.admin`, password e utente copiabili, «Apri la console ↗» in nuova scheda su `<origine IdP>/admin/<realm>/console/` ricavata dagli emittenti configurati; la scheda dei membri solo se quel realm è configurato; nota: gli eventi di Keycloak non sono ancora nell'audit, M8.12 e Q-677, e la console `master` non è pubblica). Dati in **un solo modulo** `lib/hub/testUsers.ts` (credenziali pubbliche per decisione, ADR-051 dec. 1, Q-676), importato solo da server component e route; al browser arriva solo ciò che la pagina passa come prop. Copia con `navigator.clipboard.writeText` nel gestore del clic (ripiego: testo selezionato) e annuncio «Copiato» in `aria-live`.
  - **`GET /api/vetrina/totp`**: solo `GET`, `404` se non siamo in enterprise con `testUsersAllowed`; risponde `{code, remainingSeconds}` con `Cache-Control: no-store`, TOTP RFC 6238 (SHA-1, 6 cifre, 30 s) con `node:crypto` in `lib/hub/totp.ts`, provato sui vettori dell'RFC. Il browser lo interroga una volta per periodo (al cambio di codice) e conta i secondi in locale; il seme non lascia il server.
  - **`login_hint`**: i pulsanti puntano a `/api/auth/login?realm=operators&returnTo=/backoffice&login_hint=<utente>` (membri: `realm=members&returnTo=/portal`). Il BFF lo inoltra all'IdP, con `prompt=login` (innocuo: Keycloak non autentica un altro utente sopra una sessione SSO esistente, quindi con una sessione aperta le altre schede offrono l'uscita, vedi sotto), **solo** con `testUsersAllowed` e solo se è uno degli username di test del realm scelto; ogni altro valore è ignorato in silenzio. Nessuna password viaggia nel link.
  - **Sessione aperta**: la scheda dell'operatore collegato dice «Sei dentro come <nome>» con «Apri il backoffice»; ogni altra scheda di operatore dice «Sei dentro come <nome attuale>» e, al posto del collegamento di ingresso, mostra il pulsante di uscita esistente (`LogoutButton`, modulo con token CSRF) con l'etichetta «Esci per entrare come <Nome>»: Keycloak rifiuta di autenticare un altro utente sopra una sessione SSO. Senza sessione tutte le schede restano «Entra come…».
  - **Schede dei membri attive** (M8.14 V9b, F2-SEC-09, ADR-051; `MEMBER_PORTAL_READY` in `lib/hub/testUsers.ts`, ora `true`: il portale funziona dal token, `docs/09 §2-bis`): senza sessione da membro ogni scheda ha «Entra come <Nome>» (Laura «Registrati come Laura»), verso `/api/auth/login?realm=members&returnTo=/portal&login_hint=<utente>`. Con una sessione da membro (realm `loyaltyhub-members`, indipendente da quella dell'operatore: la pagina legge `getViewer("members")` e passa alla scheda solo username e nome) la scheda del membro collegato dice «Sei dentro come <nome>» con «Apri il portale» e ogni altra scheda di membro dice «Sei dentro come <nome attuale>» con l'uscita esistente (`LogoutButton realm="members"`: modulo con token CSRF verso `/api/auth/logout?realm=members`, chiude la sola sessione del MEMBRO) etichettata «Esci per entrare come <Nome>». Il flag resta come interruttore: a `false` i quattro pulsanti tornano disabilitati con «Il portale dei membri arriva con il prossimo aggiornamento». Stati: *loading* (scheletro dell'OTP), *error* (OTP non disponibile, «Riprova»), *degraded* (le tessere di stato sopra, invariate).

## 9. Convenzioni di codice
- Hook dati per risorsa: `useMembers(filters)`, `useMember(id)`, `useAdjustPoints()`; chiavi in `lib/api/keys.ts`; `staleTime` 15 s (liste), 5 s (saldi), 60 s (configurazioni).
- URL come stato: filtri, pagina, ordinamento e scheda attiva stanno nella query string (link condivisibili durante la demo).
- Formati: punti `1.850`, valute `€ 129,90`, date `18 set 2026, 10:42`, relative "3 min fa" sotto le 24 h.
- Testi UI in **italiano**, in `lib/i18n/it.ts` (un solo dizionario, pronto a diventare multilingua).
- Accessibilità: ogni controllo ha etichetta; focus visibile (anello 2 px accent); tabelle con `scope`; grafici con tabella alternativa; ruota/gratta hanno sempre un pulsante "Gioca" equivalente.
- Ogni schermata dichiara in testa al file: ID (`BO-nn`/`PT-nn`), feature coperte, servizi chiamati.
