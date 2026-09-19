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
- Il browser chiama **sempre** `/api/lh/<service>/v1/...`; il route handler inoltra a `LH_SVC_<SERVICE>_URL` (es. `LH_SVC_WALLET_URL`), copia metodo, query, corpo, e **aggiunge** `X-LH-Actor` leggendo il cookie persona, `X-Correlation-Id` (nuovo ULID se assente). Timeout 25 s. Niente CORS sui servizi per le chiamate REST.
- Eccezione: **SSE** va diretto a `NEXT_PUBLIC_LH_INSIGHT_URL/v1/stream/events` (le funzioni serverless non reggono connessioni lunghe). Se l'SSE fallisce 3 volte → **polling** di `/v1/events?from=<ultimo>` ogni 3 s, con indicatore "live ridotto".
- `503/502/504` o errore di rete dal proxy → risposta `{type: "SERVICE_ASLEEP", service}`: l'UI mostra lo stato *degraded* (§6) e innesca `wake`.

## 4. Identità simulata (nessuna login)
- Cookie `lh_persona` (JSON, 30 giorni): `{kind: "BO", username, role}` oppure `{kind: "MEMBER", memberId}`. Default: `marta.admin` nel backoffice, `MBR-000002` nel portale.
- **Selettore persona** sempre visibile: nel backoffice in alto a destra (avatar con iniziali + ruolo in chiaro); nel portale dentro il tray demo (PT-14). Cambiare persona invalida tutta la cache di Query.
- I permessi (`lib/persona/permissions.ts`, matrice in `docs/08 §2`) **nascondono o disabilitano** le azioni; il backend le rifiuta comunque con `403` (`@RequiresRole`). Un'azione disabilitata mostra in tooltip il ruolo richiesto.
- Banner fisso in fondo al Demo Hub: "Ambiente dimostrativo: dati fittizi, nessuna autenticazione".

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

## 9. Convenzioni di codice
- Hook dati per risorsa: `useMembers(filters)`, `useMember(id)`, `useAdjustPoints()`; chiavi in `lib/api/keys.ts`; `staleTime` 15 s (liste), 5 s (saldi), 60 s (configurazioni).
- URL come stato: filtri, pagina, ordinamento e scheda attiva stanno nella query string (link condivisibili durante la demo).
- Formati: punti `1.850`, valute `€ 129,90`, date `18 set 2026, 10:42`, relative "3 min fa" sotto le 24 h.
- Testi UI in **italiano**, in `lib/i18n/it.ts` (un solo dizionario, pronto a diventare multilingua).
- Accessibilità: ogni controllo ha etichetta; focus visibile (anello 2 px accent); tabelle con `scope`; grafici con tabella alternativa; ruota/gratta hanno sempre un pulsante "Gioca" equivalente.
- Ogni schermata dichiara in testa al file: ID (`BO-nn`/`PT-nn`), feature coperte, servizi chiamati.
