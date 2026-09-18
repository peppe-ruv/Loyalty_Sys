# Stato e roadmap · Status and roadmap

> 🇮🇹 Dove siamo, per area, e cosa manca. Onesto: ciò che è parziale è segnato come parziale, con la ragione e la
> conseguenza pratica. Versione corrente **0.7.0**.
> 🇬🇧 Where we are, area by area, and what is missing. Honest: whatever is partial is marked partial, with the
> reason and the practical consequence. Current version **0.7.0**.

Legenda · Legend: **✅ completo · complete** · **🟡 parziale · partial** · **⬜ pianificato · planned**

---

## 1. Stato per area · Status by area

| Area | Stato | Cosa c'è · What exists | Cosa manca · What is missing |
| --- | --- | --- | --- |
| Ingestione e contratti · Ingestion and contracts | ✅ | REST, batch, Kafka, file; evento canonico; idempotenza; schemi; coda di scarto; quote; specifiche OpenAPI di tutti i servizi generate dal codice e verificate dai test; AsyncAPI di tutti i topic | — |
| Campagne e regole · Campaigns and rules | ✅ | Trigger, regole e condizioni, effetti, limiti e budget, espressioni, automazioni, referral multilivello, simulatore | — |
| Registro e wallet · Ledger and wallets | ✅ | Movimenti immutabili, wallet configurabili, sospensioni, blocchi, trasferimenti, outbox | Movimento di estensione della scadenza · expiry-extension movement |
| Livelli · Tiers | ✅ | Tier set, upgrade, modalità di discesa, benefici, progresso, override | — |
| Premi e riscatti · Rewards and redemption | ✅ | 10 tipi, lotti di codici, conversione unità, paga con i punti, stati con rimborso | — |
| Gamification | ✅ | Achievement, challenge, badge, classifiche, cicli premianti | — |
| Concorsi · Contests | ✅ | Istanti vincenti, montepremi, registro verificabile, ruota agganciata agli istanti | — |
| Decisioni · Decisioning | ✅ | Customer 360, motore, vincoli, punteggio, offerte, Next Best Action, decision log, esperimenti, budget giornaliero di unità applicato, interruttore automatico sul provider di previsioni, effetto per variante nel decision log | — |
| Previsioni · Predictions | 🟡 | Porta, provider a regole, routing per chiave, ripiego | Modelli addestrati; feature store; misura della qualità delle previsioni |
| Antifrode · Fraud | 🟡 | 9 segnali configurabili, punteggio e livelli, blocco automatico, rivalutazione | Grafo dei dispositivi; liste di blocco dal backoffice; revisione manuale che rialimenta le soglie |
| Consegna · Delivery | ✅ | Inbox, webhook, coda operatore, instradamento con ripiego, modelli, adattatori verso i fornitori di push/email/SMS, coda e reinvio manuale delle consegne fallite | Localizzazione dei modelli per canale · per-channel template localisation |
| Identità · Identity | ✅ | Grafo, risoluzione deterministica, merge ripartibile con chiave di idempotenza, unmerge che ripristina identificatori e saldi, alias, ripresa automatica dei merge interrotti | Collegamenti probabilistici proposti in coda operatore · probabilistic links proposed in the operator queue |
| Consensi · Consent | ✅ | Finalità con base giuridica, storico, scadenza, revoca propagata, prova | — |
| Backoffice · Back office | 🟡 | Collezioni, workflow di approvazione, versioni, simulazioni, vista andamenti | **Controllo di accesso per ruolo**: la collezione `roles` c'è ma nessuna collezione la applica, quindi ogni operatore può tutto · **role-based access control**: the `roles` collection exists but nothing enforces it. Export di configurazione verso storage a oggetti |
| Area membro · Member area | 🟡 | Saldo, storico, catalogo, riscatti, inbox, gamification, widget, degrado | Area riservata completa con autenticazione reale (oggi membro dimostrativo) |
| Osservabilità · Observability | ✅ | Metriche di business e tecniche, SLO e alert con runbook, log e tracce correlati, cruscotti versionati | — |
| Warehouse e BI | ✅ | Ingestione dai topic, schema a stella, cruscotti incorporati, retention | Analytics di programma esposte anche via API di lettura |
| Test | 🟡 | Test unitari di dominio e test di integrazione su database reale (container effimero) per riscatti, merge di identità, ingresso azioni | Banco Kafka per outbox e ciclo decisionale end-to-end · Kafka bench for the outbox and the end-to-end decision cycle |
| Sicurezza · Security | 🟡 | Nessun segreto nel repository, immagini firmate, SBOM, ruoli e SSO, resource server OAuth2 su tutte le API interne con token di servizio (attivabile per ambiente) | Scansione dipendenze bloccante in CI; quote per fonte nel gateway · blocking dependency scan in CI; per-source quotas in the gateway |

---

## 2. Prossimi passi · Next steps

| Priorità · Priority | Intervento · Work item | Perché ora · Why now |
| --- | --- | --- |
| 1 | Test di integrazione con container effimeri sui percorsi critici (outbox, ciclo decisionale, merge con trasferimento, consegna con ripiego) | Sono i punti dove un errore non si vede nei test unitari · these are the paths unit tests cannot cover |
| 4 | Quote per fonte nel gateway e scansione dipendenze bloccante in CI | Irrigidire il perimetro · harden the perimeter |
| 5 | Area riservata completa con autenticazione reale | È la faccia della piattaforma verso il membro · it is the platform's face to the member |
| 8 | Export di configurazione e dati verso storage a oggetti | Duplicazione di ambienti e analisi esterne · environment duplication and external analysis |

---

## 3. Idee valutate e rinviate · Considered and deferred

| Idea | Stato · Status | Nota |
| --- | --- | --- |
| Multi-tenant | Rinviata · deferred | Un solo programma per installazione: la separazione richiederebbe partizionamento dei dati e dei permessi · one program per installation |
| Attivo-attivo su due region · active-active across regions | Rinviata · deferred | Il vincolo di residenza dei dati dei concorsi la rende poco utile oggi · contest data residency makes it of little use today |
| Motore di raccomandazione prodotti · product recommendation engine | Fuori ambito · out of scope | La piattaforma decide azioni loyalty · the platform decides loyalty actions |
| Migrazione da un programma preesistente · migration from a pre-existing program | Possibile · possible | Adattatore di import sul registro, con ricostruzione dei saldi · ledger import adapter with balance reconstruction |
| Esposizione delle API a un assistente aziendale · exposing APIs to a corporate assistant | Da valutare · to evaluate | Le API sono già versionate e con ambiti separati · APIs are already versioned with separate scopes |

---

## 4. Come si aggiorna questo documento · How this document is updated

Chi chiude una voce la sposta da 🟡/⬜ a ✅ nello stesso commit del codice, e aggiorna
[`FEATURES.md`](FEATURES.md) se cambia una capacità. Chi apre una voce nuova scrive anche la conseguenza pratica
dell'assenza: una roadmap senza conseguenze non aiuta a decidere le priorità.
**EN** — Whoever closes an item moves it from 🟡/⬜ to ✅ in the same commit as the code, and updates
[`FEATURES.md`](FEATURES.md) if a capability changes. Whoever opens a new item also states the practical consequence
of its absence: a roadmap without consequences does not help prioritise.
