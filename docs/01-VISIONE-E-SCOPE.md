# 01 — Visione e scope del PoC

## 1. Problema

Il progetto è cresciuto più in fretta del suo ordine: entità di backoffice senza nessun lettore, confini tra moduli sfumati, specifiche sparse. Si riparte da un repo nuovo con una regola: **prima la specifica, poi il codice**, e ogni cosa configurabile deve produrre un effetto visibile.

## 2. Visione del prodotto

Una piattaforma loyalty open source con copertura funzionale paragonabile a Open Loyalty, ma costruita su **microservizi Spring Boot che si parlano solo tramite topic Kafka**. Tre tratti distintivi:

1. **Tutto è un'azione premiante.** Fonti esterne eterogenee e accadimenti interni (vincita, salto di tier, badge, referral) entrano dallo stesso topic e sono valutati dallo stesso motore.
2. **Spiegabile.** Per ogni azione si può vedere il tracciato completo (azione → valutazione → effetti → movimenti → notifiche) e il motivo per cui una campagna non è scattata.
3. **Un solo backoffice**, che governa programma, premi, concorsi e contenuti del portale, con approvazione Legal dove serve.

## 3. Obiettivo del PoC

Un sistema **funzionante e guardabile**, in cui chiunque possa in 5 minuti: scegliere una persona → simulare un'azione → vedere punti, tier, notifiche e dashboard reagire dal vivo → configurare una campagna e vederne l'effetto → giocare un instant win → richiedere un premio.

### Criteri di successo
| # | Criterio | Misura |
|---|---|---|
| S1 | Ciclo completo azione → punti visibile nel portale | ≤ 5 s a servizi svegli |
| S2 | Ogni entità configurabile nel backoffice ha un effetto osservabile | 100% (matrice in `docs/02 §4`) |
| S3 | Demo accendibile da spenta senza interventi manuali sui servizi | "Accendi la demo" → tutto UP ≤ 4 min |
| S4 | Costo mensile | 0 € (spenta e accesa, entro i free tier) |
| S5 | Nessuna schermata vuota a demo appena ripristinata | 100% delle schermate P0 |
| S6 | Avvio locale con un comando | `docker compose --profile all up` |

## 4. Scope

### Dentro (P0/P1)
Ingresso eventi multi-fonte con dedup e monitor · membri e profilo · segmenti · campagne (motore regole, limiti, budget, simulazione, spiegazione) · wallet a doppia valuta con lotti, scadenze, rettifiche · tier annuali con discesa morbida · catalogo premi a fasce, coupon, richieste premio (saga) · instant win a istanti pre-generati · obiettivi, badge, classifiche · referral · contenuti (card, pop-up, banner), messaggi in-app, tema del portale · workflow di approvazione · audit · KPI, flusso eventi live, tracciati, DLQ · simulatore eventi, scenari guidati, reset dati · deploy a costo zero.

### Fuori dal PoC (solo predisposizione o ADR)
| Tema | Trattamento nel PoC | Fase 2 (`docs/18`) |
|---|---|---|
| Autenticazione/autorizzazione (OIDC, IAM) | sostituita da *personas*; header `X-LH-Actor`. Target: OIDC (ADR-010) | OIDC con Keycloak (ruolo `idp`) e BFF, ADR-027 (M8.2) |
| Multi-tenant | singolo tenant `aurora`; l'envelope evento porta già `lhtenant` | resta un programma per installazione: ADR-013 confermata |
| E-mail/SMS/push reali | canale `EMAIL_FAKE` = solo anteprima; in-app reale | consegna esterna nel modulo `delivery` del member-service, SMTP/WEBHOOK (M8.4, ADR-032) |
| Upload immagini | solo URL o asset statici in `web/public/demo/` | asset su volume o S3 (`LH_S3_*`), controlli sui file (M8.10) |
| i18n | solo italiano | multilingua a tre livelli con prefisso URL, ADR-033 (M11) |
| Kubernetes, DR, stack Prometheus/Grafana/Loki, ClickHouse/Superset | architettura target (ADR-012); nel PoC Actuator + `insight-service` | chart Helm con operatori e SLO/DR, ADR-026 e ADR-036 (M8.3, M15) |
| Adempimenti concorsi a premio (perizia software, notaio, server in Italia) | annotati in `docs/03 §7`; nessuna implementazione | impronta firmata degli istanti, verbale, export ritenuta (M14.4) |
| Import massivi, export schedulati | P2 | `POST /v1/events/batch` e import file con rapporto (M8.7) |
| Schema Registry | P2; nel PoC JSON Schema nel repo | resta P2: JSON Schema nel repo con controllo di compatibilità, ADR-028 |

## 5. Personas

### Backoffice (ruoli fittizi, selezionabili dal menu in alto)
| Username | Nome | Ruolo | Cosa fa nella demo |
|---|---|---|---|
| `marta.admin` | Marta Villa | `ADMIN` | tutto, inclusi reset, job demo, configurazione tier/valute |
| `luca.marketing` | Luca Serra | `MARKETING` | crea campagne, premi, concorsi, contenuti; invia in approvazione |
| `elena.legal` | Elena Riva | `LEGAL` | approva/rifiuta concorsi, premi e campagne che lo richiedono; vede gli istanti vincenti |
| `paolo.care` | Paolo Neri | `CARE` | scheda membro 360°, rettifiche punti motivate, gestione richieste premio |
| `sara.analyst` | Sara Longo | `ANALYST` | sola lettura: dashboard, tracciati, audit |

Matrice permessi completa: `docs/08 §2`.

### Membri (portale)
Dodici membri fittizi con stati progettati per raccontare una storia ciascuno (nuovo iscritto, a un passo dal Gold, punti in scadenza, a rischio discesa, giocate disponibili…). Elenco e storie: `docs/10 §2`.

## 6. Parità con Open Loyalty (sintesi)

| Area Open Loyalty | Copertura Loyalty Hub | Feature |
|---|---|---|
| Members, custom attributes, labels, GDPR | sì (P0/P1) | `F-MBR-*` |
| Tiers (tier set, downgrade mode, multiplier) | sì, con discesa morbida annuale | `F-TIER-*` |
| Wallets / units, expiry, pending, manual ops | sì, doppia valuta e lotti FIFO | `F-WAL-*` |
| Campaigns (trigger, condizioni, effetti, limiti, budget) | sì | `F-CMP-*` |
| Custom events, transactions, unmatched | sì | `F-ING-*` |
| Rewards, coupon, redemption | sì, con fasce | `F-RWD-*`, `F-CPN-*` |
| Segments | sì (statici e dinamici) | `F-SEG-*` |
| Achievements, badges, leaderboards | sì | `F-ACH-*`, `F-LDB-*` |
| Referral | sì | `F-REF-*` |
| Fortune wheel / giochi | sì, come instant win a istanti pre-generati | `F-IW-*` |
| Analytics | KPI + live + tracciati | `F-INS-*` |
| Webhooks, audit log, ruoli admin | sì (ruoli simulati) | `F-WBH-*`, `F-AUD-*` |
| CMS/portale | sì, contenuti gestiti dal backoffice | `F-CNT-*`, `F-THM-*` |
| Multi-store, translations, e-mail provider | P2 | — |

## 7. Rischi principali

| Rischio | Mitigazione |
|---|---|
| Avvio lento dei servizi JVM su 0.1 CPU | Demo Hub con stato per servizio e attesa guidata; JVM snella (`docs/11 §6`); niente JPA |
| Kafka gratuito: 5 topic × 2 partizioni, spegnimento per inattività | design a 5 topic; riaccensione manuale documentata; stato Kafka visibile nel Demo Hub |
| 8 servizi = molto boilerplate | `lh-common` + archetipo di servizio creato in M0; fette verticali |
| Endpoint pubblici senza login | dati solo fittizi; reset con un clic; nessun segreto nel frontend |
| Deriva tra seed dei servizi | unica cartella `seed/` + `scripts/check-seed.mjs` in CI |
