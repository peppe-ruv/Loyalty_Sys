# Revisione del codice 0.5.0 — cosa è stato corretto e cosa resta da decidere

Revisione condotta il 17 settembre 2026 sull'intero monorepo alla prima build completa con Maven.
Ogni voce è stata verificata leggendo il codice, non dedotta: dove la segnalazione iniziale era
sbagliata è scritto perché.

Le correzioni già applicate sono nei commit di questo ramo e riassunte in `CHANGELOG.md`.
Le voci ancora aperte cambiano la semantica di saldi, transazioni o garanzie di consegna: si
affrontano una alla volta, con il banco di prova di integrazione a fare da rete (CLAUDE.md §6).
Quelle chiuse restano qui con la decisione presa, perché il motivo conta quanto la correzione.

## 1. ~~Un evento che premia due volte lo stesso wallet accredita una volta sola~~ — risolto

**Decisione presa**: la chiave di idempotenza del ledger è l'**effetto**, non l'azione; lo storno
dell'azione ritrova gli effetti per prefisso.

Il problema era che `LedgerService.post` è idempotente per `(actionKey, wallet)` mentre entrambi i
percorsi usavano la chiave della sola azione, con due sintomi diversi per la stessa causa: nel
`decision-service` il secondo accredito veniva scartato in silenzio (membro sotto-premiato), nel
`rules-engine` — che posta tutti gli esiti in un colpo — i due inserimenti si scontravano sul vincolo
di unicità e l'intero consumo dell'azione falliva, all'infinito.

Non si poteva semplicemente cambiare chiave: lo storno (`POST /v1/ledger/reversals/{chiave}`) cercava
i movimenti per chiave esatta e non li avrebbe più trovati. Quindi:

- ogni effetto ha la sua chiave derivata, che comincia sempre con quella dell'azione:
  `azione:campagna` (rules-engine, `RulesConfig.postingKey`) e `azione:decisione:tipo`
  (decision-service, `DecisionService.effectKey`);
- gli effetti della stessa campagna sullo stesso wallet si sommano in un movimento solo
  (`RulesConfig.merge`), perché la chiave del ledger resta unica per wallet;
- `LedgerService.reverse` cerca la chiave **e le sue derivate**, limitandosi ai movimenti di valore
  (EARN/SPEND) e saltando quelli già stornati: scadenze e storni precedenti non vengono toccati, e un
  secondo storno non rompe più il vincolo di unicità — restituisce una lista vuota.

Coperto da nove test di integrazione sul ledger e da otto unitari sulle due derivazioni di chiave.

## 2. ~~Le azioni premianti possono perdersi all'ingresso~~ — risolto

**Decisione presa**: meglio un duplicato che una perdita. La piattaforma è dichiaratamente
at-least-once con consumer idempotenti (CLAUDE.md §4), quindi l'ingresso ora attende la conferma del
broker e consuma la chiave di idempotenza solo dopo.

Prima: `dedup.firstSeen(chiave)` registrava la chiave, poi `kafka.send(...)` partiva senza che
nessuno ne guardasse l'esito. Con il broker fermo la fonte riceveva `202 ACCEPTED`, l'azione non
entrava in piattaforma e ogni rinvio veniva respinto come `DUPLICATE`: persa per sempre, senza traccia.

Ora:

- `ActionPublisher` attende l'ack del broker (`ingress.publish.ack-timeout-ms`, 5 s di default) e
  solleva `PublishFailed`; vale anche per la DLQ, perché anche uno scarto che sparisce è una perdita;
- il controller, se la pubblicazione fallisce, **rilascia la chiave** (`DedupService.forget`) e marca
  l'elemento `FAILED`; il lotto risponde `503` invece di `202`, così anche una fonte che non legge
  l'esito per elemento si accorge e rinvia. Le azioni già accettate restano deduplicate al rinvio;
- stesso trattamento per il check-in (`CheckInController`).

Il contratto `docs/contracts/openapi-ingress.yaml` dichiara il nuovo stato `FAILED`, il contatore
`failed` e la risposta `503` (aggiunta compatibile: nessun campo rimosso o cambiato).

Coperto da tre test di integrazione: broker fermo → 503 e nessuna accettazione; broker che torna →
la stessa chiave viene presa in carico; azione pubblicata davvero → il secondo invio resta duplicato.

## 3. Chiamate HTTP dentro transazioni, senza compensazione

`RedemptionService.create` addebita il ledger via REST e poi continua in transazione locale; se il
seguito fallisce (`COUPON_POOL_EMPTY`, stock esaurito) la transazione locale torna indietro ma
**l'addebito remoto è già committato**: il membro resta senza punti e senza premio. Stessa forma in
`WheelService.spin` e nel merge di `identity-mapping`.

Serve una compensazione esplicita (storno nel `catch`) o lo spostamento dell'addebito dopo l'esito
locale. È il punto in cui la piattaforma ha più bisogno dei test di integrazione con Testcontainers
già in backlog: una correzione senza quel banco di prova è un salto nel buio.

## 4. ~~Cicli delle classifiche chiusi prima di premiare~~ — risolto

**Decisione presa**: la riga del ciclo è una *prenotazione*, non una chiusura; la premiazione si
conferma a parte e i cicli prenotati e non confermati vengono ripresi.

`closeCycles` inseriva la riga del ciclo — la guardia contro la doppia premiazione — e solo dopo
assegnava i premi, con chiamate a catalogo, ledger ed engagement fuori da qualunque transazione
comune: un errore a metà elenco lasciava i vincitori successivi senza premio e il ciclo chiuso per
sempre.

Ora `leaderboard_cycle` ha `rewarded_at` (migrazione `V2`): l'inserimento prenota, la premiazione si
conferma in fondo, e al giro successivo un ciclo prenotato ma non confermato viene ripreso. Il
secondo tentativo è innocuo perché ogni premio ha già la sua chiave di idempotenza
(`leaderboard:<classifica>:<ciclo>:<membro>`). I punteggi si azzerano solo dopo la conferma.

## 5. ~~Metrica di classifica irraggiungibile~~ — risolto

`EngagementService.onAction` scartava ogni azione il cui tipo inizia per `ACHIEVEMENT_` o
`CHALLENGE_` — filtro anti-anello — ma la metrica `ACHIEVEMENT_PROGRESS` si nutre proprio di
`ACHIEVEMENT_PROGRESSED`: quel ramo non poteva mai scattare e una classifica configurata così
restava vuota, senza errori.

Le azioni interne del servizio ora saltano il motore (l'anello resta escluso) ma **alimentano le
classifiche**: `applyToLeaderboards` è un passaggio a sé, chiamato da entrambi i percorsi. Nello
stesso ramo mancava la guardia sul riferimento nullo che gli altri hanno: una classifica senza
riferimento vale per qualunque achievement.

Coperti da due test di integrazione sul `engagement-service`.

## 6. «Paga con i punti» può scontare più del carrello

`UnitsConversion.unitsFor` arrotonda per eccesso al passo: con passo 100 e 0,01 €/punto, un carrello
da 5,55 € scala 600 punti e restituisce `discountEur = 6,00`. Lo sconto supera l'importo.

Le due uscite ragionevoli — arrotondare per difetto (il resto si paga normalmente) o limitare lo
sconto all'importo del carrello, lasciando al membro i punti in eccesso — hanno effetti diversi sul
conto economico: è una scelta di prodotto, non una correzione tecnica.

## 7. Chiavi di idempotenza generate dall'orologio

`web/bff/src/server.js` costruisce alcune chiavi con `Date.now()` (trasferimenti P2P, eventi
comportamentali, azioni da sportello). Un ritentativo del client genera una chiave diversa e la
deduplica a valle non scatta: l'operazione si ripete. Le chiavi devono venire dal client
(`clientRef`) o da un identificativo stabile dell'operazione.

## 8. Accumulo STATUS non idempotente

`TierUpdater` somma i punti STATUS a ogni movimento consumato dal topic. L'outbox del ledger è
at-least-once: un replay del topic gonfia i punti status dell'anno. Serve la stessa difesa usata
altrove (chiave del movimento già vista, o `ON CONFLICT DO NOTHING` su una tabella di movimenti
applicati).

## Segnalazioni verificate e respinte

- **«`debit` e `reverse` non sono idempotenti e raddoppiano i movimenti»**: no. L'indice
  `movement_action_currency_uq (action_key, currency)` in `V1__schema.sql` impedisce il secondo
  inserimento. Il difetto reale è un altro e minore: il vincolo scatta come errore di integrità
  (HTTP 500) invece di una risposta pulita (200 o 409). Vale la pena intercettarlo, non riscrivere
  la logica.

## Correzioni già applicate in questo ramo

| Problema | Dove |
| --- | --- |
| `rules-engine` ed `engagement-service` non compilavano | `RulesConfig`, `AchievementEngine` |
| Il corpo di una email poteva partire come SMS o push | `notifier/delivery/DeliveryService` (+4 test) |
| Un codice tier sbagliato retrocedeva il membro in silenzio | `tier-service` (+1 test) |
| Annullo di un riscatto altrui dall'area membro | `catalog-redemption/RedemptionService` |
| Il corpo della richiesta poteva sovrascrivere il `memberId` del percorso | `web/bff/src/server.js` |
| Sostituzione dei campi custom fuori transazione | `member-service/CustomFieldController` |
| Nome tabella concatenato nel SQL senza elenco chiuso | `engagement-service/EngagementService` |
| ObjectMapper ricostruito a ogni lettura (catalogo, scansione segmenti) | `ingress-adapters`, `segment-service` |
| Risposte JSON dei client REST come raw type | tutti i `*Config` dei servizi |
| Il CMS non si installava né si costruiva | `cms/` |
| L'immagine del BFF non aveva le dipendenze | `web/bff/Dockerfile` |
