# Glossario · Glossary

Termini del dominio con la traduzione usata nel codice e nel backoffice. Se un termine non è qui, va aggiunto
prima di usarlo in un'interfaccia.
**EN** — Domain terms with the translation used in code and in the back office. If a term is missing, add it here
before using it in a user interface.

| Italiano | English | Significato · Meaning |
| --- | --- | --- |
| Membro | Member | Persona iscritta al programma, identificata da un identificatore opaco · person enrolled in the program, identified by an opaque identifier |
| Azione premiante | Rewarding action | Fatto che può generare valore per il membro (transazione, check-in, adesione, vincita) · fact that can generate value for the member |
| Evento canonico | Canonical event | Busta comune a tutte le fonti, con tipo versionato e chiave di idempotenza · common envelope across sources |
| Chiave di idempotenza | Idempotency key | Identificativo stabile che impedisce doppi effetti sul replay · stable id preventing duplicate effects on replay |
| Unità | Unit | Valore accumulabile in un wallet (i "punti") · accumulable value in a wallet (the "points") |
| Unità premio / status | Reward / status units | Unità spendibili / unità che qualificano il livello · spendable units / tier-qualifying units |
| Wallet | Wallet | Contenitore di unità con proprie regole di scadenza e limiti · unit container with its own expiry rules and caps |
| Movimento | Movement | Riga immutabile del registro (accredito, spesa, scadenza, storno, blocco, trasferimento) · immutable ledger row |
| Registro | Ledger | Insieme append-only dei movimenti: unica verità sui saldi · append-only set of movements: single source of truth for balances |
| Unità in sospeso | Pending units | Maturate ma non ancora spendibili · earned but not yet spendable |
| Livello / fascia | Tier | Stato del membro derivato dalle unità status o da altre condizioni · member status derived from status units or other conditions |
| Tier set | Tier set | Insieme di condizioni e soglie che definisce i livelli · set of conditions and thresholds defining tiers |
| Discesa morbida | Soft landing | A fine periodo si scende al massimo di un livello · at period end a member drops at most one tier |
| Campagna | Campaign | Regola di business: trigger, condizioni, effetti, limiti · business rule: trigger, conditions, effects, limits |
| Effetto | Effect | Conseguenza di una campagna (unità, premio, badge, attributo, tier, evento) · consequence of a campaign |
| Espressione | Expression | Formula valutata in contesto di sola lettura · formula evaluated in a read-only context |
| Automazione | Automation | Campagna pianificata su un pubblico · campaign scheduled over an audience |
| Segmento | Segment | Insieme di membri che soddisfa dei criteri, dinamico o statico · set of members matching criteria, dynamic or static |
| Collezione di valori | Value collection | Lista riutilizzabile usata nelle condizioni · reusable list used in conditions |
| Premio | Reward | Ciò che il membro ottiene (buono, bene, servizio, accredito, codice) · what the member obtains |
| Riscatto | Redemption | Richiesta di un premio e suo ciclo di evasione · reward request and its fulfilment lifecycle |
| Lotto di codici | Coupon pool | Codici caricati e assegnati con prelievo esclusivo · uploaded codes drawn exclusively |
| Paga con i punti | Pay with points | Conversione di unità in sconto sul carrello · converting units into a cart discount |
| Achievement | Achievement | Obiettivo progressivo con completamento · progressive goal with completion |
| Challenge | Challenge | Percorso a milestone, diretto o da referral · milestone journey, direct or referral-driven |
| Badge | Badge | Riconoscimento assegnabile e usabile nelle condizioni · grantable recognition usable in conditions |
| Classifica | Leaderboard | Ordinamento per metrica e periodo, con cicli premianti · ranking by metric and period, with rewarding cycles |
| Istante vincente | Winning moment | Momento pre-generato: la prima giocata valida successiva vince · pre-generated moment: the next valid play wins |
| Registro giocate | Play log | Registro a hash concatenato delle giocate · hash-chained log of plays |
| Presenta un amico | Referral | Meccanica presentatore/presentato con premi a entrambi · referrer/referred mechanic rewarding both |
| Customer 360 | Customer 360 | Documento aggregato per membro, letto in una sola chiamata · aggregated per-member document read in one call |
| Candidato | Candidate | Azione ammissibile prima dell'arbitrato · eligible action before arbitration |
| Decisione | Decision | Scelta finale con azioni eseguite, scartate e motivo · final choice with executed and rejected actions and reasons |
| Decision log | Decision log | Registro append-only delle decisioni · append-only log of decisions |
| Azione contrattuale | Contractual action | Spetta sempre: non è arbitrata · always due: never arbitrated |
| Azione discrezionale | Discretionary action | Soggetta a vincoli, punteggio e canale · subject to constraints, scoring and channel |
| Prossima azione consigliata | Next Best Action | Decisione richiesta in modo sincrono da un canale · decision requested synchronously by a channel |
| Previsione | Prediction | Valore stimato usato solo nel punteggio · estimated value used only in scoring |
| Punteggio di rischio | Risk score | Sintesi dei segnali antifrode, con livello e motivi · summary of fraud signals, with level and reason codes |
| Consegna | Delivery | Recapito di un'azione di contatto su un canale · dispatch of a contact action on a channel |
| Instradamento | Routing | Regole di scelta e ripiego dei canali · channel choice and fallback rules |
| Ore di silenzio | Quiet hours | Finestra in cui non si contatta · window in which no contact happens |
| Cap di contatto | Contact cap | Numero massimo di contatti per canale e periodo · maximum contacts per channel and period |
| Finalità di consenso | Consent purpose | Scopo dichiarato del trattamento, con base giuridica · declared processing purpose, with legal basis |
| Grafo identità | Identity graph | Mappa degli identificatori verso un membro · map of identifiers to a member |
| Unione / separazione | Merge / unmerge | Fusione di due membri e sua reversione parziale · merging two members and its partial reversal |
| Coda di scarto | Dead-letter queue | Eventi non elaborabili, con motivo e riprocessamento · unprocessable events, with reason and reprocessing |
| Outbox | Outbox | Tabella transazionale da cui gli eventi vengono pubblicati · transactional table events are published from |
| Backoffice | Back office | Pannello unico di contenuti e configurazione · single panel for content and configuration |
| Postazione operatore | Operator console | Interfaccia per sportello, negozio e assistenza · interface for desk, store and support |
| Anno di programma | Program year | Periodo annuale su cui girano livelli, missioni e premi · annual period tiers, missions and rewards run on |
