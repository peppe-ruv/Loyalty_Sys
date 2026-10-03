# Rapporto di collaudo — <vX.0.0>

Modello del rapporto del collaudo di release (ADR-052 decisione 2, Q-682, F2-QA-06). L'agente lo copia in `e2e/collaudo/rapporti/<vX.0.0>.md`, lo compila e lo porta nel repository con una pull request `test(collaudo): rapporto <vX.0.0>`. Il collaudo è consultivo: il verdetto non decide il rilascio.

Non scrivere qui password, seme OTP, cookie, token né corpi di richieste: le credenziali di test stanno solo nel runbook (`deploy/vetrina/README.md`).

## Esecuzione

| Voce | Valore |
| --- | --- |
| Versione | `<vX.0.0>` |
| Candidata | `<build-sha>` |
| Data e ora di inizio | `<AAAA-MM-GG hh:mm>` |
| Eseguito da | sessione cloud di Claude, oppure Claude in Chrome |
| Vetrina | `https://<codespace>-8000.app.github.dev` (accesa dall'agente, oppure già accesa) |
| Demo pubblica | `<DEMO>` |
| Browser | Chromium senza interfaccia, oppure Chrome (versione) |
| Livello 1 sulla candidata | `e2e-pr` e `e2e-nightly` verdi, oppure rossi (perché) |

## Riepilogo

| Esito | Passi |
| --- | --- |
| OK | 0 |
| KO | 0 |
| BLOCCATO | 0 |
| N/A | 0 |
| Totale | 0 |

Esiti: `OK` (l'esito atteso si verifica), `KO` (non si verifica: difetto), `BLOCCATO` (manca una precondizione: scrivi quale), `N/A` (il passo non si applica: scrivi perché).

## Esito per passo

Una riga per ogni passo del copione. «Evidenze» elenca i file `<ID>-<n>.png` in `rapporti/<vX.0.0>/`, gli URL e le voci di audit. «Console e rete» riporta gli errori di console e le richieste fallite o con `4xx/5xx` inattesi (una riga ciascuno, senza intestazioni né corpi).

### Area HUB — accensione della vetrina e riquadro Enterprise

| Passo | Esito | Nota | Evidenze | Console e rete | Issue |
| --- | --- | --- | --- | --- | --- |
| CL-HUB-001 | | | | | |
| CL-HUB-002 | | | | | |
| CL-HUB-003 | | | | | |
| CL-HUB-004 | | | | | |
| CL-HUB-005 | | | | | |
| CL-HUB-006 | | | | | |

### Area BO — backoffice, un operatore per ruolo

| Passo | Esito | Nota | Evidenze | Console e rete | Issue |
| --- | --- | --- | --- | --- | --- |
| CL-BO-001 | | | | | |
| CL-BO-002 | | | | | |
| CL-BO-003 | | | | | |
| CL-BO-004 | | | | | |
| CL-BO-005 | | | | | |
| CL-BO-006 | | | | | |
| CL-BO-007 | | | | | |
| CL-BO-008 | | | | | |
| CL-BO-009 | | | | | |
| CL-BO-010 | | | | | |
| CL-BO-011 | | | | | |
| CL-BO-012 | | | | | |
| CL-BO-013 | | | | | |
| CL-BO-014 | | | | | |
| CL-BO-015 | | | | | |
| CL-BO-016 | | | | | |
| CL-BO-017 | | | | | |
| CL-BO-018 | | | | | |

### Area PT — portale, tre membri

| Passo | Esito | Nota | Evidenze | Console e rete | Issue |
| --- | --- | --- | --- | --- | --- |
| CL-PT-001 | | | | | |
| CL-PT-002 | | | | | |
| CL-PT-003 | | | | | |
| CL-PT-004 | | | | | |
| CL-PT-005 | | | | | |
| CL-PT-006 | | | | | |
| CL-PT-007 | | | | | |
| CL-PT-008 | | | | | |

### Area KC — console di Keycloak dei due realm

| Passo | Esito | Nota | Evidenze | Console e rete | Issue |
| --- | --- | --- | --- | --- | --- |
| CL-KC-001 | | | | | |
| CL-KC-002 | | | | | |
| CL-KC-003 | | | | | |
| CL-KC-004 | | | | | |
| CL-KC-005 | | | | | |

### Area AUD — audit delle scritture di configurazione

| Passo | Esito | Nota | Evidenze | Console e rete | Issue |
| --- | --- | --- | --- | --- | --- |
| CL-AUD-001 | | | | | |
| CL-AUD-002 | | | | | |
| CL-AUD-003 | | | | | |
| CL-AUD-004 | | | | | |
| CL-AUD-005 | | | | | |
| CL-AUD-006 | | | | | |
| CL-AUD-007 | | | | | |

## Difetti

Un difetto per riga: ogni `KO` ha la sua issue GitHub con l'etichetta `collaudo` e l'ID del passo (Q-685). La issue si chiude solo con un test deterministico che lo riproduce (journey Playwright o riga del testbook) e con la correzione.

| Issue | Passo | Gravità | Sintesi | Osservato | Atteso |
| --- | --- | --- | --- | --- | --- |
| #n | CL-… | bloccante, alta, media o bassa | | | |

Gravità: **bloccante** impedisce di usare la vetrina o un percorso principale; **alta** rompe una funzione o una regola (permessi, audit, quattro occhi); **media** è un difetto di flusso, testo o stato; **bassa** è estetico o di dettaglio.

## Osservazioni

Cose non difettose ma utili: tempi (accensione, risveglio dei servizi, attesa dei punti), rumore in console, differenze dalla specifica già note (SPEC-GAP), limiti dell'esecuzione (rete, browser).

## Verdetto

Scegli uno e cancella gli altri.

- **Nessun blocco**: nessun `KO` di gravità bloccante o alta e nessun passo `BLOCCATO` senza spiegazione.
- **Blocchi**: elenca i `KO` che il proprietario deve guardare prima del tag.
- **Non eseguibile**: spiega perché (per esempio la rete non raggiunge la vetrina) e proponi il percorso alternativo.

Il proprietario decide il rilascio.
