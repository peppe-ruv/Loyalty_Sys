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

## 2. Le azioni premianti possono perdersi all'ingresso

`ActionIngressController` registra la chiave di idempotenza **prima** di pubblicare:

```java
if (!dedup.firstSeen(a.idempotencyKey())) { ...DUPLICATE... }
publisher.publish(...);   // ActionPublisher: kafka.send(...) senza attendere l'esito
```

`ActionPublisher.publish` ignora il future di `KafkaTemplate.send`: se il broker rifiuta, nessuno se
ne accorge, la risposta resta `202 ACCEPTED` e ogni tentativo successivo con la stessa chiave è
respinto come `DUPLICATE`. L'azione è persa senza traccia.

La piattaforma è dichiaratamente at-least-once con consumer idempotenti (CLAUDE.md §4), quindi la
correzione coerente è attendere l'ack e registrare la chiave **dopo** la pubblicazione riuscita:
duplicati tollerati, perdite no. Costa latenza su ogni lotto: va misurata sui volumi reali prima di
adottarla.

## 3. Chiamate HTTP dentro transazioni, senza compensazione

`RedemptionService.create` addebita il ledger via REST e poi continua in transazione locale; se il
seguito fallisce (`COUPON_POOL_EMPTY`, stock esaurito) la transazione locale torna indietro ma
**l'addebito remoto è già committato**: il membro resta senza punti e senza premio. Stessa forma in
`WheelService.spin` e nel merge di `identity-mapping`.

Serve una compensazione esplicita (storno nel `catch`) o lo spostamento dell'addebito dopo l'esito
locale. È il punto in cui la piattaforma ha più bisogno dei test di integrazione con Testcontainers
già in backlog: una correzione senza quel banco di prova è un salto nel buio.

## 4. Cicli delle classifiche chiusi prima di premiare

`LeaderboardJobs.closeCycles` inserisce la riga di ciclo chiuso (`ON CONFLICT DO NOTHING`, che è la
guardia contro la doppia premiazione) e **poi** assegna i premi, senza transazione comune. Un errore
a metà elenco lascia i vincitori successivi senza premio e il ciclo risulta chiuso per sempre.

## 5. Metrica di classifica irraggiungibile

`EngagementService.onAction` scarta ogni azione il cui tipo inizia per `ACHIEVEMENT_` o
`CHALLENGE_` — filtro anti-anello. Ma la metrica `ACHIEVEMENT_PROGRESS` delle classifiche si alimenta
proprio da `ACHIEVEMENT_PROGRESSED`: quel ramo non può mai scattare e una classifica configurata così
resta vuota senza errori. Nello stesso `switch`, il ramo `ACHIEVEMENT_PROGRESS` chiama
`lb.reference().equals(...)` senza la guardia sul null che ha il ramo precedente.

Va deciso quali azioni interne devono rientrare (probabilmente `ACHIEVEMENT_PROGRESSED` sì,
`ACHIEVEMENT_COMPLETED` no) e ristretto il filtro di conseguenza.

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
