# TB-ENG — Testbook funzionale: engagement

Dominio **engagement** del testbook funzionale (metodo e convenzioni in `docs/16-TESTBOOK-FUNZIONALE.md` §1 e §1bis): CMS del programma (card, pop-up, banner, card vincita), selezione per posizionamento e frequenza, messaggi (template, regole di notifica, inbox del portale), tema del portale, webhook in uscita.

- **Servizio:** `services/engagement-service`.
- **Fonti (oracolo):** `docs/servizi/engagement-service.md` (§2 modello, §3 API, §4 eventi, §5 regole, §7 accettazione) · `docs/03` §3.3 (condizioni), §3.4 (`SEND_MESSAGE`), §3.6 (ciclo di vita), §9 (contenuti e messaggi) · `docs/02` `F-CNT-01…04`, `F-MSG-01/02`, `F-THM-01`, `F-WBH-01` · `docs/05` (fatti) · `docs/06` §2–§4 (API, errori, attore, lock) · `docs/07` §2 (formattazione it-IT, Europe/Rome) · `docs/08` §2 (permessi), BO-18, BO-19, BO-20, BO-23 · `docs/09` PT-12 · `docs/10` §7 · `docs/11` (anti-SSRF) · scelte registrate in `docs/15`: Q-66, Q-67, Q-68, Q-70, Q-71, Q-72, Q-73, Q-75, Q-79, Q-97…Q-103.
- **Righe:** 804, di cui 132 marcate **AMBIGUO** (registrate in `docs/15`: Q-161 e Q-170…Q-185, §17.2) e 7 ex **DIVERGENZA**, risolte correggendo il codice secondo la specifica (§17.1).

## 0. Come si legge e come si esegue

- **ID** `TB-ENG-<AREA>-NNN`; ogni ID compare una sola volta come prima cella di una riga di tabella. Aree: `SEL` idoneità · `CAL` estremi del calendario · `AUD` pubblico · `EXT` estensioni del pubblico (Q-71) · `ORD` ordine e limiti · `POP` frequenza dei pop-up · `POPA` API dei pop-up · `PRV` anteprima e portale · `LIF` ciclo di vita · `END` fine automatica · `EDT` creazione e modifica · `EDL` modifica di un `LIVE` · `DUP` duplicazione · `ROL` ruoli · `AUDT` audit · `TPL` motore dei template · `TPV` sintassi dei template · `TAD` gestione dei template · `RND` anteprima renderizzata · `CND` condizioni · `RUL` regole di notifica · `RAD` gestione delle regole · `DDP` deduplica ed effetti · `SNP` snapshot del membro · `IBX` inbox · `CLN` pulizie · `THM` formato dei colori · `THA` tema via API · `WURL` URL dei webhook · `WPRF` profili e invio · `WSIG` firma · `WRTY` ritenti · `WSUB` sottoscrizioni · `WDLV` consegne e gestione · `WROL` ruoli dei webhook.
- **Colonna "test":** classe (e metodo o file CSV). Classi unitarie in `src/test/java/io/loyaltyhub/engagement/domain/` (`TestbookEngContentSelectionTest`, `TestbookEngTemplateTest`, `TestbookEngConditionTest`, `TestbookEngThemeTest`, `TestbookEngWebhookTest`), d'integrazione in `src/test/java/io/loyaltyhub/engagement/` (`TestbookEngContentIT`, `TestbookEngMessagingIT`, `TestbookEngWebhookIT`, un contesto Spring per classe, EmbeddedKafka + Postgres embedded come gli altri `*IT` del modulo). Le tabelle sono in `src/test/resources/testbook/engagement/*.csv` (separatore TAB, prima colonna l'ID, seconda la descrizione).
- **Nome dei casi.** Le righe dei CSV sono casi `@ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)` con `@CsvFileSource` (colonne lette con `TestbookRows.columns`); le righe singole sono metodi `@Test` con `@DisplayName("[<ID>] …")`. Nei rapporti XML il caso parametrizzato è `metodo(ArgumentsAccessor)[TB-ENG-…] …`, forma che `scripts/testbook-report.mjs` riconosce. Il `pom.xml` del modulo (che ridefinisce le risorse di test per i contratti) vi aggiunge `src/test/resources`.
- **Esecuzione:** `./mvnw -q -pl services/engagement-service -am verify -Dtest='Testbook*' -Dit.test='Testbook*' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`, oppure `bash scripts/testbook.sh` (rapporto in `target/testbook/rapporto.md`).
- **Dati e tempo.** Ogni riga crea i propri codici (`CNT-TB-n`, `MSG-TB-n`, `NR-TB-n`, `WH-TB-n`), membri nuovi (`MBR-9…`, `MBR-8…`, `MBR-7…`, inseriti nello snapshot locale o mai visti) ed eventi nuovi; i contenuti `LIVE` hanno un pubblico ristretto a un segmento proprio o sono chiusi a fine riga; i pop-up del seed sono archiviati all'avvio di `TestbookEngContentIT`. Unit: orologio fisso (`2026-09-24T10:00:00Z`) o istanti espliciti. `TestbookEngContentIT` sostituisce il `Clock` con uno a scostamento regolabile (mezzanotte di Roma); `TestbookEngWebhookIT` lancia i giri dello scheduler con l'istante voluto. Attese a polling con scadenza (20 s), niente pause fisse.
- **Marcature.** **AMBIGUO** = la specifica tace e `docs/15` non registra una scelta: il test asserisce il comportamento attuale (`// TESTBOOK: ambiguo, vedi <ID>`). **DIVERGENZA** = il codice non rispetta la specifica: il test asserisce la specifica e fallisce (registro al §17).

## 1. Inventario delle regole

| Regola | Testo | Fonte | Codice | Aree |
|---|---|---|---|---|
| R01 | Un contenuto è visibile se `LIVE` ∧ in calendario ∧ pubblico soddisfatto; ordine per `priority` decrescente | docs/03 §9; F-CNT-01 | `ContentSelection.select/exclusion` :42–69, `inSchedule` :102–104 | SEL, CAL, ORD |
| R02 | Limiti per posizionamento: `HOME_HERO` 1, `HOME_GRID` 6; `CATALOG_TOP` 1, `CONTEST` 3, `WIN` 1 | docs/03 §9; Q-72 | `ContentSelection.LIMITS` :23 | ORD |
| R03 | Pubblico `{tiers[], segments[], statuses[]}`, vuoto = tutti; una dimensione non vuota va soddisfatta (livello presente, almeno un segmento in comune, stato presente); tra dimensioni diverse il codice usa AND, non prescritto (**AMBIGUO**, docs/17 US-E07-03; Q-161) | engagement §2; docs/03 §9 | `ContentSelection.inAudience` :112–127 | AUD, PRV |
| R04 | Estensioni del seed `registeredWithinDays` (iscritti da < N giorni) e `daysOfWeek` (giorno di Roma) | Q-71; docs/10 §7; docs/03 §3.3 | `ContentSelection.inAudience` :128–135 | EXT |
| R05 | Anteprima per membro con motivo di esclusione `NOT_IN_AUDIENCE`, `OUT_OF_SCHEDULE`, `NOT_LIVE`, `FREQUENCY` | engagement §3; F-CNT-04; BO-18 | `ContentService.preview` :174–189 | SEL, PRV |
| R06 | Pop-up: al più uno, il primo per priorità che rispetta la frequenza (`ONCE` mai visto, `ONCE_PER_DAY` non visto oggi, `ALWAYS`) | docs/03 §9; F-CNT-02 | `ContentSelection.selectPopup/frequencyAllows` :76–100 | POP, POPA |
| R07 | `popups/next` risponde `204` se nessuno; la vista si registra con `seen` (non alla lettura) nel giorno di Roma | engagement §3, §5, §7 | `ContentService.nextPopup/seen` :144–163; `PortalPopupsController` | POPA |
| R08 | Card vincita mostrata per premio (`WIN` + `prizeCode`) | F-CNT-03; engagement §3 | `ContentService.portal` :127–138 | PRV |
| R09 | Ciclo di vita dei contenuti senza approvazione: `DRAFT→LIVE`, `LIVE⇄PAUSED`, `LIVE/PAUSED→ENDED`, `DRAFT/ENDED→ARCHIVED`; il resto `409` | docs/03 §3.6; docs/06 §2, §7; engagement §3 | `ContentService.TRANSITIONS` :84–89, `transition` :245–256 | LIF |
| R10 | Ogni transizione produce `content.status.changed` | engagement §4; docs/05 | `ContentService.changeStatus` :290–300 | LIF, END |
| R11 | Un contenuto `LIVE` si modifica solo nei campi sicuri (titolo, testo, `endAt`, priorità, immagine); per il resto si duplica (`409`) | docs/03 §3.6; docs/06 §2; docs/08 §3.2 | `ContentService.update` :224–230, `lockedChanges` :400 (`409 CONTENT_LIVE_LOCKED`) | EDL |
| R12 | Duplicazione: copia nuova in `DRAFT` | engagement §3; docs/03 §3.6 | `ContentService.duplicate` :260–273 | DUP |
| R13 | `ENDED` automatico dei contenuti con `end_at` passato (job ogni 10 min) | engagement §5 | `ContentService.endExpired` :277–286; `ContentRepository.expiredBy` | END |
| R14 | Campi validi: `kind`, `placement`, `link_type`, `frequency` (solo pop-up), `style.tone` negli enumerati; codice `^[A-Z][A-Z0-9-]{2,39}$` | engagement §2; docs/06 §2 | `ContentService.create` :194–208, `validated` :323–392 | EDT |
| R15 | Codice duplicato `409`; lock ottimistico `409` | docs/06 §2, §4 | `ContentService.create` :200, `update` :222–234; stessi rami in template, regole, tema, webhook | EDT, TAD, RAD, THA, WDLV |
| R16 | Scritture di contenuti (anche transizioni e duplicazione), template, regole e tema: `content.write` (ADMIN, MARKETING); `ANALYST` e intestazione assente rifiutati | docs/08 §2; docs/06 §3 | `@RequiresRole` nei controller | ROL |
| R17 | Endpoint del portale sempre con `memberId` esplicito (altrimenti `400`), senza intestazione `X-LH-Actor` | docs/06 §2, §3 | `ContentService.portal` :128–130, `nextPopup`/`seen` :145, :154, `preview` :175; `InboxService` | PRV, POPA, IBX, ROL |
| R18 | Motore dei template: `{{percorso}}` sul contesto `{data, member, event}`, percorso assente → stringa vuota + `WARN`, nessuna logica | engagement §5; docs/03 §9; Q-66 | `TemplateEngine.render/resolve` :56–140; `MessageContexts.build` | TPL, DDP, RND |
| R19 | Formattatori `|number` e `|date` (it-IT, Europe/Rome) | engagement §5; docs/07 §2 | `TemplateEngine.format/number/date` :142–187 | TPL |
| R20 | Template validi: segnaposto con radice `data./member./event.`, formattatori ammessi, graffe bilanciate; titolo e testo obbligatori | engagement §5; F-MSG-02; Q-66 | `TemplateEngine.problems` :90–116; `TemplateAdminService.build` :129–150 | TPV, TAD |
| R21 | Template: canale `INAPP`/`EMAIL_FAKE`, categoria `POINTS/TIER/REWARD/GAME/PROGRAM`; `EMAIL_FAKE` solo nel registro | engagement §1, §2; Q-67 | `TemplateAdminService.build`; `InboxService.portalInbox` :120–127; `InboxRepository.unreadInApp` :70 | TAD, DDP, IBX |
| R22 | Una regola collega un tipo di fatto (+ condizione opzionale su `data.*`) a un template; solo le regole attive | docs/03 §9; engagement §2 | `NotificationService.apply` :41–59; `RuleRepository.findEnabledFor` | RUL |
| R23 | Condizioni nel formato di docs/03 §3.3 (campo assente → falsa tranne `nexists`, tipi incompatibili → falsa, array: almeno uno) | docs/03 §3.3; engagement §2 | `DataCondition` :28–295 | CND |
| R24 | `message.delivered` non è mai oggetto di regole né di webhook | engagement §5 | `FactHandler` :45; `NotificationService` :43; `RuleAdminService.build` :127; `WebhookService.enqueue` :93, `build` :262 | DDP, RAD, WSUB, WDLV |
| R25 | Deduplica dei messaggi per `(memberId, sourceEventId, templateCode)` | docs/03 §9; engagement §2, §7 | `InboxRepository.insertIfAbsent`; `InboxService.deliver` :94–97 | DDP |
| R26 | Ogni messaggio nuovo produce `message.delivered`, figlio del fatto sorgente | engagement §4; docs/05 | `InboxService.deliver` :98–104 | DDP |
| R27 | Nessun messaggio ai membri `ANONYMIZED` né `INACTIVE`; i `BLOCKED` e i membri senza snapshot li ricevono | Q-70; Q-180 | `InboxService.deliver` :82–87 | RUL, DDP |
| R28 | Effetto `message.send`: `data.*` = effetto con `params`; deduplica su `effectId`; template inesistente → errore «a valle in DLQ» | docs/03 §3.4; Q-68; Q-73; campaign §5 | `MessageSendHandler.handle` :40–61 | DDP |
| R29 | Gestione delle regole: tipo del catalogo fatti, template esistente, condizione solo su `data.*` | BO-19; engagement §2; docs/05 | `RuleAdminService.create/update/build` :67–143 | RAD |
| R30 | Anteprima renderizzata `POST …/render` su un evento campione | engagement §3; BO-19; Q-75 | `TemplateAdminService.render` :100–127 | RND |
| R31 | Inbox del portale: elenco dal più recente, non letti, segna letto, segna tutte | PT-12; engagement §3 | `InboxService` :120–149; `InboxRepository` :54–87 | IBX |
| R32 | Elenchi paginati `{items, page}`, `size` massimo 100 | docs/06 §2 | `InboxService.clampSize` :158; `WebhookService.deliveries` :186–188 | IBX |
| R33 | Pulizie: `inbox_message` > 180 giorni, `webhook_delivery` > 14 giorni, `popup_view` > 90 giorni | engagement §5 | `EngagementJobs.purgeInbox` :54–68 | CLN |
| R34 | Tema: colori esadecimali; contrasto testo (`night`) su `primary` e su `bg` ≥ 4,5 altrimenti `422 THEME_CONTRAST_TOO_LOW` | engagement §3; Q-79; BO-20 | `Theme` :26–59; `ThemeService.validated` :66–112 | THM, THA |
| R35 | Tema applicato a runtime dal portale; `/v1/portal/theme` in cache 60 s | engagement §3, §7; F-THM-01 | `ThemeController`; `ThemeService.get` :41–43 | THA |
| R36 | Webhook: una consegna per ogni webhook **attivo** sottoscritto al tipo; idempotente per (webhook, evento); corpo = CloudEvent originale | F-WBH-01; engagement §2, §5 | `WebhookService.enqueue` :91–111; `WebhookRepository.findEnabledFor` | WSUB |
| R37 | Firma `X-LH-Signature: sha256=<HMAC(secret, body)>`, header `X-LH-Event-Id`, `X-LH-Delivery-Id` | engagement §5; F-WBH-01 | `WebhookSignature`; `WebhookHttpSender.send` :63–110 | WSIG, WDLV |
| R38 | Timeout 5 s; ritenti a 1, 5, 15 min poi `GAVE_UP`; solo 2xx è successo | engagement §5, §7; Q-98 | `WebhookRetry` :18–52; `WebhookDispatcher.attempt` :77–90; `WebhookHttpSender.TIMEOUT` | WRTY, WDLV, WPRF |
| R39 | *Riprova* manuale: un tentativo immediato con la stessa tabella; dopo `GAVE_UP` un fallimento resta `GAVE_UP` | Q-98; BO-23 | `WebhookService.retry` :226–236; `WebhookRetry.isRetryable` | WRTY, WDLV |
| R40 | Solo `https://` (in `local` anche `http://localhost`); indirizzi privati e loopback bloccati in ogni profilo tranne `local`, al salvataggio e dopo la risoluzione DNS | engagement §5; docs/11; Q-99 | `WebhookUrlPolicy` :28–106; `WebhookHttpSender` :47–81 | WURL, WPRF, WDLV |
| R41 | Evento di prova: esempio del contratto del primo tipo sottoscritto, id/ora/correlazione nuovi, `lhactor` di chi invia; `201` con la consegna | BO-23; Q-100; Q-103 | `WebhookService.test` :203–223 | WDLV |
| R42 | Il segreto si legge solo alla creazione | engagement §3; BO-23 | `WebhookService.create` :141; `Webhook` (`@JsonInclude`) | WDLV, AUDT |
| R43 | Gestione dei webhook: tipi dal catalogo fatti, `webhook.write` solo ADMIN; eliminazione col registro | BO-23; docs/08 §2 | `WebhookService.build` :245–272, `delete` :170–175; `WebhooksController` | WDLV, WROL |
| R44 | Audit su `lh.audit.v1` delle scritture su contenuti, template, regole, tema, webhook | engagement §4; docs/03 §3.6; docs/06 §3 | `audit.record(…)` nei servizi applicativi | AUDT |
| R45 | Snapshot locale del membro dai fatti `member.*`, `tier.*`, `member.segment.*` | engagement §4; docs/05 | `FactHandler.updateSnapshot` :53–96 | SNP |
| R46 | `/v1/demo/**` solo ADMIN | docs/06 §3; Q-103 | `EngagementJobsController` | WDLV |
| R47 | Anonimizzazione: il nome nei messaggi già in inbox diventa "Membro anonimo", i corpi delle consegne webhook sono ripuliti e rifirmati, lo snapshot perde il nome | docs/03 §2; Q-125; Q-70 | `FactHandler` :93; `MemberErasureRepository.erase` | SNP |

## 2. Rami del codice e regole

Ogni condizione, eccezione o uscita anticipata delle classi `domain`, `application`, `api` (e dei due handler `messaging`) è mappata su una regola; *senza spec* = ramo senza specifica (le righe che lo provano sono **AMBIGUO**); *non coperto* = ramo senza riga nel testbook.

| Ramo (file:riga) | Regola | Righe |
|---|---|---|
| `ContentSelection` :47–51 idoneo/escluso | R01 | SEL |
| `ContentSelection` :53 ordine per priorità | R01 | ORD-001, ORD-003 |
| `ContentSelection` :53 spareggio per codice | senza spec | ORD-002 |
| `ContentSelection` :54 taglio al limite | R02 | ORD-004…013 |
| `ContentSelection` :59 non `LIVE` → `NOT_LIVE` | R01, R05 | SEL, PRV-002 |
| `ContentSelection` :62, :103 fuori calendario → `OUT_OF_SCHEDULE`; estremi inclusi/esclusi | R01, R05 (estremi senza spec) | SEL, CAL, PRV-003 |
| `ContentSelection` :65 fuori pubblico → `NOT_IN_AUDIENCE` | R03, R05 | SEL, AUD, PRV-001 |
| `ContentSelection` :82 frequenza → `FREQUENCY` | R06 | POP, PRV-004 |
| `ContentSelection` :92 al più un pop-up | R06 | POP-035, POPA-012 |
| `ContentSelection` :96 mai visto o `ALWAYS` | R06 | POP |
| `ContentSelection` :99 `ONCE_PER_DAY` visto prima di oggi (Roma) | R06 | POP-009…016, POP-025…034, POPA-006 |
| `ContentSelection` :113 pubblico nullo o vuoto | R03 | AUD-028, AUD-032 |
| `ContentSelection` :119 livelli | R03 | AUD, AUD-029 |
| `ContentSelection` :122 segmenti | R03 | AUD, AUD-030 |
| `ContentSelection` :125 stati | R03 | AUD, AUD-031, AUD-033…036 |
| `ContentSelection` :129–131 `registeredWithinDays` | R04 | EXT-001…006 |
| `ContentSelection` :133–135 `daysOfWeek` | R04 | EXT-007…016 |
| `ContentSelection` :140 lista non array trattata come vuota | senza spec | non coperto |
| `ContentService` :128–130 portale senza `memberId` → `400` | R17 | PRV-009 |
| `ContentService` :133 filtro `WIN` per `prizeCode` | R08 | PRV-007 |
| `ContentService` :145, :154 `memberId` assente → `400` | R17 | POPA-002, POPA-010 |
| `ContentService` :157 id sconosciuto → `404` | docs/06 §2 | POPA-011, EDT-058, DUP-007 |
| `ContentService` :158 `seen` su non pop-up → `404` | R07 | POPA-009 |
| `ContentService` :175 anteprima senza membro → `400` | R05 | PRV-005 |
| `ContentService` :178 anteprima dei pop-up (`placement=POPUP`) | R05 | PRV-004 |
| `ContentService` :185 anteprima `WIN` senza limite | senza spec | PRV-012 |
| `ContentService` :196 codice non valido → `422` | R14 | EDT-028…034 |
| `ContentService` :200 codice già usato → `409` | R15 | EDT-053 |
| `ContentService` :213 `ARCHIVED` ed `ENDED` non modificabili | senza spec; Q-174 DECISA | EDT-057, EDT-060 |
| `ContentService` :216 codice immutabile | senza spec | EDT-055 |
| `ContentService` :219 tipo immutabile | senza spec (coerente con R11) | EDT-056 |
| `ContentService` :222 `PUT` senza versione = nessun controllo | senza spec | non coperto |
| `ContentService` :224–230, :400–427 `LIVE` o `PAUSED`: campo non sicuro cambiato → `409 CONTENT_LIVE_LOCKED` | R11; Q-174 DECISA per `PAUSED` | EDL-006…010, EDT-061 |
| `ContentService` :232 versione superata → `409` | R15 | EDT-054 |
| `ContentService` :248 transizione non ammessa → `409` | R09 | LIF |
| `ContentService` :262–266 codice della copia (troncamento, `-COPIA-n`) | senza spec | DUP-002, DUP-006 |
| `ContentService` :279 fine automatica | R13 | END |
| `ContentService` :303, :308 membro sconosciuto → spettatore sconosciuto (membro assente: difensivo, i chiamanti rispondono prima `400`) | R03 | PRV-010 |
| `ContentService` :313 posizionamento non valido → `400` | docs/06 §2 | PRV-006, PRV-008 |
| `ContentService` :327 `kind` | R14 | EDT-002, EDT-003 |
| `ContentService` :330–331 `placement` (pop-up senza) | R14 | EDT-004…010 |
| `ContentService` :334 banner solo `CATALOG_TOP` | senza spec | EDT-011, EDT-012 |
| `ContentService` :338–342 titolo obbligatorio, ≤ 80 | F-CNT-01 / lunghezza senza spec | EDT-035…038 |
| `ContentService` :344 testo ≤ 280 | senza spec | EDT-039, EDT-040 |
| `ContentService` :348–352 `linkType` (default `NONE`) | R14 | EDT-013…016 |
| `ContentService` :355 `linkCode` obbligatorio | senza spec | EDT-017 |
| `ContentService` :358 `PRIZE` ⇔ `WIN` | senza spec | EDT-018, EDT-019 |
| `ContentService` :362 CTA `/portal…` o `https://` | R14 (`http://` senza spec) | EDT-049…052 |
| `ContentService` :367 fine non dopo l'inizio | F-CNT-01 (uguaglianza senza spec) | EDT-046…048 |
| `ContentService` :370–371 priorità 0…1000, default 50 | senza spec | EDT-041…045 |
| `ContentService` :374–376 frequenza (default `ONCE`) | R14 | EDT-010, EDT-020…023 |
| `ContentService` :378 chiudibile (default vero) | R14 (default senza spec) | EDT-024, EDT-025 |
| `ContentService` :382 `style.tone` | R14 | EDT-026, EDT-027 |
| `ContentService` :434–450 pubblico normalizzato in maiuscolo | R03 | PRV-001 |
| `TemplateEngine` :57 template nullo o vuoto | R18 | TPL-013, TPL-014 |
| `TemplateEngine` :68 percorso assente → vuoto + mancante | R18 | TPL-004…007, TPL-009, TPL-041 |
| `TemplateEngine` :125–138 navigazione (indici, oggetti, valori non semplici) | R18 (indici senza spec) | TPL-006…008 |
| `TemplateEngine` :143 senza formattatore | R18 | TPL-001…003 |
| `TemplateEngine` :147 `|number` | R19 | TPL-019…029 |
| `TemplateEngine` :161 `|number` su non numero | senza spec | TPL-030 |
| `TemplateEngine` :165 due decimali, arrotondamento | senza spec | TPL-027, TPL-028 |
| `TemplateEngine` :173–182 `|date` (data, istante, offset) | R19 | TPL-031…037 |
| `TemplateEngine` :183 `|date` non valida | senza spec | TPL-038, TPL-039 |
| `TemplateEngine` :149 formattatore sconosciuto in resa | senza spec | TPL-040 |
| `TemplateEngine` :92–113 problemi di sintassi | R20 | TPV |
| `DataCondition` :73–89 gruppi, foglie, condizione nulla | R23 | CND |
| `DataCondition` :118–135 `exists`, `nexists`, assente, array | R23 | CND |
| `DataCondition` :143–157 comparatori | R23 | CND |
| `DataCondition` :92–113 gruppi vuoti | senza spec | CND-063, CND-064 |
| `DataCondition` :156 comparatore sconosciuto | senza spec | CND-065 |
| `DataCondition` :264–275 stringa numerica convertita | senza spec | CND-049 |
| `DataCondition` :39–70 problemi di forma | R29 | RAD-007…009 |
| `NotificationService` :43 senza membro o `message.delivered` | R24 | DDP-006 |
| `NotificationService` :48 condizione falsa | R22 | RUL |
| `NotificationService` :52 template inesistente | ramo difensivo (FK) | non coperto |
| `InboxService` :82 membro `ANONYMIZED` | R27 | RUL, DDP-011 |
| `InboxService` :94 duplicato | R25 | DDP-001…005, DDP-008 |
| `InboxService` :98–103 `message.delivered` | R26 | DDP-005 |
| `InboxService` :120–127 solo `INAPP` nel portale | R21 | IBX-003, DDP-012 |
| `InboxService` :138 messaggio non del membro → `404` | R31 | IBX-006, IBX-007 |
| `InboxService` :152 `memberId` assente → `400` | R17 | IBX-010…013 |
| `InboxService` :159 `size` 1…100 | R32 | IBX-016 |
| `InboxRepository` :77 già letto non cambia `readAt` | senza spec | IBX-005 |
| `PortalInboxController` :55 `memberId` nel corpo | senza spec | IBX-014 |
| `MessageContexts` :35–55 contesto `{data, member, event}` | R18 | DDP-013, RND-001 |
| `MessageSendHandler` :43 effetto senza dati o membro → DLQ `INVALID_EFFECT` | senza spec | DDP-014 |
| `MessageSendHandler` :48 template sconosciuto → DLQ `TEMPLATE_NOT_FOUND` | R28 (campaign §5) | DDP-010 |
| `MessageSendHandler` :52 `params` sovrapposti | R28 | DDP-007 |
| `MessageSendHandler` :59 chiave `effectId` o id evento | R28 | DDP-008, DDP-009 |
| `FactHandler` :45 `message.delivered` ignorato | R24 | DDP-006, WSUB-009 |
| `FactHandler` :60–87 snapshot per tipo | R45 | SNP-001…007 |
| `FactHandler` :93 anonimizzazione (messaggi, consegne, snapshot) | R47 | SNP-008, SNP-009 |
| `RuleAdminService` :68–78 corpo, codice, codice usato | R29, R15 | RAD-010 |
| `RuleAdminService` :92 codice immutabile | senza spec | RAD-012 |
| `RuleAdminService` :97 versione superata | R15 | RAD-011 |
| `RuleAdminService` :111–117 tipo completo → breve | senza spec | RAD-002 |
| `RuleAdminService` :125–136 tipo assente/`message.delivered`/sconosciuto, template | R29, R24 | RAD-003…006 |
| `TemplateAdminService` :63–68 codice non valido/usato | R15 | TAD-011 |
| `TemplateAdminService` :83 codice immutabile | senza spec | TAD-014 |
| `TemplateAdminService` :88 versione superata | R15 | TAD-012 |
| `TemplateAdminService` :105–120 membro dal `subject`, bozza nella richiesta | R30 | RND-001, RND-002 |
| `TemplateAdminService` :131 canale di default `INAPP` | senza spec | TAD-004 |
| `TemplateAdminService` :138–144 nome, canale, categoria, titolo, testo, sintassi | R20, R21 (nome senza spec) | TAD-002…010 |
| `Theme` :27 formato `#RRGGBB` | R34 (3/8 cifre e senza `#` senza spec) | THM-001…014 |
| `ThemeService` :42 ripiego Aurora senza riga | R35 | non coperto (il seed ha sempre il tema) |
| `ThemeService` :49 versione superata | R15 | THA-017 |
| `ThemeService` :69–71 nome obbligatorio, ≤ 40 | senza spec | THA-020, THA-021 |
| `ThemeService` :77 colore non esadecimale o assente | R34 | THA-010, THA-012, THA-014 |
| `ThemeService` :84 logo `/…` o `https://` | senza spec | THA-022, THA-023 |
| `ThemeService` :90 nomi delle valute di default | Q-79 | THA-019 |
| `ThemeService` :98 contrasto su `primary` | R34 | THA-002…005, THA-008, THA-009 |
| `ThemeService` :102 contrasto su `bg` | R34, Q-79 | THA-006…008 |
| `WebhookService` :93 non un fatto o `message.delivered` | R24 | WSUB-009 |
| `WebhookService` :98 nessun webhook attivo sottoscritto | R36 | WSUB |
| `WebhookService` :105 consegna già presente | R36 | WSUB-002 |
| `WebhookService` :129–135 codice generato, non valido, usato | R15; docs/06 §2 | WDLV-028, WDLV-030 (codice generato: le righe senza `code`, es. WROL) |
| `WebhookService` :150 codice immutabile | senza spec | WDLV-029 |
| `WebhookService` :155 versione superata | R15 | WDLV-024 |
| `WebhookService` :179 stato di consegna sconosciuto → `400` | docs/06 §2 | WDLV-026 |
| `WebhookService` :208, :288–307 tipo dell'evento di prova ed esempio del contratto | R41 | WDLV-016 |
| `WebhookService` :228 non ritentabile → `409` | R39 | WDLV-012, WDLV-013 |
| `WebhookService` :235 consegna già in invio → `DELIVERY_BUSY` | senza spec | WDLV-031 |
| `WebhookService` :251–266 nome, URL, tipi | R43, R40 | WDLV-019…023 |
| `WebhookDispatcher` :77–89 esito e prossimo tentativo | R38 | WDLV-001…008 |
| `WebhookDispatcher` :82 errore `HTTP_ERROR` | R38 | WDLV-002 |
| `WebhookRetry` :36–42 tabella dei ritenti | R38, R39 | WRTY-001…010 |
| `WebhookRetry` :45–47 solo 2xx | R38, Q-98 | WRTY-011…019 |
| `WebhookRetry` :50–52 ritentabili `FAILED`/`GAVE_UP` | R39 | WRTY-020…023 |
| `WebhookSignature` :42 firma assente | R37 | WSIG-006 |
| `WebhookUrlPolicy` :29–50 vuoto, lunghezza, sintassi, host, credenziali, frammento | R40 (lunghezza, credenziali, frammento senza spec) | WURL-004, WURL-005, WURL-048…051 |
| `WebhookUrlPolicy` :53–58 schemi | R40 | WURL-001…003, WURL-052…056 |
| `WebhookUrlPolicy` :61 nomi locali (`.localhost`, `.local`, `.internal`) | R40 (`.local`, `.internal` senza spec) | WURL-042…046 |
| `WebhookUrlPolicy` :65, :74–92 indirizzi bloccati | R40 (intervalli speciali senza spec) | WURL-006…041 |
| `WebhookUrlPolicy` :97 non letterale (forma decimale) | Q-99 | WURL-047 |
| `WebhookHttpSender` :47–51 politica per profilo | R40, Q-99 | WPRF-001…005 |
| `WebhookHttpSender` :66 URL non ammesso all'invio | R40 | WPRF-006 |
| `WebhookHttpSender` :70–79 blocco dopo la risoluzione, host sconosciuto | R40 (host sconosciuto non coperto) | WPRF-007 |
| `WebhookHttpSender` :98 timeout | R38 | WPRF-008 |
| `WebhookHttpSender` :100–108 errori di rete | R38 | non coperto |
| `EngagementJobs` :54–68 pulizie | R33 | CLN |
| `EngagementJobsController` :51–57 `asOf` vuoto, istante, data | Q-103 (data = fine giornata senza spec) | non coperto (usato come istante in WDLV) |
| `@RequiresRole` di `ContentsController` (creazione, modifica, transizioni, duplicazione), `MessageTemplatesController`, `NotificationRulesController`, `ThemeController` | R16 | ROL |
| `@RequiresRole` di `WebhooksController` (creazione, modifica, eliminazione, prova, Riprova), `EngagementJobsController` | R43, R46 | WROL, WDLV-027 |
| `PortalPopupsController` `204` | R07 | POPA-001 |
| `ThemeController` `Cache-Control` 60 s | R35 | THA-016 |

## 3. Selezione dei contenuti (R01–R04)

**Regola.** «Selezione contenuti per posizionamento: `LIVE` ∧ in calendario ∧ pubblico soddisfatto, ordinati per `priority` desc; `HOME_HERO` ne mostra 1, `HOME_GRID` fino a 6» (docs/03 §9). Pubblico `audience jsonb {tiers[], segments[], statuses[]}`, «vuoto = tutti» (engagement §2). Limiti degli altri posizionamenti: Q-72 (`CATALOG_TOP` 1, `CONTEST` 3, `WIN` 1). Chiavi aggiuntive del seed (Q-71): `registeredWithinDays` («iscritti da < 7 giorni», docs/10 §7) e `daysOfWeek` («solo sab–dom»), giorno calcolato in Europe/Rome come i campi `context.*` di docs/03 §3.3.

| Ingresso | Classi valide | Classi non valide / limiti |
|---|---|---|
| stato | `LIVE` | `DRAFT`, `PAUSED`, `ENDED`, `ARCHIVED` |
| calendario | senza date, dentro (`start ≤ now < end`) | prima dell'inizio, dopo la fine; estremi `start = now`, `end = now`, ±1 s |
| livelli | vuoto, contiene il livello | non lo contiene; membro senza livello (sconosciuto) |
| segmenti | vuoto, almeno uno in comune | nessuno in comune; membro senza segmenti |
| stati del membro | vuoto, contiene lo stato (`ACTIVE`, `INACTIVE`, `BLOCKED`, `ANONYMIZED`) | non lo contiene; stato ignoto |
| `registeredWithinDays` = 7 | iscritto da 0 s … 6 g 23:59:59 | 7 g esatti, 7 g + 1 s, iscrizione ignota |
| `daysOfWeek` = [SAT, SUN] | sab 00:00 … dom 23:59:59 di Roma (anche nei giorni del cambio d'ora) | ven 23:59:59, lun 00:00 di Roma; `[]` = ogni giorno |
| priorità | 0 … 1000 | parità |
| numero di idonei | 0 … limite | limite + 1 |

Come si combinano livelli, segmenti e stati non è scritto: il codice richiede tutte le dimensioni non vuote (AND), mentre il pubblico delle campagne è "livello **o** segmento"; docs/17 (US-E07-03, nodo ENG-02) lo segnala da decidere (domanda Q-161 in `docs/15`). Le righe AUD in cui una dimensione è soddisfatta e un'altra no sono quindi **AMBIGUO**.

**Strategia.** Idoneità: tabella decisionale **completa** stato (5) × calendario (4) × pubblico (3) = 60 righe; con più condizioni violate la specifica non fissa quale motivo mostrare, quindi l'atteso è «escluso con uno dei motivi violati». Estremi del calendario provati uno alla volta. Pubblico: tabella **completa** livelli (3) × segmenti (3) × stati (3) = 27 più le classi del membro sconosciuto (4), il pubblico `null` e ogni stato del membro. Estensioni Q-71 e ordine/limiti: ogni valore limite da solo (min−1, min, min+1 dei 7 giorni; mezzanotte di Roma venerdì/sabato e domenica/lunedì, anche a marzo e ottobre; limite −1, limite, limite +1 per `HOME_HERO` e `HOME_GRID`, limite e limite +1 per i posizionamenti di Q-72).

### 3.1 Idoneità (SEL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-SEL-001 | `DRAFT` · senza date · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-002 | `DRAFT` · senza date · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-003 | `DRAFT` · senza date · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-004 | `DRAFT` · prima dell'inizio · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-005 | `DRAFT` · prima dell'inizio · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-006 | `DRAFT` · prima dell'inizio · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-007 | `DRAFT` · dentro il calendario · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-008 | `DRAFT` · dentro il calendario · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-009 | `DRAFT` · dentro il calendario · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-010 | `DRAFT` · dopo la fine · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-011 | `DRAFT` · dopo la fine · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-012 | `DRAFT` · dopo la fine · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-013 | `LIVE` · senza date · pubblico vuoto | visibile | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-014 | `LIVE` · senza date · pubblico soddisfatto | visibile | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-015 | `LIVE` · senza date · pubblico non soddisfatto | escluso, motivo `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-016 | `LIVE` · prima dell'inizio · pubblico vuoto | escluso, motivo `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-017 | `LIVE` · prima dell'inizio · pubblico soddisfatto | escluso, motivo `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-018 | `LIVE` · prima dell'inizio · pubblico non soddisfatto | escluso, motivo `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-019 | `LIVE` · dentro il calendario · pubblico vuoto | visibile | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-020 | `LIVE` · dentro il calendario · pubblico soddisfatto | visibile | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-021 | `LIVE` · dentro il calendario · pubblico non soddisfatto | escluso, motivo `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-022 | `LIVE` · dopo la fine · pubblico vuoto | escluso, motivo `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-023 | `LIVE` · dopo la fine · pubblico soddisfatto | escluso, motivo `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-024 | `LIVE` · dopo la fine · pubblico non soddisfatto | escluso, motivo `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-025 | `PAUSED` · senza date · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-026 | `PAUSED` · senza date · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-027 | `PAUSED` · senza date · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-028 | `PAUSED` · prima dell'inizio · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-029 | `PAUSED` · prima dell'inizio · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-030 | `PAUSED` · prima dell'inizio · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-031 | `PAUSED` · dentro il calendario · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-032 | `PAUSED` · dentro il calendario · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-033 | `PAUSED` · dentro il calendario · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-034 | `PAUSED` · dopo la fine · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-035 | `PAUSED` · dopo la fine · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-036 | `PAUSED` · dopo la fine · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-037 | `ENDED` · senza date · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-038 | `ENDED` · senza date · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-039 | `ENDED` · senza date · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-040 | `ENDED` · prima dell'inizio · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-041 | `ENDED` · prima dell'inizio · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-042 | `ENDED` · prima dell'inizio · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-043 | `ENDED` · dentro il calendario · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-044 | `ENDED` · dentro il calendario · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-045 | `ENDED` · dentro il calendario · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-046 | `ENDED` · dopo la fine · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-047 | `ENDED` · dopo la fine · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-048 | `ENDED` · dopo la fine · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-049 | `ARCHIVED` · senza date · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-050 | `ARCHIVED` · senza date · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-051 | `ARCHIVED` · senza date · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-052 | `ARCHIVED` · prima dell'inizio · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-053 | `ARCHIVED` · prima dell'inizio · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-054 | `ARCHIVED` · prima dell'inizio · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-055 | `ARCHIVED` · dentro il calendario · pubblico vuoto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-056 | `ARCHIVED` · dentro il calendario · pubblico soddisfatto | escluso, motivo `NOT_LIVE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-057 | `ARCHIVED` · dentro il calendario · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-058 | `ARCHIVED` · dopo la fine · pubblico vuoto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-059 | `ARCHIVED` · dopo la fine · pubblico soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |
| TB-ENG-SEL-060 | `ARCHIVED` · dopo la fine · pubblico non soddisfatto | escluso, motivo `NOT_LIVE` o `OUT_OF_SCHEDULE` o `NOT_IN_AUDIENCE` | docs/03 §9; engagement §3 preview; F-CNT-01/04 | `TestbookEngContentSelectionTest` · `sel.csv` |

### 3.2 Estremi del calendario (CAL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-CAL-001 | `startAt` = adesso (contenuto `LIVE`, pubblico vuoto) | **AMBIGUO** — visibile | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |
| TB-ENG-CAL-002 | `startAt` = adesso + 1 s (contenuto `LIVE`, pubblico vuoto) | escluso (`OUT_OF_SCHEDULE`) | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |
| TB-ENG-CAL-003 | `startAt` = adesso − 1 s (contenuto `LIVE`, pubblico vuoto) | visibile | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |
| TB-ENG-CAL-004 | `endAt` = adesso (contenuto `LIVE`, pubblico vuoto) | **AMBIGUO** — escluso (`OUT_OF_SCHEDULE`) | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |
| TB-ENG-CAL-005 | `endAt` = adesso + 1 s (contenuto `LIVE`, pubblico vuoto) | visibile | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |
| TB-ENG-CAL-006 | `endAt` = adesso − 1 s (contenuto `LIVE`, pubblico vuoto) | escluso (`OUT_OF_SCHEDULE`) | docs/03 §9 (in calendario); engagement §5 (end_at passato) | `TestbookEngContentSelectionTest` · `cal.csv` |

### 3.3 Pubblico (AUD)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-AUD-001 | livelli vuoto · segmenti vuoto · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-002 | livelli vuoto · segmenti vuoto · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-003 | livelli vuoto · segmenti vuoto · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-004 | livelli vuoto · segmenti almeno uno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-005 | livelli vuoto · segmenti almeno uno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-006 | livelli vuoto · segmenti almeno uno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-007 | livelli vuoto · segmenti nessuno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-008 | livelli vuoto · segmenti nessuno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-009 | livelli vuoto · segmenti nessuno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-010 | livelli soddisfatto · segmenti vuoto · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-011 | livelli soddisfatto · segmenti vuoto · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-012 | livelli soddisfatto · segmenti vuoto · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-013 | livelli soddisfatto · segmenti almeno uno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-014 | livelli soddisfatto · segmenti almeno uno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | nel pubblico | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-015 | livelli soddisfatto · segmenti almeno uno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-016 | livelli soddisfatto · segmenti nessuno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-017 | livelli soddisfatto · segmenti nessuno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-018 | livelli soddisfatto · segmenti nessuno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-019 | livelli non soddisfatto · segmenti vuoto · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-020 | livelli non soddisfatto · segmenti vuoto · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-021 | livelli non soddisfatto · segmenti vuoto · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-022 | livelli non soddisfatto · segmenti almeno uno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-023 | livelli non soddisfatto · segmenti almeno uno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-024 | livelli non soddisfatto · segmenti almeno uno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-025 | livelli non soddisfatto · segmenti nessuno in comune · stati vuoto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-026 | livelli non soddisfatto · segmenti nessuno in comune · stati soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | **AMBIGUO** — fuori dal pubblico: dimensioni in AND (in OR sarebbe dentro) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-027 | livelli non soddisfatto · segmenti nessuno in comune · stati non soddisfatto (membro GOLD, [SEG-A, SEG-B], ACTIVE) | fuori dal pubblico (`NOT_IN_AUDIENCE`) | engagement §2 `audience`; docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-028 | membro sconosciuto (nessuno snapshot) · pubblico vuoto | nel pubblico | engagement §2 (vuoto = tutti); docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-029 | membro sconosciuto · livelli [GOLD] | fuori dal pubblico | engagement §2 (vuoto = tutti); docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-030 | membro sconosciuto · segmenti [SEG-A] | fuori dal pubblico | engagement §2 (vuoto = tutti); docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-031 | membro sconosciuto · stati [ACTIVE] | fuori dal pubblico | engagement §2 (vuoto = tutti); docs/03 §9 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-032 | pubblico `null` (JSON null) | nel pubblico (vuoto = tutti) | engagement §2 | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-033 | stati [ACTIVE] · membro con stato ACTIVE | nel pubblico | engagement §2; docs/03 §2 (stati del membro) | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-034 | stati [INACTIVE] · membro con stato INACTIVE | nel pubblico | engagement §2; docs/03 §2 (stati del membro) | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-035 | stati [BLOCKED] · membro con stato BLOCKED | nel pubblico | engagement §2; docs/03 §2 (stati del membro) | `TestbookEngContentSelectionTest` · `aud.csv` |
| TB-ENG-AUD-036 | stati [ANONYMIZED] · membro con stato ANONYMIZED | nel pubblico | engagement §2; docs/03 §2 (stati del membro) | `TestbookEngContentSelectionTest` · `aud.csv` |

### 3.4 Estensioni del pubblico (EXT)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-EXT-001 | `registeredWithinDays=7`, iscritto da 6 g 23:59:59 (pop-up) | nel pubblico | Q-71; docs/10 §7 (iscritti da < 7 giorni) | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-002 | `registeredWithinDays=7`, iscritto da 7 g esatti (pop-up) | fuori dal pubblico ("da < 7 giorni"; divergenza risolta, §17.1) | Q-71; docs/10 §7 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-003 | `registeredWithinDays=7`, iscritto da 7 g + 1 s (pop-up) | fuori dal pubblico | Q-71; docs/10 §7 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-004 | `registeredWithinDays=7`, iscritto adesso (pop-up) | nel pubblico | Q-71; docs/10 §7 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-005 | `registeredWithinDays=7`, data di iscrizione ignota (pop-up) | **AMBIGUO** — fuori dal pubblico (iscrizione non dimostrabile) | Q-71 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-006 | senza `registeredWithinDays`, iscritto da anni (pop-up) | nel pubblico | engagement §2 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-007 | `daysOfWeek=[SAT,SUN]`, venerdì 23:59:59 a Roma (pop-up) | fuori dal pubblico | Q-71; docs/10 §7 (solo sab–dom); docs/03 §3.3 (Europe/Rome) | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-008 | `daysOfWeek=[SAT,SUN]`, sabato 00:00:00 a Roma (venerdì in UTC) (pop-up) | nel pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-009 | `daysOfWeek=[SAT,SUN]`, domenica 23:59:59 a Roma (pop-up) | nel pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-010 | `daysOfWeek=[SAT,SUN]`, lunedì 00:00:00 a Roma (domenica in UTC) (pop-up) | fuori dal pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-011 | `daysOfWeek=[SAT,SUN]`, domenica del cambio d'ora di ottobre, 23:59:59 CET (pop-up) | nel pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-012 | `daysOfWeek=[SAT,SUN]`, lunedì 26/10 00:00 CET (pop-up) | fuori dal pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-013 | `daysOfWeek=[SAT,SUN]`, domenica del cambio d'ora di marzo, 23:59:59 CEST (pop-up) | nel pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-014 | `daysOfWeek=[SAT,SUN]`, lunedì 30/3 00:00 CEST (pop-up) | fuori dal pubblico | Q-71; docs/03 §3.3 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-015 | `daysOfWeek=[]`, mercoledì (pop-up) | nel pubblico (vuoto = ogni giorno) | Q-71 | `TestbookEngContentSelectionTest` · `ext.csv` |
| TB-ENG-EXT-016 | card (non pop-up) con `daysOfWeek=[SAT,SUN]` di mercoledì | **AMBIGUO** — fuori dal pubblico: Q-71 applica le chiavi ai pop-up, il codice a ogni contenuto | Q-71 | `TestbookEngContentSelectionTest` · `ext.csv` |

### 3.5 Ordine e limiti (ORD)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-ORD-001 | priorità 10, 90, 50 | ordine 90, 50, 10 | docs/03 §9 (priority desc) | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-002 | parità di priorità (50, 50), codici B e A | **AMBIGUO** — ordine per codice crescente (la spec non fissa lo spareggio) | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-003 | priorità ai limiti 0 e 1000 | 1000 prima, 0 ultima | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-004 | `HOME_HERO` con 0 idonei | nessun contenuto | docs/03 §9 (HOME_HERO 1) | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-005 | `HOME_HERO` con 1 idoneo | 1 contenuto | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-006 | `HOME_HERO` con 2 idonei | 1 contenuto, il più prioritario | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-007 | `HOME_GRID` con 5 idonei (max−1) | 5 contenuti | docs/03 §9 (HOME_GRID fino a 6) | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-008 | `HOME_GRID` con 6 idonei (max) | 6 contenuti | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-009 | `HOME_GRID` con 7 idonei (max+1) | 6 contenuti, escluso il meno prioritario | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-010 | `CATALOG_TOP` con 2 idonei | 1 contenuto | Q-72 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-011 | `CONTEST` con 3 idonei | 3 contenuti | Q-72 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-012 | `CONTEST` con 4 idonei | 3 contenuti | Q-72 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-013 | `WIN` con 2 idonei | 1 contenuto | Q-72 | `TestbookEngContentSelectionTest` · `ord.csv` |
| TB-ENG-ORD-014 | `HOME_HERO`: il più prioritario è `DRAFT` | si vede il `LIVE` meno prioritario (gli esclusi non occupano posti) | docs/03 §9 | `TestbookEngContentSelectionTest` · `ord.csv` |

## 4. Pop-up (R06, R07)

**Regola.** «Pop-up: al più uno per visita; candidato = primo per priorità che rispetta la frequenza (`ONCE`: mai visto; `ONCE_PER_DAY`: non visto oggi)» (docs/03 §9); `ALWAYS` e chiudibile (F-CNT-02, engagement §2). «`popups/next`: primo per priorità che passa pubblico, calendario e frequenza; la registrazione della vista avviene con `seen` (non alla lettura)» (engagement §5); `204` se nessuno (engagement §3). Accettazione: «Pop-up `ONCE` visto e chiuso → non ricompare; `ONCE_PER_DAY` ricompare il giorno dopo (test con orologio iniettato)» (engagement §7). La tabella `popup_view` ha il giorno `view_date` in Europe/Rome.

| Ingresso | Classi | Limiti |
|---|---|---|
| frequenza | `ONCE`, `ONCE_PER_DAY`, `ALWAYS` | valore sconosciuto (rifiutato alla creazione, EDT-022) |
| ultima vista | mai, oggi, ieri, 30 giorni fa | 23:59:59 / 00:00:00 di Roma, cambio d'ora di marzo e ottobre, 29 febbraio, fine mese, giorno UTC ≠ giorno di Roma |
| chiudibile | sì, no | — |
| `seen` | chiuso, non chiuso, ripetuto nello stesso giorno | `memberId` assente, id di una card, id inesistente |

**Strategia.** Frequenza: tabella **completa** frequenza (3) × ultima vista (4) × chiudibile (2) = 24; poi ogni istante limite da solo (10 righe); interazioni di priorità (4 righe). API: una riga per ramo (lettura non consumante, `204`, errori) e la mezzanotte di Roma con l'orologio iniettato.

### 4.1 Frequenza (POP)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-POP-001 | `ONCE` · mai visto · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-002 | `ONCE` · mai visto · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-003 | `ONCE` · visto oggi · chiudibile=true | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-004 | `ONCE` · visto oggi · chiudibile=false | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-005 | `ONCE` · visto ieri · chiudibile=true | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-006 | `ONCE` · visto ieri · chiudibile=false | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-007 | `ONCE` · visto 30 giorni fa · chiudibile=true | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-008 | `ONCE` · visto 30 giorni fa · chiudibile=false | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-009 | `ONCE_PER_DAY` · mai visto · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-010 | `ONCE_PER_DAY` · mai visto · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-011 | `ONCE_PER_DAY` · visto oggi · chiudibile=true | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-012 | `ONCE_PER_DAY` · visto oggi · chiudibile=false | escluso (`FREQUENCY`) | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-013 | `ONCE_PER_DAY` · visto ieri · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-014 | `ONCE_PER_DAY` · visto ieri · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-015 | `ONCE_PER_DAY` · visto 30 giorni fa · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-016 | `ONCE_PER_DAY` · visto 30 giorni fa · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-017 | `ALWAYS` · mai visto · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-018 | `ALWAYS` · mai visto · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-019 | `ALWAYS` · visto oggi · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-020 | `ALWAYS` · visto oggi · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-021 | `ALWAYS` · visto ieri · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-022 | `ALWAYS` · visto ieri · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-023 | `ALWAYS` · visto 30 giorni fa · chiudibile=true | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-024 | `ALWAYS` · visto 30 giorni fa · chiudibile=false | mostrato | docs/03 §9; F-CNT-02; engagement §7 | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-025 | `ONCE_PER_DAY` · visto il 24/9, ora 23:59:59 del 24/9 a Roma | escluso (`FREQUENCY`) | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-026 | `ONCE_PER_DAY` · visto il 24/9, ora 00:00:00 del 25/9 a Roma (ancora 24/9 in UTC) | mostrato | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-027 | `ONCE_PER_DAY` · visto il 29/3 (cambio d'ora), ora 23:59:59 CEST | escluso (`FREQUENCY`) | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-028 | `ONCE_PER_DAY` · visto il 29/3, ora 00:00 del 30/3 CEST | mostrato | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-029 | `ONCE_PER_DAY` · visto il 25/10 (cambio d'ora), ora 23:59:59 CET | escluso (`FREQUENCY`) | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-030 | `ONCE_PER_DAY` · visto il 25/10, ora 00:00 del 26/10 CET | mostrato | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-031 | `ONCE_PER_DAY` · visto il 29/2/2028, ora 23:59:59 del 29/2 | escluso (`FREQUENCY`) | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-032 | `ONCE_PER_DAY` · visto il 29/2/2028, ora 00:00 dell'1/3 | mostrato | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-033 | `ONCE_PER_DAY` · visto il 30/9 (fine mese), ora 00:00 dell'1/10 a Roma | mostrato | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-034 | `ONCE_PER_DAY` · vista registrata il 25/9 di Roma (alle 00:30, cioè 24/9 in UTC), ora 25/9 12:00 | escluso (`FREQUENCY`) | docs/03 §9 (non visto oggi); engagement §5; Europe/Rome | `TestbookEngContentSelectionTest` · `pop.csv` |
| TB-ENG-POP-035 | due pop-up idonei, priorità 90 e 80 | al più uno: solo quello a 90 | docs/03 §9; F-CNT-02 | `TestbookEngContentSelectionTest#atMostOnePopup` |
| TB-ENG-POP-036 | pop-up a 90 già visto (`ONCE`) e pop-up a 80 mai visto | il pop-up a 80; quello a 90 escluso per `FREQUENCY` | docs/03 §9 (primo per priorità che rispetta la frequenza) | `TestbookEngContentSelectionTest#frequencySkipsToNext` |
| TB-ENG-POP-037 | nessun pop-up idoneo | nessun pop-up | docs/03 §9; engagement §3 (`204`) | `TestbookEngContentSelectionTest#noPopup` |
| TB-ENG-POP-038 | pop-up `DRAFT` già visto | escluso con motivo `NOT_LIVE` o `FREQUENCY` | docs/03 §9; engagement §3 preview | `TestbookEngContentSelectionTest#draftAndSeen` |

### 4.2 API del portale (POPA)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-POPA-001 | nessun pop-up idoneo | `204` | engagement §3 | `TestbookEngContentIT`#noPopupIs204 |
| TB-ENG-POPA-002 | `popups/next` senza `memberId` | `400` | docs/06 §2 | `TestbookEngContentIT`#nextWithoutMember |
| TB-ENG-POPA-003 | lettura ripetuta senza `seen` | stesso pop-up (la lettura non consuma) | engagement §5 | `TestbookEngContentIT`#readDoesNotConsume |
| TB-ENG-POPA-004 | `ONCE` visto e chiuso (`dismissed=true`) | non ricompare (`204`) | engagement §7 (accettazione) | `TestbookEngContentIT`#onceSeenAndClosed |
| TB-ENG-POPA-005 | `ONCE` visto senza chiuderlo (`dismissed=false`) | non ricompare | docs/03 §9 (ONCE: mai visto) | `TestbookEngContentIT`#onceSeenNotClosed |
| TB-ENG-POPA-006 | `ONCE_PER_DAY` visto alle 23:59:5x di Roma | escluso fino a mezzanotte di Roma, ricompare alle 00:00:0x | engagement §7 (orologio iniettato); docs/03 §9 | `TestbookEngContentIT`#dailyPopupRomeMidnight |
| TB-ENG-POPA-007 | `ALWAYS` visto | ricompare subito | engagement §2; F-CNT-02 | `TestbookEngContentIT`#alwaysPopupAgain |
| TB-ENG-POPA-008 | `seen` due volte nello stesso giorno (prima aperto, poi chiuso) | una sola vista del giorno, chiusura registrata | engagement §2 (`popup_view` PK con giorno) | `TestbookEngContentIT`#seenTwiceSameDay |
| TB-ENG-POPA-009 | `seen` su una card | `404` | engagement §3 | `TestbookEngContentIT`#seenOnCard |
| TB-ENG-POPA-010 | `seen` senza `memberId` | `400` | docs/06 §2 | `TestbookEngContentIT`#seenWithoutMember |
| TB-ENG-POPA-011 | `seen` su un id inesistente | `404` | docs/06 §2 | `TestbookEngContentIT`#seenUnknown |
| TB-ENG-POPA-012 | due pop-up idonei (priorità 900 e 800) | `next` restituisce quello a 900 | docs/03 §9; engagement §5 | `TestbookEngContentIT`#nextByPriority |

## 5. Anteprima e portale (R05, R08, R17)

**Regola.** «`GET /v1/contents/preview?memberId=&placement=`: ciò che vedrebbe quel membro adesso, con il motivo di esclusione degli altri (`NOT_IN_AUDIENCE, OUT_OF_SCHEDULE, NOT_LIVE, FREQUENCY`)» (engagement §3; F-CNT-04; BO-18). «`GET /v1/portal/content?memberId=&placement=` elenco ordinato; per `WIN` aggiungere `&prizeCode=`» (engagement §3; F-CNT-03). «portale (`/v1/portal/**` — sempre con `memberId` esplicito)» (docs/06 §2); `400` per parametri errati (docs/06 §2). Accettazione: «Contenuto con `audience.tiers=[GOLD,PLATINUM]` → assente per Anna, presente per Davide; `preview` ne spiega il motivo» (engagement §7).

| Ingresso | Classi valide | Classi non valide |
|---|---|---|
| `memberId` | membro con snapshot, membro sconosciuto | assente |
| `placement` | i 5 posizionamenti, `POPUP` (anteprima) | sconosciuto |
| `prizeCode` (`WIN`) | premio con card | — |

**Strategia.** Un motivo di esclusione per riga (4), ogni parametro non valido da solo, l'accettazione di §7 con membri propri.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-PRV-001 | pubblico `tiers=[GOLD,PLATINUM]` (scritto `gold`): membro BASE e membro GOLD | assente per il BASE, presente per il GOLD; l'anteprima del BASE dice `NOT_IN_AUDIENCE` | engagement §7 (accettazione); F-CNT-04 | `TestbookEngContentIT`#tierAudienceAcceptance |
| TB-ENG-PRV-002 | anteprima di una bozza | esclusa con `NOT_LIVE` | engagement §3 preview; BO-18 | `TestbookEngContentIT`#previewNotLive |
| TB-ENG-PRV-003 | anteprima di un `LIVE` con inizio futuro | escluso con `OUT_OF_SCHEDULE` | engagement §3 preview | `TestbookEngContentIT`#previewOutOfSchedule |
| TB-ENG-PRV-004 | anteprima dei pop-up dopo la vista di un `ONCE` | escluso con `FREQUENCY` | engagement §3 preview; BO-18 | `TestbookEngContentIT`#previewFrequency |
| TB-ENG-PRV-005 | anteprima senza `memberId` | `400` | engagement §3; docs/06 §2 | `TestbookEngContentIT`#previewWithoutMember |
| TB-ENG-PRV-006 | anteprima con posizionamento sconosciuto | `400` | docs/06 §2 (parametri errati) | `TestbookEngContentIT`#previewBadPlacement |
| TB-ENG-PRV-007 | portale `WIN` con `prizeCode` | solo la card del premio vinto | engagement §3; F-CNT-03 | `TestbookEngContentIT`#winCardByPrize |
| TB-ENG-PRV-008 | portale con posizionamento sconosciuto | `400` | docs/06 §2 | `TestbookEngContentIT`#portalBadPlacement |
| TB-ENG-PRV-009 | portale senza `memberId` | `400` (portale sempre con memberId esplicito; divergenza risolta, §17.1) | docs/06 §2 | `TestbookEngContentIT`#portalWithoutMember |
| TB-ENG-PRV-010 | membro sconosciuto (nessuno snapshot) | vede i contenuti per tutti, non quelli con pubblico | engagement §2 (vuoto = tutti) | `TestbookEngContentIT`#unknownMember |
| TB-ENG-PRV-011 | risposta del portale | **AMBIGUO** — senza pubblico, stato, versione | engagement §3 | `TestbookEngContentIT`#portalDisplayOnly |
| TB-ENG-PRV-012 | anteprima di `WIN` con due card vincita idonee | **AMBIGUO** — entrambe mostrate (una per premio), mentre il portale ne mostra una | engagement §3; BO-18; Q-72 | `TestbookEngContentIT`#winPreviewShowsAll |

## 6. Ciclo di vita e fine automatica (R09, R10, R13)

**Regola.** Macchina a stati di docs/03 §3.6 («vale per campagne, premi, concorsi, contenuti») con la policy `CONTENT` «mai (pubblicazione diretta)» (docs/06 §7) e gli stati dei contenuti `DRAFT, LIVE, PAUSED, ENDED, ARCHIVED` (engagement §2, nessun `IN_REVIEW`/`APPROVED`); «`POST /v1/contents/{id}/transitions` — `CONTENT` non richiede approvazione (`DRAFT → LIVE` diretto)» (engagement §3); transizione non valida → `409` (docs/06 §2). Ogni transizione produce `content.status.changed` (engagement §4) e scrive audit (docs/03 §3.6). «`ENDED` automatico dei contenuti con `end_at` passato (job ogni 10 min)» (engagement §5).

| Ingresso | Classi |
|---|---|
| stato di partenza | `DRAFT`, `LIVE`, `PAUSED`, `ENDED`, `ARCHIVED` |
| azione | `PUBLISH`, `PAUSE`, `RESUME`, `END`, `ARCHIVE`; vietate per i contenuti `SUBMIT`, `APPROVE`, `REJECT`; sconosciuta `BOH`; assente |
| `endAt` al job | passato, uguale all'istante, futuro, assente; stato `LIVE`, `PAUSED`, `DRAFT` |

**Strategia.** Macchina a stati **completa**: 5 stati × 10 azioni = 50 righe (transizioni ammesse e vietate col codice `INVALID_TRANSITION`); fatto prodotto e non prodotto; fine automatica per ogni classe di `endAt` e di stato.

### 6.1 Transizioni (LIF)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-LIF-001 | da `DRAFT` · `PUBLISH` | `200`, stato `LIVE` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-002 | da `DRAFT` · `PAUSE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-003 | da `DRAFT` · `RESUME` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-004 | da `DRAFT` · `END` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-005 | da `DRAFT` · `ARCHIVE` | `200`, stato `ARCHIVED` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-006 | da `DRAFT` · `SUBMIT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-007 | da `DRAFT` · `APPROVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-008 | da `DRAFT` · `REJECT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-009 | da `DRAFT` · azione sconosciuta `BOH` | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-010 | da `DRAFT` · azione mancante | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-011 | da `LIVE` · `PUBLISH` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-012 | da `LIVE` · `PAUSE` | `200`, stato `PAUSED` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-013 | da `LIVE` · `RESUME` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-014 | da `LIVE` · `END` | `200`, stato `ENDED` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-015 | da `LIVE` · `ARCHIVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-016 | da `LIVE` · `SUBMIT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-017 | da `LIVE` · `APPROVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-018 | da `LIVE` · `REJECT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-019 | da `LIVE` · azione sconosciuta `BOH` | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-020 | da `LIVE` · azione mancante | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-021 | da `PAUSED` · `PUBLISH` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-022 | da `PAUSED` · `PAUSE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-023 | da `PAUSED` · `RESUME` | `200`, stato `LIVE` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-024 | da `PAUSED` · `END` | `200`, stato `ENDED` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-025 | da `PAUSED` · `ARCHIVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-026 | da `PAUSED` · `SUBMIT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-027 | da `PAUSED` · `APPROVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-028 | da `PAUSED` · `REJECT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-029 | da `PAUSED` · azione sconosciuta `BOH` | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-030 | da `PAUSED` · azione mancante | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-031 | da `ENDED` · `PUBLISH` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-032 | da `ENDED` · `PAUSE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-033 | da `ENDED` · `RESUME` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-034 | da `ENDED` · `END` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-035 | da `ENDED` · `ARCHIVE` | `200`, stato `ARCHIVED` | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-036 | da `ENDED` · `SUBMIT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-037 | da `ENDED` · `APPROVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-038 | da `ENDED` · `REJECT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-039 | da `ENDED` · azione sconosciuta `BOH` | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-040 | da `ENDED` · azione mancante | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-041 | da `ARCHIVED` · `PUBLISH` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-042 | da `ARCHIVED` · `PAUSE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-043 | da `ARCHIVED` · `RESUME` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-044 | da `ARCHIVED` · `END` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-045 | da `ARCHIVED` · `ARCHIVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-046 | da `ARCHIVED` · `SUBMIT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-047 | da `ARCHIVED` · `APPROVE` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-048 | da `ARCHIVED` · `REJECT` | `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; docs/06 §7 (CONTENT: mai approvazione); engagement §2 (nessuno stato IN_REVIEW) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-049 | da `ARCHIVED` · azione sconosciuta `BOH` | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-050 | da `ARCHIVED` · azione mancante | **AMBIGUO** — `409 INVALID_TRANSITION`, stato invariato | docs/03 §3.6; engagement §3; docs/06 §2 (409 transizione non valida) | `TestbookEngContentIT` · `lif.csv` |
| TB-ENG-LIF-051 | transizione valida (`DRAFT → LIVE`) | fatto `content.status.changed` in outbox con `previousStatus`, `newStatus`, `contentCode`, attore | engagement §4; docs/05; docs/06 §7 | `TestbookEngContentIT`#statusFactOnTransition |
| TB-ENG-LIF-052 | transizione rifiutata | nessun fatto `content.status.changed` | engagement §4; docs/06 §7 | `TestbookEngContentIT`#noFactOnRefusedTransition |

### 6.2 Fine automatica (END)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-END-001 | `LIVE` con `endAt` passato | `ENDED` e fatto `content.status.changed` con attore `system` | engagement §5 (ENDED automatico); docs/03 §3.6 | `TestbookEngContentIT`#autoEndLive |
| TB-ENG-END-002 | `PAUSED` con `endAt` passato | `ENDED` | engagement §5; docs/03 §3.6 (PAUSED → ENDED) | `TestbookEngContentIT`#autoEndPaused |
| TB-ENG-END-003 | `DRAFT` con `endAt` passato | resta `DRAFT` (DRAFT → ENDED non è una transizione) | docs/03 §3.6 | `TestbookEngContentIT`#autoEndDraft |
| TB-ENG-END-004 | `LIVE` con `endAt` futuro | resta `LIVE` | engagement §5 | `TestbookEngContentIT`#autoEndFuture |
| TB-ENG-END-005 | `LIVE` con `endAt` uguale all'istante del job | **AMBIGUO** — `ENDED` | engagement §5 (end_at passato) | `TestbookEngContentIT`#autoEndAtInstant |
| TB-ENG-END-006 | `LIVE` senza `endAt` | resta `LIVE` | engagement §5 | `TestbookEngContentIT`#autoEndNoEnd |

## 7. Creazione, modifica, duplicazione (R11, R12, R14, R15)

**Regola.** Campi e enumerati di `content_item` (engagement §2): `kind` (`CARD, POPUP, BANNER`), `placement` (`HOME_HERO, HOME_GRID, CATALOG_TOP, CONTEST, WIN`), `link_type` (`NONE, CONTEST, CAMPAIGN, REWARD, PRIZE`), `frequency` (`ONCE, ONCE_PER_DAY, ALWAYS`; solo pop-up), `style.tone` (`PRIMARY/SECONDARY/COIN/NIGHT`), `cta_target` (path del portale o URL). Codice `^[A-Z][A-Z0-9-]{2,39}$`, `409` per codice duplicato e per modifica non ammessa su oggetto `LIVE` (docs/06 §2), `409` di versione (docs/06 §4). «Un oggetto `LIVE` si modifica solo nei campi "sicuri" (nome, descrizione, `endAt`, priorità, immagine); per il resto va duplicato» (docs/03 §3.6; docs/08 §3.2). Duplicazione: `POST /v1/contents/{id}/duplicate` (engagement §3).

| Ingresso | Classi valide | Non valide / limiti |
|---|---|---|
| `code` | 3 e 40 caratteri | 2, 41 caratteri, prima cifra, assente, già usato; minuscolo |
| `kind` | i 3 valori | sconosciuto, assente |
| `placement` | i 5 valori; assente per i pop-up | sconosciuto, assente per una card |
| `linkType` / `linkCode` | i 5 valori | sconosciuto; codice mancante; `PRIZE` fuori da `WIN`, `WIN` senza premio |
| `frequency` / `dismissible` | i 3 valori; vero, falso | sconosciuta; assenti |
| `style.tone` | i 4 valori | sconosciuto |
| `title` / `body` | 80 / 280 caratteri | assente, spazi, 81 / 281 |
| `priority` | 0, 1000 | −1, 1001, assente |
| calendario | fine dopo l'inizio | fine = inizio, fine < inizio |
| `ctaTarget` | `/portal…`, `https://…` | `javascript:`, `http://` |
| modifica di un `LIVE` | titolo, testo, `endAt`, priorità, immagine | posizionamento, pubblico, `startAt`, collegamento, frequenza |

**Strategia.** Il prodotto dei domini supera 64: **guasto singolo** su una card valida minima (ogni classe non valida da sola), ogni valore degli enumerati da solo, ogni limite da solo; modifiche di un `LIVE` un campo alla volta (5 sicuri, 5 non sicuri). Riduzione: da ~10⁶ combinazioni a 59 + 10 righe.

### 7.1 Creazione e modifica (EDT)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-EDT-001 | creazione: card valida minima | `201`, stato `DRAFT`, versione 0 | docs/03 §3.6 ([*] → DRAFT); engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-002 | creazione: `kind` assente | `422`, campo `kind` | engagement §2 (kind CARD, POPUP, BANNER) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-003 | creazione: `kind` sconosciuto `VIDEO` | `422`, campo `kind` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-004 | creazione: card in `HOME_HERO` | `201` | engagement §2; F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-005 | creazione: card in `CONTEST` | `201` | engagement §2; F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-006 | creazione: card in `CATALOG_TOP` | `201` | engagement §2; F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-007 | creazione: card in `WIN` collegata al premio | `201` | engagement §2; F-CNT-03 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-008 | creazione: `placement` sconosciuto | `422`, campo `placement` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-009 | creazione: card senza `placement` | `422`, campo `placement` | F-CNT-01 (posizionamento) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-010 | creazione: pop-up senza `placement` | `201`, frequenza `ONCE` | engagement §2; docs/10 §7 (pop-up senza posizione) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-011 | creazione: banner in `CATALOG_TOP` | `201` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-012 | creazione: banner in `HOME_GRID` | **AMBIGUO** — `422`, campo `placement` (solo CATALOG_TOP) | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-013 | creazione: collegamento `CONTEST` | `201` | engagement §2 (link_type) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-014 | creazione: collegamento `CAMPAIGN` | `201` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-015 | creazione: collegamento `REWARD` | `201` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-016 | creazione: collegamento sconosciuto `PAGE` | `422`, campo `linkType` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-017 | creazione: collegamento `CAMPAIGN` senza `linkCode` | **AMBIGUO** — `422`, campo `linkCode` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-018 | creazione: `PRIZE` fuori da `WIN` | **AMBIGUO** — `422`, campo `linkType` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-019 | creazione: card `WIN` senza premio | **AMBIGUO** — `422`, campo `linkType` | F-CNT-03 (per premio) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-020 | creazione: pop-up `ONCE_PER_DAY` | `201` | engagement §2; F-CNT-02 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-021 | creazione: pop-up `ALWAYS` | `201` | engagement §2; F-CNT-02 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-022 | creazione: frequenza sconosciuta `SEMPRE` | `422`, campo `frequency` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-023 | creazione: card con `frequency` | `201`, frequenza ignorata (`null`) | engagement §2 (frequency solo pop-up) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-024 | creazione: pop-up non chiudibile | `201`, `dismissible=false` | engagement §2; F-CNT-02 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-025 | creazione: pop-up senza `dismissible` | **AMBIGUO** — `201`, chiudibile per difetto | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-026 | creazione: tono `NIGHT` | `201` | engagement §2 (style.tone) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-027 | creazione: tono sconosciuto `RED` | `422`, campo `style.tone` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-028 | creazione: codice di 3 caratteri (minimo) | `201` | docs/06 §2 (^[A-Z][A-Z0-9-]{2,39}$) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-029 | creazione: codice di 2 caratteri | `422`, campo `code` | docs/06 §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-030 | creazione: codice di 40 caratteri (massimo) | `201` | docs/06 §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-031 | creazione: codice di 41 caratteri | `422`, campo `code` | docs/06 §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-032 | creazione: codice che inizia con una cifra | `422`, campo `code` | docs/06 §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-033 | creazione: codice in minuscolo | **AMBIGUO** — `201`, codice portato in maiuscolo | docs/06 §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-034 | creazione: codice assente | `422`, campo `code` | docs/06 §2 (code obbligatorio) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-035 | creazione: titolo assente | `422`, campo `title` | F-CNT-01 (titolo) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-036 | creazione: titolo di soli spazi | `422`, campo `title` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-037 | creazione: titolo di 80 caratteri | **AMBIGUO** — `201` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-038 | creazione: titolo di 81 caratteri | **AMBIGUO** — `422`, campo `title` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-039 | creazione: testo di 280 caratteri | **AMBIGUO** — `201` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-040 | creazione: testo di 281 caratteri | **AMBIGUO** — `422`, campo `body` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-041 | creazione: priorità 0 | **AMBIGUO** — `201` | docs/03 §9 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-042 | creazione: priorità 1000 | **AMBIGUO** — `201` | docs/03 §9 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-043 | creazione: priorità −1 | **AMBIGUO** — `422`, campo `priority` | docs/03 §9 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-044 | creazione: priorità 1001 | **AMBIGUO** — `422`, campo `priority` | docs/03 §9 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-045 | creazione: priorità assente | **AMBIGUO** — `201`, priorità 50 | docs/03 §9 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-046 | creazione: calendario con fine dopo l'inizio | `201` | F-CNT-01 (calendario) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-047 | creazione: fine uguale all'inizio | **AMBIGUO** — `422`, campo `endAt` | F-CNT-01 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-048 | creazione: fine prima dell'inizio | `422`, campo `endAt` | F-CNT-01 (calendario) | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-049 | creazione: CTA verso pagina del portale | `201` | engagement §2 (cta_target path del portale o URL); BO-18 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-050 | creazione: CTA verso URL `https://` | `201` | engagement §2; BO-18 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-051 | creazione: CTA `javascript:` | `422`, campo `ctaTarget` | engagement §2; BO-18 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-052 | creazione: CTA `http://` | **AMBIGUO** — `422`, campo `ctaTarget` | engagement §2 | `TestbookEngContentIT` · `edt.csv` |
| TB-ENG-EDT-053 | creazione con codice già usato | `409 CODE_TAKEN` | docs/06 §2 (409 codice duplicato) | `TestbookEngContentIT`#duplicateCode |
| TB-ENG-EDT-054 | modifica con versione superata | `409 VERSION_CONFLICT` | docs/06 §4 (lock ottimistico, 409) | `TestbookEngContentIT`#versionConflict |
| TB-ENG-EDT-055 | modifica che cambia il codice | **AMBIGUO** — `409 CODE_IMMUTABLE` | docs/06 §2 | `TestbookEngContentIT`#codeImmutable |
| TB-ENG-EDT-056 | modifica che cambia il tipo (`CARD → POPUP`) di una bozza | **AMBIGUO** — `409 KIND_IMMUTABLE` | docs/03 §3.6 | `TestbookEngContentIT`#kindImmutable |
| TB-ENG-EDT-057 | modifica di un contenuto `ARCHIVED` | **AMBIGUO** — `409 CONTENT_NOT_EDITABLE` | docs/03 §3.6 | `TestbookEngContentIT`#archivedNotEditable |
| TB-ENG-EDT-058 | modifica di un contenuto inesistente | `404` | docs/06 §2 | `TestbookEngContentIT`#updateNotFound |
| TB-ENG-EDT-059 | modifica di una bozza (`DRAFT`): cambia posizionamento e pubblico | `200`, campi aggiornati, versione +1 | docs/03 §3.6 (vincoli solo su LIVE) | `TestbookEngContentIT`#draftFullyEditable |
| TB-ENG-EDT-060 | modifica di un contenuto `ENDED` | `409 CONTENT_NOT_EDITABLE` (Q-174 DECISA, come docs/17 US-E07-01) | docs/03 §3.6; docs/17 US-E07-01; Q-174 | `TestbookEngContentIT`#endedNotEditable |
| TB-ENG-EDT-061 | modifica di un contenuto `PAUSED`: posizionamento, poi solo titolo | `409 CONTENT_LIVE_LOCKED`, poi `200` (Q-174 DECISA: stessi campi sicuri del `LIVE`, come Q-51) | docs/03 §3.6; Q-51; Q-174 | `TestbookEngContentIT`#pausedLockedLikeLive |

### 7.2 Modifica di un contenuto LIVE (EDL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-EDL-001 | contenuto `LIVE`: modifica di titolo (campo sicuro) | `200` | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-002 | contenuto `LIVE`: modifica di testo/descrizione (campo sicuro) | `200` | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-003 | contenuto `LIVE`: modifica di `endAt` (campo sicuro) | `200` | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-004 | contenuto `LIVE`: modifica di priorità (campo sicuro) | `200` | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-005 | contenuto `LIVE`: modifica di immagine (campo sicuro) | `200` | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-006 | contenuto `LIVE`: modifica di posizionamento (non sicuro) | `409 CONTENT_LIVE_LOCKED` (duplicare; divergenza risolta, §17.1) | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-007 | contenuto `LIVE`: modifica di pubblico (non sicuro) | `409 CONTENT_LIVE_LOCKED` (duplicare; divergenza risolta, §17.1) | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-008 | contenuto `LIVE`: modifica di `startAt` (non sicuro) | `409 CONTENT_LIVE_LOCKED` (duplicare; divergenza risolta, §17.1) | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-009 | contenuto `LIVE`: modifica di collegamento (non sicuro) | `409 CONTENT_LIVE_LOCKED` (duplicare; divergenza risolta, §17.1) | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |
| TB-ENG-EDL-010 | contenuto `LIVE`: modifica di frequenza di un pop-up (non sicuro) | `409 CONTENT_LIVE_LOCKED` (duplicare; divergenza risolta, §17.1) | docs/03 §3.6 (LIVE: solo campi sicuri); docs/06 §2 (409); docs/08 §3.2 | `TestbookEngContentIT` · `edl.csv` |

### 7.3 Duplicazione (DUP)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-DUP-001 | duplica una bozza | `201`, nuovo id e codice, stato `DRAFT`, versione 0, stessi campi | docs/03 §3.6; engagement §3 | `TestbookEngContentIT`#duplicateDraft |
| TB-ENG-DUP-002 | codice della copia e della seconda copia | **AMBIGUO** — `<CODICE>-COPIA`, poi `<CODICE>-COPIA-2`; titolo "(copia)" | engagement §3 | `TestbookEngContentIT`#duplicateNaming |
| TB-ENG-DUP-003 | duplica un contenuto `LIVE` | copia in `DRAFT`, originale ancora `LIVE` | docs/03 §3.6 (per il resto va duplicato) | `TestbookEngContentIT`#duplicateLive |
| TB-ENG-DUP-004 | duplica un contenuto `ARCHIVED` | `201`, copia in `DRAFT` | docs/03 §3.6 | `TestbookEngContentIT`#duplicateArchived |
| TB-ENG-DUP-005 | duplica un pop-up | frequenza e chiudibilità conservate | engagement §2 | `TestbookEngContentIT`#duplicatePopup |
| TB-ENG-DUP-006 | duplica un contenuto con codice di 40 caratteri | **AMBIGUO** — codice della copia valido (≤ 40 caratteri) | docs/06 §2 | `TestbookEngContentIT`#duplicateLongCode |
| TB-ENG-DUP-007 | duplica un contenuto inesistente | `404` | docs/06 §2 | `TestbookEngContentIT`#duplicateNotFound |

## 8. Ruoli e audit (R16, R44)

**Regola.** Matrice di docs/08 §2: `content.write` — «contenuti, template, regole di notifica, tema» — ADMIN e MARKETING; «il backend rifiuta inoltre ogni scrittura di `ANALYST`»; header assente ⇒ `ANALYST:anonymous` (docs/06 §3); `403 forbidden-role`. Gli endpoint del portale non richiedono header (docs/06 §3). Audit su `lh.audit.v1` delle «scritture su contenuti, template, regole, tema, webhook» (engagement §4), con l'attore (docs/06 §3). La capacità `content.write` non è marcata ● («il backend rifiuta con 403 dove c'è ●»): il rifiuto di LEGAL e CARE lato servizio non è prescritto (**AMBIGUO**).

**Strategia.** Tabella **completa** 4 endpoint di scrittura × 7 classi di attore = 28 righe, più transizioni e duplicazione con `ANALYST` e le letture del portale senza intestazione; audit: una riga per tipo di scrittura più la scrittura rifiutata.

### 8.1 Ruoli (ROL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-ROL-001 | `POST /v1/contents` con ADMIN | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-002 | `POST /v1/contents` con MARKETING | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-003 | `POST /v1/contents` con LEGAL | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-004 | `POST /v1/contents` con CARE | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-005 | `POST /v1/contents` con ANALYST | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-006 | `POST /v1/contents` con intestazione assente | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-007 | `POST /v1/contents` con intestazione non valida `PIPPO:x` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-008 | `POST /v1/message-templates` con ADMIN | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-009 | `POST /v1/message-templates` con MARKETING | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-010 | `POST /v1/message-templates` con LEGAL | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-011 | `POST /v1/message-templates` con CARE | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-012 | `POST /v1/message-templates` con ANALYST | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-013 | `POST /v1/message-templates` con intestazione assente | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-014 | `POST /v1/message-templates` con intestazione non valida `PIPPO:x` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-015 | `POST /v1/notification-rules` con ADMIN | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-016 | `POST /v1/notification-rules` con MARKETING | `201` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-017 | `POST /v1/notification-rules` con LEGAL | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-018 | `POST /v1/notification-rules` con CARE | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-019 | `POST /v1/notification-rules` con ANALYST | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-020 | `POST /v1/notification-rules` con intestazione assente | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-021 | `POST /v1/notification-rules` con intestazione non valida `PIPPO:x` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-022 | `PUT /v1/theme` con ADMIN | `200` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-023 | `PUT /v1/theme` con MARKETING | `200` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-024 | `PUT /v1/theme` con LEGAL | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-025 | `PUT /v1/theme` con CARE | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-026 | `PUT /v1/theme` con ANALYST | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-027 | `PUT /v1/theme` con intestazione assente | `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-028 | `PUT /v1/theme` con intestazione non valida `PIPPO:x` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`content.write`: ADMIN, MARKETING); docs/06 §3 | `TestbookEngContentIT` · `rol.csv` |
| TB-ENG-ROL-029 | letture del portale senza intestazione (`/v1/portal/theme`, `/v1/portal/content`) | `200` | docs/06 §3 (portale senza header) | `TestbookEngContentIT`#portalWithoutHeader |
| TB-ENG-ROL-030 | `POST /v1/contents/{id}/transitions` con `ANALYST` | `403 FORBIDDEN_ROLE` | docs/06 §3; docs/08 §2 | `TestbookEngContentIT`#transitionAnalyst |
| TB-ENG-ROL-031 | `POST /v1/contents/{id}/duplicate` con `ANALYST` | `403 FORBIDDEN_ROLE` | docs/06 §3; docs/08 §2 | `TestbookEngContentIT`#duplicateAnalyst |

### 8.2 Audit (AUDT)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-AUDT-001 | creazione di un contenuto | voce di audit `CREATE` su `CONTENT:<codice>` con l'attore | engagement §4 (lh.audit.v1); docs/06 §3 | `TestbookEngContentIT`#auditContentCreate |
| TB-ENG-AUDT-002 | transizione di un contenuto | voce di audit `TRANSITION` con stato prima/dopo | engagement §4; docs/03 §3.6 (ogni transizione scrive audit) | `TestbookEngContentIT`#auditContentTransition |
| TB-ENG-AUDT-003 | creazione rifiutata (`422`) | nessuna voce di audit | engagement §4 (audit delle scritture) | `TestbookEngContentIT`#auditNotOnRefusal |
| TB-ENG-AUDT-004 | modifica del tema | voce di audit `UPDATE` su `THEME:default` | engagement §4 | `TestbookEngContentIT`#auditTheme |
| TB-ENG-AUDT-005 | creazione di un template | voce di audit `CREATE` su `MESSAGE_TEMPLATE:<codice>` | engagement §4 | `TestbookEngMessagingIT`#auditTemplate |
| TB-ENG-AUDT-006 | creazione di una regola | voce di audit `CREATE` su `NOTIFICATION_RULE:<codice>` | engagement §4 | `TestbookEngMessagingIT`#auditRule |
| TB-ENG-AUDT-007 | creazione di un webhook | voce di audit `CREATE` su `WEBHOOK:<codice>` senza il segreto | engagement §4; BO-23 (segreto mostrato una volta) | `TestbookEngWebhookIT`#auditWebhook |

## 9. Template (R18–R21, R30)

**Regola.** «Motore template: sostituzione `{{percorso}}` su contesto `{data, member, event}`; percorso assente → stringa vuota + log `WARN`; nessuna logica nei template. Formattatori: `{{data.amount|number}}`, `{{data.expiresAt|date}}`» (engagement §5); segnaposto `{{data.campo}}`, `{{member.firstName}}` (docs/03 §9); radice obbligatoria (Q-66). Formattazione di numeri e date del PoC: Intl it-IT, Europe/Rome (docs/07 §2), es. «fascia 1.500» (docs/10 §7). Template: titolo/testo con segnaposto, icona, link, canale `INAPP` o `EMAIL_FAKE` (F-MSG-02), categoria `POINTS, TIER, REWARD, GAME, PROGRAM` (engagement §2). Anteprima: `POST /v1/message-templates/{code}/render` `{sampleEvent}` (engagement §3), bozza nella richiesta (Q-75).

| Ingresso | Classi | Limiti e valori speciali |
|---|---|---|
| segnaposto | `data.*`, `member.*`, `event.*` presenti | assente, `null`, oggetto, indice di array, senza radice, spazi, graffe singole |
| valore | testo | `$1`, `\`, `{{…}}` annidato, HTML |
| `|number` | intero, decimale, stringa numerica | 0, 999/1000 (raggruppamento), −1500, 1234567, 1.25, 1.5, 1.255 e 0.125 (arrotondamento), testo non numerico |
| `|date` | data `yyyy-MM-dd`, istante `Z`, istante con offset | 23:59:59/00:30 di Roma, cambio d'ora, capodanno, 29 febbraio, data inesistente, testo |
| sintassi | valida | vuoto, senza radice, formattatore sconosciuto, graffe spaiate |
| canale / categoria | `INAPP`, `EMAIL_FAKE` / 5 valori | sconosciuti, canale assente |

**Strategia.** Una classe per riga (guasto singolo), ogni valore limite dei formattatori da solo; gestione e anteprima via API una riga per ramo.

### 9.1 Motore dei template (TPL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-TPL-001 | segnaposto `data.amount` presente (162) | `Hai guadagnato 162 punti` | engagement §7 (accettazione); §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-002 | segnaposto `member.firstName` | `Ciao Giulia` | docs/03 §9; §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-003 | segnaposto `event.type` | `wallet.points.earned` | engagement §5 (contesto {data, member, event}) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-004 | percorso assente in `data` | `[]`; non risolto `data.missing` | engagement §5 (assente → stringa vuota) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-005 | percorso assente in `member` | `[]`; non risolto `member.lastName` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-006 | valore `null` | `[]`; non risolto `data.nul` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-007 | valore oggetto (non semplice) | **AMBIGUO** — `[]`; non risolto `data.obj` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-008 | indice di array `arr.0.name` | **AMBIGUO** — `X` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-009 | segnaposto senza radice `{{amount}}` | `[]`; non risolto `amount` | Q-66 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-010 | spazi dentro le graffe | **AMBIGUO** — `162` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-011 | stesso segnaposto due volte | `162 e 162` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-012 | testo senza segnaposto | `Nessun segnaposto` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-013 | template vuoto | stringa vuota | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-014 | template assente (`null`) | **AMBIGUO** — stringa vuota | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-015 | valore con `$1` e `\` (caratteri speciali di sostituzione) | `$1 \ fine` | engagement §5 (valore sostituito com'è) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-016 | valore che contiene a sua volta `{{data.amount}}` | `{{data.amount}}` | engagement §5 (nessuna logica: niente sostituzione ricorsiva) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-017 | valore con HTML `<b>ciao</b>` | **AMBIGUO** — `<b>ciao</b>` | engagement §5 (nessun escape previsto) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-018 | graffe singole `{data.amount}` | `{data.amount}` | engagement §5 (segnaposto = doppie graffe) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-019 | `\|number` di 0 | `0` | engagement §5; docs/07 §2 (Intl it-IT) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-020 | `\|number` di 999 | `999` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-021 | `\|number` di 1000 | `1.000` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-022 | `\|number` di 1500 | `1.500` | engagement §5; docs/07 §2; docs/10 §7 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-023 | `\|number` di 1234567 | `1.234.567` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-024 | `\|number` di −1500 | `-1.500` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-025 | `\|number` di 1.25 | `1,25` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-026 | `\|number` di 1.5 | `1,5` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-027 | `\|number` di 1.255 (arrotondamento) | **AMBIGUO** — `1,26` | engagement §5 (cifre decimali non fissate) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-028 | `\|number` di 0.125 (arrotondamento al pari) | **AMBIGUO** — `0,12` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-029 | `\|number` di stringa "1500" | `1.500` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-030 | `\|number` di stringa non numerica "abc" | **AMBIGUO** — `abc` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-031 | `\|date` di 2026-10-31 | `31 ottobre 2026` | engagement §5; docs/07 §2 (it-IT, Europe/Rome) | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-032 | `\|date` di 2026-10-31T23:30:00Z (00:30 dell'1/11 a Roma) | `1 novembre 2026` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-033 | `\|date` di 2026-10-31T22:59:59Z (23:59:59 CET) | `31 ottobre 2026` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-034 | `\|date` di 2026-03-29T00:30:00Z (giorno del cambio d'ora) | `29 marzo 2026` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-035 | `\|date` di 2026-12-31T23:00:00Z (capodanno a Roma) | `1 gennaio 2027` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-036 | `\|date` di 2028-02-29 | `29 febbraio 2028` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-037 | `\|date` di 2026-10-31T23:30:00+01:00 | `31 ottobre 2026` | engagement §5; docs/07 §2 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-038 | `\|date` di un testo non data | **AMBIGUO** — `abc` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-039 | `\|date` di 2026-02-30 (data inesistente) | **AMBIGUO** — `2026-02-30` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-040 | formattatore sconosciuto `\|euro` in resa | **AMBIGUO** — `162` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |
| TB-ENG-TPL-041 | percorso assente con `\|number` | `[]`; non risolto `data.missing` | engagement §5 | `TestbookEngTemplateTest` · `tpl.csv` |

### 9.2 Sintassi (TPV)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-TPV-001 | segnaposto validi con formattatori ammessi | valido | engagement §5; F-MSG-02 | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-002 | segnaposto vuoto `{{}}` | problema «vuoto» | engagement §5 | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-003 | segnaposto senza radice `{{amount}}` | problema «data., member. o event.» | Q-66; engagement §5 | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-004 | formattatore sconosciuto `\|euro` | problema «formattatore sconosciuto» | engagement §5 (solo number, date) | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-005 | graffa aperta non chiusa | problema «non bilanciate» | engagement §5 | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-006 | graffa chiusa senza apertura | problema «non bilanciate» | engagement §5 | `TestbookEngTemplateTest` · `tpv.csv` |
| TB-ENG-TPV-007 | template assente | **AMBIGUO** — valido | engagement §5 | `TestbookEngTemplateTest` · `tpv.csv` |

### 9.3 Gestione dei template (TAD)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-TAD-001 | template valido `INAPP` | `201`, versione 0 | BO-19; F-MSG-02 | `TestbookEngMessagingIT`#templateCreate |
| TB-ENG-TAD-002 | canale `EMAIL_FAKE` | `201` | engagement §2 (channel) | `TestbookEngMessagingIT`#templateEmailFake |
| TB-ENG-TAD-003 | canale sconosciuto `SMS` | `422`, campo `channel` | engagement §2 | `TestbookEngMessagingIT`#templateBadChannel |
| TB-ENG-TAD-004 | canale assente | **AMBIGUO** — `201`, canale `INAPP` | engagement §2 | `TestbookEngMessagingIT`#templateDefaultChannel |
| TB-ENG-TAD-005 | le 5 categorie `POINTS, TIER, REWARD, GAME, PROGRAM` | `201` per ciascuna | engagement §2 (category) | `TestbookEngMessagingIT`#templateCategories |
| TB-ENG-TAD-006 | categoria sconosciuta `ALTRO` | `422`, campo `category` | engagement §2 | `TestbookEngMessagingIT`#templateBadCategory |
| TB-ENG-TAD-007 | titolo con segnaposto senza radice `{{amount}}` | `422`, campo `titleTpl` | Q-66; engagement §5 | `TestbookEngMessagingIT`#templateBadRoot |
| TB-ENG-TAD-008 | testo con formattatore `\|euro` | `422`, campo `bodyTpl` | engagement §5 | `TestbookEngMessagingIT`#templateBadFormatter |
| TB-ENG-TAD-009 | titolo assente | `422`, campo `titleTpl` | F-MSG-02 (titolo/testo) | `TestbookEngMessagingIT`#templateNoTitle |
| TB-ENG-TAD-010 | nome assente | **AMBIGUO** — `422`, campo `name` | engagement §2 | `TestbookEngMessagingIT`#templateNoName |
| TB-ENG-TAD-011 | codice già usato | `409 CODE_TAKEN` | docs/06 §2 | `TestbookEngMessagingIT`#templateDuplicate |
| TB-ENG-TAD-012 | modifica con versione superata | `409 VERSION_CONFLICT` | docs/06 §4 | `TestbookEngMessagingIT`#templateVersionConflict |
| TB-ENG-TAD-013 | modifica di un template inesistente | `404` | docs/06 §2 | `TestbookEngMessagingIT`#templateNotFound |
| TB-ENG-TAD-014 | modifica che cambia il codice | **AMBIGUO** — `409 CODE_IMMUTABLE` | docs/06 §2 | `TestbookEngMessagingIT`#templateCodeImmutable |

### 9.4 Anteprima renderizzata (RND)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-RND-001 | anteprima su evento campione (`amount` 1500, `subject` del membro) | segnaposto risolti (`1.500`, nome del membro), nessun mancante | engagement §3 (render); BO-19 | `TestbookEngMessagingIT`#renderSample |
| TB-ENG-RND-002 | anteprima di una bozza (`titleTpl` nella richiesta) con percorso assente | testo reso e percorsi mancanti elencati | engagement §3; BO-19; Q-75 | `TestbookEngMessagingIT`#renderDraft |
| TB-ENG-RND-003 | anteprima di un template inesistente | `404` | docs/06 §2 | `TestbookEngMessagingIT`#renderNotFound |
| TB-ENG-RND-004 | anteprima con ruolo `ANALYST` | `200` (non è una scrittura) | docs/08 §2 (lettura per tutti) | `TestbookEngMessagingIT`#renderAnalyst |

## 10. Regole di notifica e condizioni (R22, R23, R27, R29)

**Regola.** «Una regola collega un tipo di fatto (+ condizione opzionale) a un template» (docs/03 §9); `notification_rule`: `fact_type`, `condition jsonb null` («stesso formato condizioni, spazio `data.*`»), `template_code`, `enabled` (engagement §2). Formato delle condizioni: docs/03 §3.3 («Campo assente → la foglia è falsa (tranne `nexists`). Tipi incompatibili → falsa, mai eccezione»; su array «vero se almeno un elemento soddisfa»). Membri non attivi: Q-70 («nessun messaggio agli `ANONYMIZED`; i `BLOCKED` li ricevono») e Q-180 DECISA (nessun messaggio agli `INACTIVE`; senza snapshot il messaggio parte). Il template non ha un campo "abilitato": l'interruttore è della regola (BO-19 `rules`).

| Ingresso | Classi |
|---|---|
| tipo di fatto | uguale a quello della regola, diverso |
| condizione | assente, vera, falsa |
| regola | attiva, disattivata |
| stato del membro | `ACTIVE`, `INACTIVE`, `BLOCKED`, `ANONYMIZED`, membro senza snapshot |
| comparatori | i 14 di docs/03 §3.3 × vero / falso / campo assente; limiti di `gt/gte/lt/lte/between`; tipi incompatibili; array; gruppi `all/any/not`, annidati, vuoti |

**Strategia.** Regole × fatti: tabella **completa** 2 × 3 × 2 × 5 = 60 righe (dipendenza combinata di tipo, condizione, interruttore e stato). Condizioni: ogni comparatore vero, falso e su campo assente (36), poi limiti, tipi, array e gruppi uno alla volta. Gestione delle regole: guasto singolo.

### 10.1 Condizioni (CND)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-CND-001 | `eq` vero: `data.amount eq 150` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-002 | `eq` falso: `data.amount eq 151` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-003 | `eq` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-004 | `neq` vero: `data.currency neq "STS"` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-005 | `neq` falso: `data.currency neq "PTS"` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-006 | `neq` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-007 | `gt` vero: `data.amount gt 149` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-008 | `gt` falso: `data.amount gt 150` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-009 | `gt` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-010 | `gte` vero: `data.amount gte 150` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-011 | `gte` falso: `data.amount gte 151` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-012 | `gte` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-013 | `lt` vero: `data.amount lt 151` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-014 | `lt` falso: `data.amount lt 150` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-015 | `lt` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-016 | `lte` vero: `data.amount lte 150` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-017 | `lte` falso: `data.amount lte 149` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-018 | `lte` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-019 | `in` vero: `data.role in ["REFERRER", "REFEREE"]` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-020 | `in` falso: `data.role in ["REFEREE"]` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-021 | `in` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-022 | `nin` vero: `data.role nin ["REFEREE"]` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-023 | `nin` falso: `data.role nin ["REFERRER"]` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-024 | `nin` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-025 | `contains` vero: `data.tags contains "B"` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-026 | `contains` falso: `data.tags contains "Z"` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-027 | `contains` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-028 | `ncontains` vero: `data.tags ncontains "Z"` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-029 | `ncontains` falso: `data.tags ncontains "A"` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-030 | `ncontains` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-031 | `between` vero: `data.amount between [100, 200]` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-032 | `between` falso: `data.amount between [151, 200]` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-033 | `between` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-034 | `startsWith` vero: `data.code startsWith "CAF-"` (amount=150) | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-035 | `startsWith` falso: `data.code startsWith "XYZ"` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-036 | `startsWith` su campo assente `data.missing` | falso (campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-037 | `exists` su campo presente | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-038 | `exists` su campo assente | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-039 | `nexists` su campo assente | vero (unica eccezione al campo assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-040 | `nexists` su campo presente | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-041 | `exists` su campo `null` | **AMBIGUO** — falso (null = assente) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-042 | `gt` con valore uguale (150 > 150) | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-043 | `lte` con valore uguale | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-044 | `between [150,200]` con 150 | **AMBIGUO** — vero (estremi inclusi) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-045 | `between [100,150]` con 150 | **AMBIGUO** — vero (estremi inclusi) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-046 | `eq` numero contro stringa "150" | falso (tipi incompatibili) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-047 | `gt` su testo "PTS" | falso, nessuna eccezione | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-048 | `gt` con valore di confronto testuale | falso, nessuna eccezione | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-049 | `gt` su stringa numerica "10" > 5 | **AMBIGUO** — vero (la stringa numerica è convertita) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-050 | `eq` booleano true | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-051 | `data.items[*].category eq TECH` (uno su due) | vero (almeno un elemento) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-052 | `data.items[*].category eq HOME` | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-053 | campo `member.tier` (fuori da `data.*`) | falso (le regole leggono solo `data.*`) | engagement §2 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-054 | condizione `null` | vero (nessuna condizione) | engagement §2 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-055 | condizione `{}` | vero (nessuna condizione) | engagement §2 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-056 | `all` di due foglie vere | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-057 | `all` con una foglia falsa | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-058 | `any` con una foglia vera | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-059 | `any` di due foglie false | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-060 | `not` di una foglia vera | falso | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-061 | `not` di una foglia falsa | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-062 | gruppi annidati `all(any(vero,falso), not(falso))` | vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-063 | `all` senza regole | **AMBIGUO** — vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-064 | `any` senza regole | **AMBIGUO** — vero | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |
| TB-ENG-CND-065 | comparatore sconosciuto `like` | **AMBIGUO** — falso (la gestione lo rifiuta al salvataggio) | docs/03 §3.3 | `TestbookEngConditionTest` · `cnd.csv` |

### 10.2 Regole × fatti × stato del membro (RUL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-RUL-001 | tipo di fatto uguale · senza condizione · regola attiva · membro `ACTIVE` | 1 messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-002 | tipo di fatto uguale · senza condizione · regola attiva · membro `INACTIVE` | nessun messaggio (Q-180 DECISA) | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70; Q-180 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-003 | tipo di fatto uguale · senza condizione · regola attiva · membro `BLOCKED` | 1 messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-004 | tipo di fatto uguale · senza condizione · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-005 | tipo di fatto uguale · senza condizione · regola attiva · membro senza snapshot | 1 messaggio (Q-180 DECISA: lo snapshot può arrivare dopo) | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70; Q-180 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-006 | tipo di fatto uguale · senza condizione · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-007 | tipo di fatto uguale · senza condizione · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-008 | tipo di fatto uguale · senza condizione · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-009 | tipo di fatto uguale · senza condizione · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-010 | tipo di fatto uguale · senza condizione · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-011 | tipo di fatto uguale · condizione vera · regola attiva · membro `ACTIVE` | 1 messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-012 | tipo di fatto uguale · condizione vera · regola attiva · membro `INACTIVE` | nessun messaggio (Q-180 DECISA) | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70; Q-180 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-013 | tipo di fatto uguale · condizione vera · regola attiva · membro `BLOCKED` | 1 messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-014 | tipo di fatto uguale · condizione vera · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-015 | tipo di fatto uguale · condizione vera · regola attiva · membro senza snapshot | 1 messaggio (Q-180 DECISA: lo snapshot può arrivare dopo) | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70; Q-180 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-016 | tipo di fatto uguale · condizione vera · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-017 | tipo di fatto uguale · condizione vera · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-018 | tipo di fatto uguale · condizione vera · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-019 | tipo di fatto uguale · condizione vera · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-020 | tipo di fatto uguale · condizione vera · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-021 | tipo di fatto uguale · condizione falsa · regola attiva · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-022 | tipo di fatto uguale · condizione falsa · regola attiva · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-023 | tipo di fatto uguale · condizione falsa · regola attiva · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-024 | tipo di fatto uguale · condizione falsa · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-025 | tipo di fatto uguale · condizione falsa · regola attiva · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-026 | tipo di fatto uguale · condizione falsa · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-027 | tipo di fatto uguale · condizione falsa · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-028 | tipo di fatto uguale · condizione falsa · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-029 | tipo di fatto uguale · condizione falsa · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-030 | tipo di fatto uguale · condizione falsa · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-031 | tipo di fatto diverso · senza condizione · regola attiva · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-032 | tipo di fatto diverso · senza condizione · regola attiva · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-033 | tipo di fatto diverso · senza condizione · regola attiva · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-034 | tipo di fatto diverso · senza condizione · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-035 | tipo di fatto diverso · senza condizione · regola attiva · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-036 | tipo di fatto diverso · senza condizione · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-037 | tipo di fatto diverso · senza condizione · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-038 | tipo di fatto diverso · senza condizione · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-039 | tipo di fatto diverso · senza condizione · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-040 | tipo di fatto diverso · senza condizione · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-041 | tipo di fatto diverso · condizione vera · regola attiva · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-042 | tipo di fatto diverso · condizione vera · regola attiva · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-043 | tipo di fatto diverso · condizione vera · regola attiva · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-044 | tipo di fatto diverso · condizione vera · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-045 | tipo di fatto diverso · condizione vera · regola attiva · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-046 | tipo di fatto diverso · condizione vera · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-047 | tipo di fatto diverso · condizione vera · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-048 | tipo di fatto diverso · condizione vera · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-049 | tipo di fatto diverso · condizione vera · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-050 | tipo di fatto diverso · condizione vera · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-051 | tipo di fatto diverso · condizione falsa · regola attiva · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-052 | tipo di fatto diverso · condizione falsa · regola attiva · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-053 | tipo di fatto diverso · condizione falsa · regola attiva · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-054 | tipo di fatto diverso · condizione falsa · regola attiva · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-055 | tipo di fatto diverso · condizione falsa · regola attiva · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-056 | tipo di fatto diverso · condizione falsa · regola disattivata · membro `ACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-057 | tipo di fatto diverso · condizione falsa · regola disattivata · membro `INACTIVE` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-058 | tipo di fatto diverso · condizione falsa · regola disattivata · membro `BLOCKED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-059 | tipo di fatto diverso · condizione falsa · regola disattivata · membro `ANONYMIZED` | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |
| TB-ENG-RUL-060 | tipo di fatto diverso · condizione falsa · regola disattivata · membro senza snapshot | nessun messaggio | docs/03 §9; engagement §2, §5; F-MSG-01; Q-70 | `TestbookEngMessagingIT` · `rul.csv` |

### 10.3 Gestione delle regole (RAD)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-RAD-001 | regola valida (tipo breve) | `201`, attiva di default | BO-19 rules; engagement §2 | `TestbookEngMessagingIT`#ruleCreate |
| TB-ENG-RAD-002 | tipo di fatto in forma completa `io.loyaltyhub.fact.coupon.used` | **AMBIGUO** — `201`, salvato in forma breve | docs/05 | `TestbookEngMessagingIT`#ruleFullType |
| TB-ENG-RAD-003 | tipo di fatto sconosciuto | `422 RULE_INVALID`, campo `factType` | docs/05 (catalogo fatti); BO-19 | `TestbookEngMessagingIT`#ruleUnknownType |
| TB-ENG-RAD-004 | tipo `message.delivered` | `422 RULE_INVALID` | engagement §5 (mai oggetto di regole) | `TestbookEngMessagingIT`#ruleMessageDelivered |
| TB-ENG-RAD-005 | tipo di fatto assente | `422`, campo `factType` | engagement §2 (fact_type) | `TestbookEngMessagingIT`#ruleNoType |
| TB-ENG-RAD-006 | template inesistente | `422`, campo `templateCode` | engagement §2 (template_code) | `TestbookEngMessagingIT`#ruleUnknownTemplate |
| TB-ENG-RAD-007 | condizione su `member.tier` | `422`, campo `condition` | engagement §2 (spazio `data.*`) | `TestbookEngMessagingIT`#ruleConditionOutsideData |
| TB-ENG-RAD-008 | condizione con comparatore sconosciuto `like` | `422`, campo `condition` | docs/03 §3.3 | `TestbookEngMessagingIT`#ruleUnknownComparator |
| TB-ENG-RAD-009 | gruppo con operatore `xor` | `422`, campo `condition` | docs/03 §3.3 (all, any, not) | `TestbookEngMessagingIT`#ruleUnknownGroup |
| TB-ENG-RAD-010 | codice già usato | `409 CODE_TAKEN` | docs/06 §2 | `TestbookEngMessagingIT`#ruleDuplicate |
| TB-ENG-RAD-011 | disattivazione con versione superata | `409 VERSION_CONFLICT` | docs/06 §4 | `TestbookEngMessagingIT`#ruleVersionConflict |
| TB-ENG-RAD-012 | modifica che cambia il codice | **AMBIGUO** — `409 CODE_IMMUTABLE` | docs/06 §2 | `TestbookEngMessagingIT`#ruleCodeImmutable |

## 11. Consegna, deduplica, effetti, snapshot (R24–R28, R45)

**Regola.** «Deduplica per `(memberId, sourceEventId, templateCode)`» (docs/03 §9; UQ di `inbox_message`, engagement §2); accettazione «stesso evento rielaborato → nessun duplicato» (engagement §7). «`message.delivered` non è mai oggetto di regole né di webhook» (engagement §5); `message.delivered` prodotto per ogni messaggio (engagement §4). Effetto `message.send` (`SEND_MESSAGE`, docs/03 §3.4): chiave `effectId`, `data.*` = effetto con `params` sovrapposti (Q-68); un template inesistente non è verificabile dalla campagna e «l'errore emergerà a valle in DLQ» (campaign §5, Q-73). `EMAIL_FAKE` «produce solo un'anteprima consultabile da BO-19» (engagement §1): solo registro (Q-67). Snapshot locale da `member.*`, `tier.*`, `member.segment.*` (engagement §4); anonimizzazione: nome → "Membro anonimo" (docs/03 §2), nei messaggi già resi e nei corpi delle consegne webhook, rifirmati (Q-125).

**Strategia.** Una riga per ramo (doppio invio, rielaborazione, due regole con template uguali/diversi, effetto con e senza `effectId`, template sconosciuto, membro anonimizzato, canale finto); snapshot: un tipo di fatto per riga.

### 11.1 Deduplica ed effetti (DDP)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-DDP-001 | stesso fatto inviato due volte | 1 messaggio | docs/03 §9 (deduplica); engagement §7 | `TestbookEngMessagingIT`#sameFactTwice |
| TB-ENG-DDP-002 | stesso fatto rielaborato (dopo la perdita dell'idempotenza del consumer) | 1 messaggio (terna membro, evento, template) | docs/03 §9; engagement §2 UQ; §7 | `TestbookEngMessagingIT`#reprocessedFact |
| TB-ENG-DDP-003 | due regole sullo stesso tipo con lo stesso template | 1 messaggio | docs/03 §9 (deduplica per template) | `TestbookEngMessagingIT`#twoRulesSameTemplate |
| TB-ENG-DDP-004 | due regole sullo stesso tipo con template diversi | 2 messaggi | docs/03 §9 | `TestbookEngMessagingIT`#twoRulesTwoTemplates |
| TB-ENG-DDP-005 | messaggio nuovo e suo duplicato | un solo `message.delivered`, figlio del fatto sorgente | engagement §4; docs/05 | `TestbookEngMessagingIT`#deliveredOncePerMessage |
| TB-ENG-DDP-006 | fatto `message.delivered` in ingresso | nessun messaggio (mai oggetto di regole) | engagement §5 | `TestbookEngMessagingIT`#deliveredFactIgnored |
| TB-ENG-DDP-007 | effetto `message.send` con `params` | messaggio col template e i parametri | docs/03 §3.4; Q-68 | `TestbookEngMessagingIT`#messageSendEffect |
| TB-ENG-DDP-008 | due `message.send` con lo stesso `effectId` e id d'evento diversi | 1 messaggio | Q-68 | `TestbookEngMessagingIT`#messageSendSameEffect |
| TB-ENG-DDP-009 | due `message.send` senza `effectId` | 2 messaggi (chiave = id dell'evento) | Q-68 | `TestbookEngMessagingIT`#messageSendNoEffectId |
| TB-ENG-DDP-010 | `message.send` con template inesistente | nessun messaggio; effetto in DLQ con `lh-error-code` `TEMPLATE_NOT_FOUND` | campaign §5 (l'errore emergerà a valle in DLQ); Q-73 | `TestbookEngMessagingIT`#messageSendUnknownTemplate |
| TB-ENG-DDP-011 | `message.send` a un membro `ANONYMIZED` | nessun messaggio | Q-70 | `TestbookEngMessagingIT`#messageSendAnonymized |
| TB-ENG-DDP-012 | regola con template `EMAIL_FAKE` | nel registro `/v1/messages`, non nell'inbox del portale né nei non letti | engagement §1; Q-67 | `TestbookEngMessagingIT`#emailFakeOnlyInLog |
| TB-ENG-DDP-013 | messaggio reso: `{{data.amount}}`, `{{member.firstName}}`, percorso assente | valori sostituiti, assente → vuoto | engagement §5; §7 | `TestbookEngMessagingIT`#renderedDelivery |
| TB-ENG-DDP-014 | `message.send` senza membro (`subject` non `member:`) | **AMBIGUO** — nessun messaggio; effetto in DLQ con `lh-error-code` `INVALID_EFFECT` | docs/03 §3.4 | `TestbookEngMessagingIT`#messageSendWithoutMember |

### 11.2 Snapshot del membro (SNP)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-SNP-001 | `member.registered` con nome e stato | snapshot con nome e stato del membro | engagement §4 (member.* per lo snapshot) | `TestbookEngMessagingIT`#snapshotRegistered |
| TB-ENG-SNP-002 | `member.status.changed` → `BLOCKED` | stato dello snapshot `BLOCKED` | engagement §4 | `TestbookEngMessagingIT`#snapshotStatus |
| TB-ENG-SNP-003 | `tier.upgraded` → `GOLD` | livello dello snapshot `GOLD` | engagement §4 (tier.*) | `TestbookEngMessagingIT`#snapshotTierUp |
| TB-ENG-SNP-004 | `tier.downgraded` → `SILVER` | livello dello snapshot `SILVER` | engagement §4 | `TestbookEngMessagingIT`#snapshotTierDown |
| TB-ENG-SNP-005 | `tier.retained` con `tier=PLATINUM` | livello dello snapshot `PLATINUM` | engagement §4 | `TestbookEngMessagingIT`#snapshotTierRetained |
| TB-ENG-SNP-006 | `member.segment.entered` `SEG-X` | `SEG-X` tra i segmenti dello snapshot | engagement §4 (member.segment.*) | `TestbookEngMessagingIT`#snapshotSegmentEntered |
| TB-ENG-SNP-007 | `member.segment.left` `SEG-X` | `SEG-X` tolto dai segmenti | engagement §4 | `TestbookEngMessagingIT`#snapshotSegmentLeft |
| TB-ENG-SNP-008 | anonimizzazione (`member.status.changed` → `ANONYMIZED`) con un messaggio che contiene il nome | nome sostituito da "Membro anonimo" nel messaggio; snapshot senza nome e `ANONYMIZED` | docs/03 §2; Q-125; Q-70 | `TestbookEngMessagingIT`#snapshotAnonymized |
| TB-ENG-SNP-009 | anonimizzazione con una consegna webhook che contiene il nome | corpo senza il nome e firma ricalcolata, verificabile col segreto | Q-125 | `TestbookEngWebhookIT`#anonymizedDeliveryRedacted |

## 12. Inbox e pulizie (R31–R33)

**Regola.** PT-12: elenco cronologico con la nuova riga in cima, non letti, tocco → letto, *Segna tutte come lette*; campanella `unread-count` (docs/09 §1). API `GET /v1/portal/inbox?memberId=&page=`, `unread-count`, `POST …/{id}/read`, `…/read-all` (engagement §3), `memberId` esplicito (docs/06 §2), `{items, page}` con `size` massimo 100 (docs/06 §2). Registro `/v1/messages?memberId=&category=&channel=` per BO-19 (engagement §3). Pulizia: «`inbox_message` > 180 giorni, `webhook_delivery` > 14 giorni, `popup_view` > 90 giorni» (engagement §5).

| Ingresso | Classi | Limiti |
|---|---|---|
| messaggi | `INAPP` non letti, `INAPP` letti, `EMAIL_FAKE` | membro senza messaggi, messaggio di un altro membro, id inesistente |
| `memberId` | in query, nel corpo | assente |
| `size` | 2 | 500 (> 100) |
| età | 180 g, 90 g, 13 g | 180 g + 1 s, 91 g, 14 g + 1 s |

**Strategia.** Una riga per operazione e per errore; pulizie ai limiti (dentro/fuori).

### 12.1 Inbox (IBX)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-IBX-001 | non letti di un membro senza messaggi | 0 | PT-12; engagement §3 | `TestbookEngMessagingIT`#unreadZero |
| TB-ENG-IBX-002 | non letti con 3 `INAPP` non letti, 1 `INAPP` letto, 1 `EMAIL_FAKE` non letto | 3 | PT-12; Q-67 | `TestbookEngMessagingIT`#unreadCountsInAppOnly |
| TB-ENG-IBX-003 | elenco dell'inbox del portale | solo `INAPP`, dal più recente | PT-12 (nuova riga in cima); Q-67 | `TestbookEngMessagingIT`#inboxOrderAndChannel |
| TB-ENG-IBX-004 | segna letto un messaggio | `200`, `read=true`, `readAt` valorizzato; non letti −1 | PT-12 | `TestbookEngMessagingIT`#markRead |
| TB-ENG-IBX-005 | segna letto un messaggio già letto | **AMBIGUO** — `200`, `readAt` invariato | PT-12 | `TestbookEngMessagingIT`#markReadTwice |
| TB-ENG-IBX-006 | segna letto il messaggio di un altro membro | `404` | docs/06 §2 (memberId esplicito) | `TestbookEngMessagingIT`#markReadOtherMember |
| TB-ENG-IBX-007 | segna letto un id inesistente | `404` | docs/06 §2 | `TestbookEngMessagingIT`#markReadUnknown |
| TB-ENG-IBX-008 | segna tutte come lette | `marked` = non letti `INAPP`, poi 0 non letti; `EMAIL_FAKE` non toccato | PT-12; Q-67 | `TestbookEngMessagingIT`#readAll |
| TB-ENG-IBX-009 | segna tutte come lette senza non letti | `marked` = 0 | PT-12 | `TestbookEngMessagingIT`#readAllNothing |
| TB-ENG-IBX-010 | `GET /v1/portal/inbox` senza `memberId` | `400` | docs/06 §2 | `TestbookEngMessagingIT`#inboxWithoutMember |
| TB-ENG-IBX-011 | `unread-count` senza `memberId` | `400` | docs/06 §2 | `TestbookEngMessagingIT`#unreadWithoutMember |
| TB-ENG-IBX-012 | `read` senza `memberId` | `400` | docs/06 §2 | `TestbookEngMessagingIT`#readWithoutMember |
| TB-ENG-IBX-013 | `read-all` senza `memberId` | `400` | docs/06 §2 | `TestbookEngMessagingIT`#readAllWithoutMember |
| TB-ENG-IBX-014 | `read-all` con `memberId` nel corpo | **AMBIGUO** — `200` | docs/06 §2 | `TestbookEngMessagingIT`#readAllMemberInBody |
| TB-ENG-IBX-015 | paginazione `size=2` su 4 messaggi | `{items, page}` con 2 elementi, `totalItems` 4, `totalPages` 2 | docs/06 §2 | `TestbookEngMessagingIT`#inboxPaging |
| TB-ENG-IBX-016 | `size=500` | `size` ridotto a 100 | docs/06 §2 (size massimo 100) | `TestbookEngMessagingIT`#inboxSizeCap |
| TB-ENG-IBX-017 | registro `/v1/messages` per membro e canale | tutti i canali, filtrabile per `channel` e `category` | engagement §3; BO-19 log | `TestbookEngMessagingIT`#messageLog |

### 12.2 Pulizie (CLN)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-CLN-001 | messaggio dell'inbox di 180 giorni + 1 s | eliminato dalla pulizia | engagement §5 (> 180 giorni) | `TestbookEngMessagingIT`#purgeInboxOld |
| TB-ENG-CLN-002 | messaggio dell'inbox di 180 giorni esatti | conservato | engagement §5 (> 180 giorni) | `TestbookEngMessagingIT`#purgeInboxBoundary |
| TB-ENG-CLN-003 | vista di pop-up di 91 giorni fa | eliminata | engagement §5 (> 90 giorni) | `TestbookEngMessagingIT`#purgePopupViewOld |
| TB-ENG-CLN-004 | vista di pop-up di 90 giorni fa | conservata | engagement §5 | `TestbookEngMessagingIT`#purgePopupViewBoundary |
| TB-ENG-CLN-005 | consegna webhook di 14 giorni + 1 s | eliminata | engagement §5 (> 14 giorni) | `TestbookEngMessagingIT`#purgeDeliveryOld |
| TB-ENG-CLN-006 | consegna webhook di 13 giorni | conservata | engagement §5 | `TestbookEngMessagingIT`#purgeDeliveryRecent |

## 13. Tema (R34, R35)

**Regola.** «`GET/PUT /v1/theme` — colori validati come esadecimali; contrasto testo/primario ≥ 4.5 altrimenti `422 THEME_CONTRAST_TOO_LOW`»; «`GET /v1/portal/theme` cache 60 s» (engagement §3); accettazione «`PUT /v1/theme` con nuovo `primary` → `GET /v1/portal/theme` lo restituisce» (engagement §7). Q-79: testo = `night`, verificato su `primary` e su `bg`; `secondary` e `coin` non verificati; `currency_names` di default `punti`/`punti status`; `version` per il lock. Contrasto WCAG 2.x (BO-20 «AA»).

| Ingresso | Classi valide | Non valide / limiti |
|---|---|---|
| colore | `#RRGGBB` maiuscolo/minuscolo, `#000000`, `#FFFFFF` | senza `#`, `#RGB`, `#RRGGBBAA`, cifre non esadecimali, 5 o 7 cifre, vuoto, assente, nome di colore, `rgb()` |
| contrasto `night`/`primary` | 4,50003:1 (`#577B76` su nero), 4,56:1 | 4,499:1 (`#457E76`), 4,49:1 (`#747474`), 1,5:1 |
| contrasto `night`/`bg` | 4,50003:1 | 4,499:1 |

**Strategia.** Formato: una classe per riga. Contrasto: i due confini (appena sopra/sotto 4,5, su `primary` e su `bg`) uno alla volta, poi entrambi; il 4,5 esatto non è ottenibile con colori a 8 bit, si usano le coppie più vicine.

### 13.1 Formato e contrasto (THM)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-THM-001 | `#1FB98F` (maiuscole) | esadecimale valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-002 | `#1fb98f` (minuscole) | esadecimale valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-003 | `#000000` (minimo) | esadecimale valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-004 | `#FFFFFF` (massimo) | esadecimale valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-005 | `1FB98F` senza `#` | **AMBIGUO** — non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-006 | `#FFF` (forma corta a 3 cifre) | **AMBIGUO** — non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-007 | `#1FB98F80` (8 cifre con alfa) | **AMBIGUO** — non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-008 | `#GGGGGG` (cifre non esadecimali) | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-009 | `#12345` (5 cifre) | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-010 | `#1234567` (7 cifre) | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-011 | stringa vuota | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-012 | assente (`null`) | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-013 | nome di colore `verde` | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-014 | `rgb(0 0 0)` | non valido | engagement §3 (colori validati come esadecimali) | `TestbookEngThemeTest` · `thm.csv` |
| TB-ENG-THM-015 | contrasto `#000000` su `#FFFFFF` | 21:1 (massimo WCAG) | engagement §3; BO-20 (AA) | `TestbookEngThemeTest#contrastExtremes` |
| TB-ENG-THM-016 | contrasto di un colore con sé stesso | 1:1 (minimo) | engagement §3; BO-20 | `TestbookEngThemeTest#contrastSame` |
| TB-ENG-THM-017 | contrasto simmetrico (a,b) = (b,a) | stesso valore | engagement §3; BO-20 | `TestbookEngThemeTest#contrastSymmetric` |

### 13.2 Tema via API (THA)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-THA-001 | tema Aurora | `200` | docs/07 §5.3; engagement §3 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-002 | `night` su `primary` 4,5000:1 (appena sopra la soglia) | `200` | engagement §3 (≥ 4.5); Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-003 | `night` su `primary` 4,499:1 (appena sotto) | `422 THEME_CONTRAST_TOO_LOW`, campo `colors.primary` | engagement §3; Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-004 | `night` su `primary` 4,56:1 (grigio #757575) | `200` | engagement §3; Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-005 | `night` su `primary` 4,49:1 (grigio #747474) | `422 THEME_CONTRAST_TOO_LOW` | engagement §3; Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-006 | `night` su `bg` 4,5000:1 | `200` | Q-79 (verificato anche su bg) | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-007 | `night` su `bg` 4,499:1 | `422 THEME_CONTRAST_TOO_LOW`, campo `colors.bg` | Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-008 | entrambi sotto soglia | `422`, campi `colors.primary` e `colors.bg` | engagement §3; Q-79 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-009 | primario scuro su testo scuro (1,5:1) | `422 THEME_CONTRAST_TOO_LOW` | engagement §3; BO-20 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-010 | primario non esadecimale `verde` | `422`, campo `colors.primary` (non un errore di contrasto) | engagement §3 (colori esadecimali) | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-011 | primario in minuscolo | `200` | engagement §3 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-012 | primario `#FFF` (3 cifre) | **AMBIGUO** — `422` | engagement §3 | `TestbookEngContentIT` · `tha.csv` |
| TB-ENG-THA-013 | `secondary` e `coin` a contrasto 1:1 col testo | `200` (non verificati) | Q-79 | `TestbookEngContentIT`#secondaryCoinNotChecked |
| TB-ENG-THA-014 | colore `coin` assente | `422`, campo `colors.coin` | engagement §2 (colors {primary, secondary, coin, night, bg}); §3 | `TestbookEngContentIT`#missingColor |
| TB-ENG-THA-015 | `PUT` con nuovo `primary` | `GET /v1/portal/theme` lo restituisce subito | engagement §7 (accettazione); F-THM-01 | `TestbookEngContentIT`#themeAppliedAtRuntime |
| TB-ENG-THA-016 | intestazione di cache di `/v1/portal/theme` | `Cache-Control` con `max-age=60` | engagement §3 (cache 60 s) | `TestbookEngContentIT`#portalThemeCache |
| TB-ENG-THA-017 | `PUT` con versione superata | `409 VERSION_CONFLICT` | docs/06 §4; Q-79 (version) | `TestbookEngContentIT`#themeVersionConflict |
| TB-ENG-THA-018 | `PUT` rifiutato per contrasto | tema invariato | engagement §3; BO-20 (blocco al salvataggio) | `TestbookEngContentIT`#rejectedThemeUnchanged |
| TB-ENG-THA-019 | `PUT` senza nomi delle valute | `punti` / `punti status` di default | Q-79 | `TestbookEngContentIT`#currencyDefaults |
| TB-ENG-THA-020 | `PUT` senza `programName` | **AMBIGUO** — `422`, campo `programName` | F-THM-01 (nome programma) | `TestbookEngContentIT`#programNameRequired |
| TB-ENG-THA-021 | `programName` di 41 caratteri | **AMBIGUO** — `422 THEME_INVALID`, campo `programName` | F-THM-01 | `TestbookEngContentIT`#programNameTooLong |
| TB-ENG-THA-022 | logo `javascript:alert(1)` | **AMBIGUO** — `422 THEME_INVALID`, campo `logoUrl` | F-THM-01 (logo) | `TestbookEngContentIT`#logoJavascript |
| TB-ENG-THA-023 | logo `/demo/logo.svg` | **AMBIGUO** — `200` | F-THM-01 (logo) | `TestbookEngContentIT`#logoPath |

## 14. Webhook: URL, indirizzi privati, profili (R40)

**Regola.** «solo URL `https://` (in `local` anche `http://localhost`); blocca indirizzi privati/loopback nel profilo `free` (anti-SSRF)» (engagement §5; docs/11); Q-99: «indirizzi privati e loopback bloccati in ogni profilo tranne `local` (al salvataggio e dopo la risoluzione DNS all'invio); proprietà per cambiarlo». Per "privati" si intendono gli intervalli non instradabili pubblicamente che l'anti-SSRF deve escludere: 10/8, 172.16/12, 192.168/16, loopback 127/8 e `::1`, link-local 169.254/16 e `fe80::/10`, non specificato, ULA `fc00::/7`, IPv4 mappati; gli altri intervalli speciali (CGNAT, 192.0.0/24, 198.18/15, multicast, 240/4) e i nomi `.local`/`.internal` sono **AMBIGUO**.

**Strategia.** Ogni intervallo con il primo e l'ultimo indirizzo e i due vicini esterni (valori limite da soli); schemi e forme dell'URL uno alla volta; profili e invio (blocco sul letterale e dopo la risoluzione, timeout, redirect) una riga ciascuno.

### 14.1 URL (WURL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WURL-001 | `https://example.org/hook` | ammesso | engagement §5 (solo https) | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-002 | `http://` pubblico | rifiutato | engagement §5; docs/11 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-003 | schema `ftp://` | rifiutato | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-004 | URL vuoto | rifiutato | BO-23 (URL obbligatorio) | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-005 | URL relativo `/hook` | rifiutato | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-006 | 10.0.0.1 (10/8, primo) | rifiutato | engagement §5; Q-99; docs/11 (anti-SSRF) | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-007 | 10.255.255.255 (10/8, ultimo) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-008 | 9.255.255.255 (prima di 10/8) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-009 | 11.0.0.0 (dopo 10/8) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-010 | 172.16.0.0 (172.16/12, primo) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-011 | 172.31.255.255 (172.16/12, ultimo) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-012 | 172.15.255.255 (prima di 172.16/12) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-013 | 172.32.0.0 (dopo 172.16/12) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-014 | 192.168.0.0 (192.168/16, primo) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-015 | 192.168.255.255 (192.168/16, ultimo) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-016 | 192.167.255.255 (prima di 192.168/16) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-017 | 192.169.0.0 (dopo 192.168/16) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-018 | 127.0.0.1 (loopback) | rifiutato | engagement §5 (loopback); Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-019 | 127.255.255.254 (127/8) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-020 | 128.0.0.1 (dopo 127/8) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-021 | 169.254.169.254 (link-local, metadati cloud) | rifiutato | docs/11 (anti-SSRF); Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-022 | 169.253.255.255 (prima di 169.254/16) | ammesso | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-023 | 169.255.0.0 (dopo 169.254/16) | ammesso | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-024 | 0.0.0.0 (indirizzo non specificato) | rifiutato | docs/11 (anti-SSRF); Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-025 | 100.64.0.0 (CGNAT 100.64/10, primo) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-026 | 100.127.255.255 (CGNAT, ultimo) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-027 | 100.63.255.255 (prima di 100.64/10) | ammesso | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-028 | 100.128.0.0 (dopo 100.64/10) | ammesso | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-029 | 192.0.0.8 (192.0.0.0/24 riservato IETF) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-030 | 198.18.0.1 (198.18/15 benchmark) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-031 | 224.0.0.1 (multicast) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-032 | 240.0.0.1 (240/4 riservato) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-033 | 8.8.8.8 (pubblico) | ammesso | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-034 | `[::1]` (loopback IPv6) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-035 | `[::]` (non specificato IPv6) | rifiutato | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-036 | `[fe80::1]` (link-local IPv6) | rifiutato | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-037 | `[fc00::1]` (ULA fc00::/7, inizio) | rifiutato | engagement §5 (privati); Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-038 | `[fdff:ffff::1]` (ULA, fine) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-039 | `[fbff::1]` (prima di fc00::/7) | ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-040 | `[::ffff:10.0.0.1]` (IPv4 privato mappato in IPv6) | rifiutato | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-041 | `[2001:4860:4860::8888]` (IPv6 pubblico) | ammesso | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-042 | `localhost` | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-043 | `LOCALHOST` (maiuscole) | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-044 | `api.localhost` | rifiutato | docs/11; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-045 | `printer.local` (mDNS) | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-046 | `metadata.internal` | rifiutato (Q-184 DECISA) | docs/11; Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-047 | `2130706433` (127.0.0.1 in forma decimale) al salvataggio | rifiutato: forma non puntata (Q-184 DECISA) | Q-99 (letterali al salvataggio); Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-048 | credenziali nell'URL | rifiutato (Q-184 DECISA) | engagement §5; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-049 | frammento `#x` | rifiutato (Q-184 DECISA) | engagement §5; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-050 | URL di 500 caratteri (massimo) | ammesso (Q-184 DECISA) | engagement §5; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-051 | URL di 501 caratteri | rifiutato (Q-184 DECISA) | engagement §5; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-052 | profilo `local`: `http://localhost` | ammesso | engagement §5 (in local anche http://localhost) | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-053 | profilo `local`: `http://127.0.0.1` | ammesso | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-054 | profilo `local`: `http://` non locale | rifiutato | engagement §5 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-055 | profilo `local`: indirizzo privato | ammesso | Q-99 (blocco in ogni profilo tranne local) | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-056 | profilo non `local`: `http://localhost` | rifiutato | engagement §5; Q-99 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-057 | `0x7f000001` (127.0.0.1 esadecimale) | rifiutato: forma non puntata (Q-184 DECISA) | Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-058 | `127.1` (forma abbreviata) | rifiutato: forma non puntata (Q-184 DECISA) | Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-059 | `0177.0.0.1` (ottale) | rifiutato: forma non canonica (Q-184 DECISA) | Q-99; Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-060 | `08.8.8.8` (zero iniziale su indirizzo pubblico) | rifiutato: forma non canonica (Q-184 DECISA) | Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-061 | profilo `local`: `2130706433` | rifiutato: la forma canonica vale in ogni profilo (Q-184 DECISA) | Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |
| TB-ENG-WURL-062 | nome con cifre non finali (`123.example.org`) | ammesso (non è un host numerico) | Q-184 | `TestbookEngWebhookTest` · `wurl.csv` |

### 14.2 Profili e invio (WPRF)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WPRF-001 | nessun profilo attivo | indirizzi privati bloccati, `http://localhost` non ammesso | Q-99 | `TestbookEngWebhookTest#defaultProfileBlocks` |
| TB-ENG-WPRF-002 | profilo `demo` | indirizzi privati bloccati | Q-99 | `TestbookEngWebhookTest#demoProfileBlocks` |
| TB-ENG-WPRF-003 | profilo `free` | indirizzi privati bloccati | engagement §5; Q-99 | `TestbookEngWebhookTest#freeProfileBlocks` |
| TB-ENG-WPRF-004 | profilo `local` | indirizzi privati ammessi, `http://localhost` ammesso | engagement §5; Q-99 | `TestbookEngWebhookTest#localProfileAllows` |
| TB-ENG-WPRF-005 | profilo `local` con `block-private-addresses=true` | indirizzi privati bloccati | Q-99 (proprietà per cambiarlo) | `TestbookEngWebhookTest#localProfileOverride` |
| TB-ENG-WPRF-006 | invio verso `https://127.0.0.1:1/` con blocco attivo | nessuna chiamata, errore `BLOCKED_ADDRESS` | engagement §5; Q-99 | `TestbookEngWebhookTest#sendBlockedLiteral` |
| TB-ENG-WPRF-007 | invio verso un nome che il DNS risolve in loopback/privato (nome della macchina) | nessuna chiamata, errore `BLOCKED_ADDRESS` (controllo dopo la risoluzione) | Q-99 | `TestbookEngWebhookTest#sendBlockedAfterDns` |
| TB-ENG-WPRF-008 | endpoint che risponde dopo 6 s | tentativo chiuso a 5 s con errore `TIMEOUT` | engagement §5 (timeout 5 s) | `TestbookEngWebhookTest#timeoutFiveSeconds` |
| TB-ENG-WPRF-009 | endpoint che risponde 302 con `Location` | stato 302 registrato, redirect non seguito | Q-98 | `TestbookEngWebhookTest#redirectNotFollowed` |

## 15. Webhook: firma e ritenti (R37–R39)

**Regola.** «Corpo = CloudEvent originale; header `X-LH-Signature: sha256=<HMAC(secret, body)>`, `X-LH-Event-Id`, `X-LH-Delivery-Id`; timeout 5 s; ritenti a 1, 5, 15 min poi `GAVE_UP`; scheduler ogni 30 s» (engagement §5). Q-98: *Riprova* = un solo tentativo immediato con la stessa tabella; dopo `GAVE_UP` un fallimento torna `GAVE_UP`; ogni non-2xx (anche 3xx) è un fallimento.

| Ingresso | Classi | Limiti |
|---|---|---|
| numero del tentativo | 1, 2, 3, 4, 5 (Riprova) | 0 |
| esito HTTP | 2xx | 199, 200, 299, 300, 302, 404, 500, nessuna risposta |
| stato per la Riprova | `FAILED`, `GAVE_UP` | `PENDING`, `OK` |
| firma | segreto giusto | altro segreto, corpo alterato, assente, senza prefisso |

**Strategia.** Tabella dei ritenti: ogni tentativo × fallimento più i successi; esiti HTTP ai confini della classe 2xx; ogni stato per la Riprova; firma con un vettore noto (HMAC-SHA256 di riferimento) e ogni guasto da solo.

### 15.1 Firma (WSIG)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WSIG-001 | firma di un vettore noto: segreto `key`, corpo `The quick brown fox jumps over the lazy dog` | `sha256=f7bc83f4…2d1a3cd8` (HMAC-SHA256 esadecimale minuscolo) | engagement §5 (X-LH-Signature) | `TestbookEngWebhookTest#knownVector` |
| TB-ENG-WSIG-002 | firma di un corpo UTF-8 non ASCII (`città €`) | HMAC calcolato sui byte UTF-8 | engagement §5 | `TestbookEngWebhookTest#utf8Body` |
| TB-ENG-WSIG-003 | verifica con il segreto giusto | valida | engagement §5; F-WBH-01 | `TestbookEngWebhookTest#verifyOk` |
| TB-ENG-WSIG-004 | verifica con un altro segreto | non valida | engagement §5 | `TestbookEngWebhookTest#verifyWrongSecret` |
| TB-ENG-WSIG-005 | verifica con il corpo alterato di un byte | non valida | engagement §5 | `TestbookEngWebhookTest#verifyTamperedBody` |
| TB-ENG-WSIG-006 | verifica senza firma (`null`) | non valida | engagement §5 | `TestbookEngWebhookTest#verifyNull` |
| TB-ENG-WSIG-007 | verifica con la firma senza prefisso `sha256=` | non valida | engagement §5 | `TestbookEngWebhookTest#verifyNoPrefix` |

### 15.2 Ritenti (WRTY)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WRTY-001 | 1° tentativo fallito | `FAILED`, prossimo tra 1 min | engagement §5 (ritenti a 1, 5, 15 min) | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-002 | 2° tentativo fallito | `FAILED`, prossimo tra 5 min | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-003 | 3° tentativo fallito | `FAILED`, prossimo tra 15 min | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-004 | 4° tentativo fallito | `GAVE_UP`, nessun prossimo | engagement §5; §7 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-005 | 5° tentativo (Riprova dopo GAVE_UP) fallito | `GAVE_UP` senza nuovo ciclo | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-006 | 1° tentativo riuscito | `OK` | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-007 | 4° tentativo riuscito | `OK` | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-008 | Riprova dopo GAVE_UP riuscita | `OK` | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-009 | tentativo numero 0 (fuori dominio) | **AMBIGUO** — `GAVE_UP` | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-010 | numero massimo di tentativi | 4 (primo invio + 3 ritenti) | engagement §5; §7 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-011 | HTTP 199 | fallimento | Q-98 (solo 2xx è successo) | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-012 | HTTP 200 | successo | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-013 | HTTP 204 | successo | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-014 | HTTP 299 | successo | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-015 | HTTP 300 | fallimento | Q-98 (anche 3xx) | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-016 | HTTP 302 (redirect) | fallimento, redirect non seguito | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-017 | HTTP 404 | fallimento | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-018 | HTTP 500 | fallimento | Q-98; engagement §7 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-019 | nessuna risposta (errore di rete o timeout) | fallimento | engagement §5 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-020 | Riprova su `PENDING` | **AMBIGUO** — non ammesso | Q-98 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-021 | Riprova su `OK` | non ammesso | Q-98; BO-23 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-022 | Riprova su `FAILED` | ammesso | Q-98; BO-23 | `TestbookEngWebhookTest` · `wrty.csv` |
| TB-ENG-WRTY-023 | Riprova su `GAVE_UP` | ammesso | Q-98; BO-23 | `TestbookEngWebhookTest` · `wrty.csv` |

## 16. Webhook: sottoscrizioni, consegne, gestione (R36, R39, R41–R43, R46)

**Regola.** Sottoscrizione a tipi di fatto, firma, ritentativi, registro consegne (F-WBH-01, BO-23); `webhook` con `fact_types text[]`, `enabled` (engagement §2); il fatto consumato genera le consegne (engagement §4); `message.delivered` mai consegnato (engagement §5); accettazione «webhook di test verso un endpoint che risponde 500 → 3 ritenti pianificati, stato finale `GAVE_UP`, firma verificabile» (engagement §7; docs/12 §M7). «il `secret` si legge solo alla creazione» (engagement §3). Evento di prova (Q-100), endpoint in più (Q-103), `webhook.write` solo ADMIN (docs/08 §2, senza ●: il rifiuto degli altri ruoli è **AMBIGUO**), `/v1/demo/**` solo ADMIN (docs/06 §3).

**Strategia.** Abbinamento: tabella **completa** sottoscritto (2) × attivo (2) × doppio invio (2) = 8. Consegne: la sequenza dei ritenti è una macchina a stati provata passo per passo (casi ordinati `@Order`), poi ogni ramo della Riprova, l'evento di prova, le validazioni (guasto singolo) e i ruoli (7 classi di attore sulla creazione, più prova, Riprova, eliminazione e modifica).

### 16.1 Sottoscrizioni (WSUB)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WSUB-001 | tipo sottoscritto · webhook attivo · fatto inviato una volta | 1 consegna `PENDING` | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-002 | tipo sottoscritto · webhook attivo · fatto inviato due volte | 1 consegna `PENDING` | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-003 | tipo sottoscritto · webhook disattivato · fatto inviato una volta | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-004 | tipo sottoscritto · webhook disattivato · fatto inviato due volte | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-005 | tipo non sottoscritto · webhook attivo · fatto inviato una volta | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-006 | tipo non sottoscritto · webhook attivo · fatto inviato due volte | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-007 | tipo non sottoscritto · webhook disattivato · fatto inviato una volta | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-008 | tipo non sottoscritto · webhook disattivato · fatto inviato due volte | nessuna consegna | F-WBH-01; engagement §2, §5; BO-23 | `TestbookEngWebhookIT` · `wsub.csv` |
| TB-ENG-WSUB-009 | fatto `message.delivered` | nessuna consegna | engagement §5 (mai oggetto di webhook) | `TestbookEngWebhookIT`#messageDeliveredNeverDelivered |
| TB-ENG-WSUB-010 | due webhook attivi sottoscritti allo stesso tipo | una consegna per ciascuno | F-WBH-01 | `TestbookEngWebhookIT`#twoWebhooksSameType |
| TB-ENG-WSUB-011 | consegna creata dal fatto | `PENDING`, tentativo 0, corpo = CloudEvent originale firmato, nessuna chiamata HTTP dal consumer | engagement §5 (corpo = CloudEvent originale) | `TestbookEngWebhookIT`#deliveryIsOriginalEvent |

### 16.2 Consegne e gestione (WDLV)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WDLV-001 | invio di una consegna `PENDING` a un endpoint che risponde 204 | `OK`, tentativo 1; header `X-LH-Signature` verificabile col segreto, `X-LH-Event-Id`, `X-LH-Delivery-Id` | engagement §5; F-WBH-01 | `TestbookEngWebhookIT`#sendSigned |
| TB-ENG-WDLV-002 | endpoint che risponde 500: primo tentativo | `FAILED`, prossimo tentativo a +1 min | engagement §5, §7 | `TestbookEngWebhookIT`#retryFirstAttempt |
| TB-ENG-WDLV-003 | giro dello scheduler 1 s prima della scadenza | nessun tentativo | engagement §5 (ritenti a 1, 5, 15 min) | `TestbookEngWebhookIT`#retryNotDueYet |
| TB-ENG-WDLV-004 | giro alla scadenza (+1 min) | tentativo 2, `FAILED`, prossimo a +5 min | engagement §5 | `TestbookEngWebhookIT`#retrySecondAttempt |
| TB-ENG-WDLV-005 | giro alla scadenza successiva | tentativo 3, `FAILED`, prossimo a +15 min | engagement §5 | `TestbookEngWebhookIT`#retryThirdAttempt |
| TB-ENG-WDLV-006 | giro dopo altri 15 min | tentativo 4, `GAVE_UP`, nessun prossimo | engagement §5, §7 (3 ritenti poi GAVE_UP) | `TestbookEngWebhookIT`#retryGaveUp |
| TB-ENG-WDLV-007 | giro un giorno dopo il `GAVE_UP` | nessun altro tentativo | engagement §5 | `TestbookEngWebhookIT`#retryNoMoreAfterGaveUp |
| TB-ENG-WDLV-008 | i 4 tentativi della stessa consegna | stesso corpo, stesso evento, firma verificabile | engagement §5, §7 (firma verificabile) | `TestbookEngWebhookIT`#retrySameBodyAndSignature |
| TB-ENG-WDLV-009 | *Riprova* su `GAVE_UP` con endpoint ancora in errore | tentativo 5, di nuovo `GAVE_UP`, nessun nuovo ciclo | Q-98 | `TestbookEngWebhookIT`#manualRetryAfterGaveUpFails |
| TB-ENG-WDLV-010 | *Riprova* su `GAVE_UP` con endpoint tornato su | `OK` | Q-98; BO-23 | `TestbookEngWebhookIT`#manualRetryAfterGaveUpSucceeds |
| TB-ENG-WDLV-011 | *Riprova* su `FAILED` (tentativo 1) ancora in errore | tentativo 2, `FAILED`, prossimo a +5 min | Q-98 (stessa tabella dei ritenti) | `TestbookEngWebhookIT`#manualRetryOnFailed |
| TB-ENG-WDLV-012 | *Riprova* su `OK` | `409 DELIVERY_NOT_RETRYABLE` | Q-98; BO-23 | `TestbookEngWebhookIT`#manualRetryOnOk |
| TB-ENG-WDLV-013 | *Riprova* su `PENDING` | **AMBIGUO** — `409 DELIVERY_NOT_RETRYABLE` | Q-98 | `TestbookEngWebhookIT`#manualRetryOnPending |
| TB-ENG-WDLV-014 | *Riprova* su una consegna inesistente | `404` | docs/06 §2 | `TestbookEngWebhookIT`#manualRetryUnknown |
| TB-ENG-WDLV-015 | endpoint che risponde 302 | `FAILED` con stato 302, redirect non seguito | Q-98 | `TestbookEngWebhookIT`#redirectIsFailure |
| TB-ENG-WDLV-016 | *Invia evento di prova* | `201`, consegna `test`, esempio del contratto del primo tipo, `lhactor` di chi invia | BO-23; Q-100; Q-103 | `TestbookEngWebhookIT`#testEvent |
| TB-ENG-WDLV-017 | *Invia evento di prova* su un webhook disattivato | **AMBIGUO** — `201`, inviato comunque | BO-23 | `TestbookEngWebhookIT`#testEventDisabled |
| TB-ENG-WDLV-018 | segreto nella risposta di creazione e nelle letture | presente solo nella creazione | engagement §3 (secret solo alla creazione); BO-23 | `TestbookEngWebhookIT`#secretOnlyOnCreate |
| TB-ENG-WDLV-019 | tipo di fatto sconosciuto | `422`, campo `factTypes` | BO-23 (tipi dal catalogo fatti) | `TestbookEngWebhookIT`#unknownFactType |
| TB-ENG-WDLV-020 | sottoscrizione a `message.delivered` | `422`, campo `factTypes` | engagement §5 | `TestbookEngWebhookIT`#subscribeMessageDelivered |
| TB-ENG-WDLV-021 | nessun tipo di fatto | **AMBIGUO** — `422`, campo `factTypes` | F-WBH-01 (sottoscrizione a tipi di fatto) | `TestbookEngWebhookIT`#noFactTypes |
| TB-ENG-WDLV-022 | URL `http://` pubblico | `422`, campo `url` | engagement §5 (solo https) | `TestbookEngWebhookIT`#httpUrlRejected |
| TB-ENG-WDLV-023 | nome assente | **AMBIGUO** — `422`, campo `name` | engagement §2 | `TestbookEngWebhookIT`#nameRequired |
| TB-ENG-WDLV-024 | disattivazione con versione superata | `409 VERSION_CONFLICT` | docs/06 §4 | `TestbookEngWebhookIT`#versionConflict |
| TB-ENG-WDLV-025 | eliminazione | `204`, poi `404`; registro consegne eliminato | engagement §3 (DELETE) | `TestbookEngWebhookIT`#deleteWebhook |
| TB-ENG-WDLV-026 | registro consegne con `status` sconosciuto | `400` | docs/06 §2 | `TestbookEngWebhookIT`#deliveriesBadStatus |
| TB-ENG-WDLV-027 | job demo `deliver-webhooks` con ruolo `MARKETING` | `403` | docs/06 §3 (/v1/demo/** ⇒ ADMIN) | `TestbookEngWebhookIT`#demoJobAdminOnly |
| TB-ENG-WDLV-028 | codice già usato | `409 CODE_TAKEN` | docs/06 §2 | `TestbookEngWebhookIT`#webhookCodeTaken |
| TB-ENG-WDLV-029 | modifica che cambia il codice | **AMBIGUO** — `409 CODE_IMMUTABLE` | docs/06 §2 | `TestbookEngWebhookIT`#webhookCodeImmutable |
| TB-ENG-WDLV-030 | codice non valido `wh bad` | `422 WEBHOOK_INVALID`, campo `code` | docs/06 §2 (^[A-Z][A-Z0-9-]{2,39}$) | `TestbookEngWebhookIT`#webhookInvalidCode |
| TB-ENG-WDLV-031 | *Riprova* su una consegna già in invio | **AMBIGUO** — `409 DELIVERY_BUSY` | Q-98 | `TestbookEngWebhookIT`#manualRetryBusy |

### 16.3 Ruoli (WROL)
| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ENG-WROL-001 | `POST /v1/webhooks` con ADMIN | `201` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-002 | `POST /v1/webhooks` con MARKETING | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-003 | `POST /v1/webhooks` con LEGAL | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-004 | `POST /v1/webhooks` con CARE | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-005 | `POST /v1/webhooks` con ANALYST | `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-006 | `POST /v1/webhooks` con intestazione assente | `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-007 | `POST /v1/webhooks` con intestazione non valida `PIPPO:x` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`: solo ADMIN); docs/06 §3 | `TestbookEngWebhookIT` · `wrol.csv` |
| TB-ENG-WROL-008 | *Invia evento di prova* con `MARKETING` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 (`webhook.write`) | `TestbookEngWebhookIT`#testEventMarketing |
| TB-ENG-WROL-009 | *Riprova* con `CARE` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 | `TestbookEngWebhookIT`#retryCare |
| TB-ENG-WROL-010 | `DELETE /v1/webhooks/{id}` con `MARKETING` | **AMBIGUO** — `403 FORBIDDEN_ROLE` | docs/08 §2 | `TestbookEngWebhookIT`#deleteMarketing |
| TB-ENG-WROL-011 | `PUT /v1/webhooks/{id}` con `ANALYST` | `403 FORBIDDEN_ROLE` | docs/06 §3 (scritture di ANALYST rifiutate) | `TestbookEngWebhookIT`#updateAnalyst |

## 17. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate (§1) | 47 (R01–R47) |
| Regole non implementate | nessuna (R11 e R17 completate con la correzione delle divergenze, §17.4) |
| Rami del codice mappati (§2) | 143 voci; 43 rami senza specifica; 7 rami non coperti da righe |
| Righe del testbook | 804 (unit 395, integrazione 409) |
| Righe AMBIGUO | 132 |
| Divergenze | nessuna aperta (7 righe, 3 cause, risolte: §17.1) |
| Tabelle complete | SEL (60), AUD (27 + 9), POP (24), LIF (50), ROL (28), RUL (60), WSUB (8), WROL (7) |
| Combinazioni ridotte | EDT (guasto singolo su ~10⁶ → 59), EDL (un campo alla volta → 10), EXT/CAL/ORD (limiti da soli), TPL/TPV (classi e limiti da soli), CND (14 comparatori × 3 + 29 casi speciali), THM/THA (classi e confini da soli), WURL (intervalli × confini da soli → 56), WRTY (tentativi e confini 2xx → 23), IBX, TAD, RAD, DDP, DUP, PRV, POPA (una riga per ramo) |

### 17.1 Divergenze (risolte)

Le tre cause sono state corrette nel codice di produzione, senza toccare i test: le 7 righe sono verdi. Le righe di codice della colonna *Causa* sono quelle di prima della correzione.

| Righe (TB-ENG-…) | Specifica | Causa (prima della correzione) | Correzione |
|---|---|---|---|
| EXT-002 | Q-71 applica ai pop-up `registeredWithinDays`, che traduce «iscritti da < 7 giorni» (docs/10 §7): a 7 giorni esatti il membro è fuori | `ContentSelection.java:129` confrontava `registeredAt.isBefore(now − 7 g)`: l'istante esatto passava | `ContentSelection.inAudience` :129–131: fuori se `registeredAt` non è dopo `now − N g` |
| EDL-006…010 | docs/03 §3.6 «un oggetto `LIVE` si modifica solo nei campi "sicuri" (nome, descrizione, `endAt`, priorità, immagine); per il resto va duplicato»; docs/06 §2 `409` «modifica non ammessa su oggetto `LIVE`» | `ContentService.java:206-229`: `update` rifiutava solo `ARCHIVED` (:208), codice (:211) e tipo (:214); `validated` (:310) ricostruiva tutti i campi | `ContentService.update` :224–230 e `lockedChanges` :400–427: su un `LIVE` un cambio di posizionamento, pubblico, `startAt`, collegamento, CTA, frequenza, chiudibile o stile ⇒ `409 CONTENT_LIVE_LOCKED` (stessa forma di `CAMPAIGN_LIVE_LOCKED`); BO-18 mette in sola lettura i campi non sicuri di un `LIVE` e li rimanda invariati (`web/lib/content/manage.ts` `liveSafeBody`) |
| PRV-009 | docs/06 §2 «portale (`/v1/portal/**` — sempre con `memberId` esplicito)», `400` per parametri errati | `PortalContentController.java` (`memberId` facoltativo) e `ContentService.java:289-291` (`viewer` con membro assente → spettatore sconosciuto) | `ContentService.portal` :128–130: `memberId` assente ⇒ `400` RFC 9457; i chiamanti del web (`ContentSlot`, esito `WIN` di PT-06) lo passano già |

Sono le tre voci da riportare nel registro delle divergenze di `docs/16` §12 all'inserimento del dominio, come risolte.

### 17.2 Ambiguità (AMBIGUO, comportamento attuale asserito)

| Righe | Punto aperto | Comportamento attuale | docs/15 |
|---|---|---|---|
| AUD-006, -008, -012, -015…018, -020, -022…024, -026 | livelli, segmenti e stati in AND o in OR (docs/17 US-E07-03 ⚠) | AND: basta una dimensione non soddisfatta per escludere | Q-161 |
| CAL-001, CAL-004, END-005 | istante esatto di inizio/fine del calendario ed `end_at` "passato" all'istante | inizio incluso, fine esclusa, job che chiude all'istante | Q-170 |
| ORD-002 | spareggio a parità di priorità | codice crescente | Q-171 |
| EXT-005, EXT-016 | iscrizione ignota con `registeredWithinDays`; chiavi Q-71 applicate anche alle card | fuori dal pubblico; applicate a ogni contenuto | Q-172 |
| LIF-009/010, -019/020, -029/030, -039/040, -049/050 | azione sconosciuta o assente: `409` o `400` | `409 INVALID_TRANSITION` | Q-173 |
| EDT-012, -017…019, -025, -033, -037…045, -047, -052, -055…057, -060 | vincoli senza fonte (banner solo `CATALOG_TOP`, `PRIZE` ⇔ `WIN`, `linkCode` obbligatorio, lunghezze 80/280, priorità 0…1000 e 50 di default, fine = inizio, CTA `http://`, codice minuscolo, codice/tipo immutabili, `ARCHIVED` ed `ENDED` non modificabili, `PAUSED` bloccato come `LIVE`, chiudibile di default) | come nel codice (§2); `ENDED` → `409 CONTENT_NOT_EDITABLE` e `PAUSED` → `409 CONTENT_LIVE_LOCKED` (Q-174 DECISA) | Q-174 |
| DUP-002, DUP-006, PRV-011, PRV-012 | nome della copia, troncamento del codice; forma della risposta del portale; anteprima `WIN` senza limite | `-COPIA`, `-COPIA-2`, "(copia)"; niente pubblico/stato/versione; tutte le card vincita | Q-175 |
| ROL (LEGAL, CARE, intestazione non valida), WROL-002…004, -007…010 | capacità senza ● in docs/08 §2 | `403 FORBIDDEN_ROLE`; intestazione non valida = `ANALYST` | Q-176 |
| TPL-007, -008, -010, -014, -017, -027, -028, -030, -038…040, TPV-007 | valori non semplici, indici, spazi, template nullo, HTML, cifre decimali e arrotondamento, ripieghi dei formattatori | vuoto; indice risolto; spazi ammessi; stringa vuota; nessun escape; 2 decimali al pari; valore grezzo | Q-177 |
| TAD-004, TAD-010, TAD-014, RAD-012, WDLV-029 | canale di default, nome obbligatorio, codice immutabile di template, regole e webhook | `INAPP`; `422`; `409 CODE_IMMUTABLE` | Q-178 |
| CND-041, -044, -045, -049, -063…065 | `null` come assente, estremi di `between`, stringa numerica, gruppi vuoti, comparatore sconosciuto | assente; estremi inclusi; convertita; vero; falso | Q-179 |
| RUL con membro `INACTIVE` o senza snapshot (4 righe) | Q-70 nomina solo `ANONYMIZED` e `BLOCKED` | `INACTIVE` nessun messaggio, senza snapshot riceve (Q-180 DECISA) | Q-180 |
| RAD-002, DDP-014 | tipo di fatto in forma completa; `message.send` senza membro | salvato in forma breve; DLQ `INVALID_EFFECT` | Q-181 |
| IBX-005, IBX-014 | segna letto due volte; `memberId` nel corpo | `readAt` invariato; accettato | Q-182 |
| THM-005…007, THA-012, THA-020…023 | colori senza `#`, `#RGB`, `#RRGGBBAA`; nome del programma obbligatorio e ≤ 40; logo solo `/…` o `https://` | rifiutati; `422`; `422`; `/demo/logo.svg` ammesso | Q-183 |
| WURL-025, -026, -029…032, -045…051, -057…062 | intervalli speciali oltre privati/loopback, nomi `.local`/`.internal`, forma decimale al salvataggio, credenziali, frammento, 500 caratteri | bloccati; host numerico non in forma puntata canonica rifiutato al salvataggio (Q-184 DECISA); rifiutati; 500 ammessi, 501 no | Q-184 |
| WRTY-009, WRTY-020, WDLV-013, WDLV-017, WDLV-021, WDLV-023, WDLV-031 | tentativo 0; Riprova su `PENDING`; prova su webhook disattivato; nessun tipo; nome obbligatorio; Riprova durante un invio | `GAVE_UP`; `409`; inviata; `422`; `422`; `409 DELIVERY_BUSY` | Q-185 |

### 17.3 Rami senza specifica

Provati come **AMBIGUO** (§17.2): AND tra le dimensioni del pubblico; spareggio per codice; banner solo in `CATALOG_TOP`; `PRIZE` ⇔ `WIN`; `linkCode` obbligatorio; CTA solo `/portal…` o `https://`; titolo ≤ 80 e testo ≤ 280; priorità 0…1000, default 50; frequenza `ONCE` e chiudibile di default; codice in maiuscolo; codice e tipo immutabili; `ARCHIVED` non modificabile; nome e troncamento della copia; anteprima `WIN` senza limite; canale `INAPP` di default e nome del template obbligatorio; tipo di fatto in forma completa; `|number`/`|date` su valori non validi, formattatore sconosciuto in resa, due decimali; indici di array nei percorsi; gruppi vuoti, comparatore sconosciuto, stringhe numeriche; `readAt` invariato e `memberId` nel corpo; lunghezza, credenziali, frammento e nomi `.local`/`.internal` degli URL; intervalli speciali bloccati; Riprova su `PENDING`; prova su webhook disattivato; webhook senza tipi o senza nome; nome del programma obbligatorio e ≤ 40, logo; codici immutabili di regole, template e webhook; `DELIVERY_BUSY`; `ENDED` modificabile; `message.send` senza membro.

Non coperti da righe: `PUT` senza `version` (nessun controllo di versione, `ContentService.java:222` e analoghi); ripiego Aurora senza riga (`ThemeService.java:42`); template inesistente a runtime (`NotificationService.java:52`, impedito dalla FK); host sconosciuto ed errori di rete all'invio (`WebhookHttpSender.java:79`, `:100-108`); `asOf` come data pura del job demo (`EngagementJobsController.java:57`); liste del pubblico non array (`ContentSelection.java:140`); evento non di tipo fatto in `WebhookService.enqueue` (:93, non raggiungibile dal consumer dei fatti); scheduler automatico ogni 30 s e il suo interruttore (Q-101: nei test lo scheduler è spento e i giri sono lanciati con l'istante voluto).

### 17.4 Regole non implementate

Nessuna. Prima della correzione delle divergenze (§17.1):

- **R11** — docs/03 §3.6, campi sicuri di un oggetto `LIVE`: nessun controllo in `ContentService.update` (righe EDL-006…010); ora `409 CONTENT_LIVE_LOCKED`.
- **R17 (parziale)** — docs/06 §2, portale sempre con `memberId`: `GET /v1/portal/content` lo accettava assente (PRV-009); ora `400` come gli altri endpoint del portale.

### 17.5 Controlli di mutazione

Per ogni classe si è rotta temporaneamente una regola di produzione, eseguita la classe e ripristinato il file (nessuna modifica di produzione a fine lavoro):

| Classe | Mutazione | Righe diventate rosse |
|---|---|---|
| `TestbookEngContentSelectionTest` | `ContentSelection.frequencyAllows`: `lastSeen.isBefore(today)` → `!lastSeen.isAfter(today)` | POP-011, -012, -025, -027, -029, -031, -034 |
| `TestbookEngTemplateTest` | `TemplateEngine.number`: formato `#,##0.##` → `#0.##` | TPL-021…024, TPL-029 |
| `TestbookEngConditionTest` | `DataCondition`: `gte` con `c >= 0` → `c > 0` | CND-010 |
| `TestbookEngThemeTest` | `Theme.HEX`: `{6}` → `{3,6}` | THM-006, THM-009 |
| `TestbookEngWebhookTest` | `WebhookRetry.DELAYS`: 5 min → 6 min | WRTY-002 |
| `TestbookEngContentIT` | `ContentService.TRANSITIONS`: `RESUME` senza `PAUSED → LIVE` | LIF-023 |
| `TestbookEngMessagingIT` | `InboxService.deliver`: esclusi i `BLOCKED` invece degli `ANONYMIZED` | RUL-003, -004, -013, -014, DDP-011 |
| `TestbookEngWebhookIT` | `WebhookRepository.findEnabledFor`: tolto il filtro `enabled` | WSUB-003, WSUB-004 |

### 17.6 Storie di docs/17 coperte

| Storia | Criteri | Righe |
|---|---|---|
| US-E07-01 Creare card, pop-up e banner | 201 in `DRAFT`; 422 `CONTENT_INVALID`, 409 codice/tipo/versione/non modificabile; CARE 403 | EDT, EDL, ROL-004 |
| US-E07-02 Pubblicare senza approvazione | `DRAFT → LIVE` e fatto; `SUBMIT`/`APPROVE` 409; fine automatica | LIF, END |
| US-E07-03 Cosa vede un membro | accettazione tiers GOLD/PLATINUM; più di 6 card; ⚠ AND tra dimensioni | PRV-001, ORD-007…009, AUD (AMBIGUO) |
| US-E07-04 Un pop-up alla volta | `ONCE`, `ONCE_PER_DAY` col giorno di Roma; 204; lettura non consumante; `POP-WEEKEND` di martedì | POP, POPA, EXT-007…015 |
| US-E07-05 Anteprima per membro | i quattro motivi di esclusione | SEL, PRV-002…004, PRV-001 |
| US-E07-06 Card di vincita | card del premio vinto (il ripiego generico è del portale, TB-WEB) | PRV-007, PRV-012 |
| US-E07-07 Template | `|number` 1500 → «1.500»; percorso assente → vuoto; 422 `TEMPLATE_INVALID` | TPL, TPV, TAD, RND |
| US-E07-08 Regole di notifica | condizione `data.currency = PTS`; 422 su `message.delivered` e tipi sconosciuti; regola disabilitata | RUL, CND, RAD |
| US-E07-09 Messaggi in inbox | «Hai guadagnato 162 punti», nessun duplicato; BLOCKED sì, ANONYMIZED no; `EMAIL_FAKE` solo registro; `message.delivered` ignorato | TPL-001, DDP, RUL |
| US-E07-10 Messaggio da campagna | dedup su `effectId`; DLQ `TEMPLATE_NOT_FOUND` | DDP-007…011, DDP-014 |
| US-E07-11 Leggere le notifiche | non letti, *Segna tutte*; 404 per un altro membro (la campanella ogni 30 s è del portale, TB-WEB) | IBX |
| US-E07-12 Tema a runtime | nuovo `primary` restituito; 422 `THEME_CONTRAST_TOO_LOW` / `THEME_INVALID` (i colori di ripiego del portale sono TB-WEB) | THM, THA |
| US-E08-08 Configurare un webhook | segreto una volta; 422 per `http://`, privato, `.local`, `message.delivered`, nessun tipo; MARKETING 403 | WDLV-018…023, WURL, WROL |
| US-E08-09 Consegna firmata e ritentativi | 500 → 1, 5, 15 min → `GAVE_UP`, firma verificabile; 302 fallimento; webhook disabilitato; evento di prova | WDLV-001…008, WDLV-015…017, WSUB, WPRF-008, WPRF-009 |
| US-E08-10 Riprovare una consegna | `GAVE_UP` → `OK`; 409 su `OK`/`PENDING`/in invio; fallita dopo `GAVE_UP` resta `GAVE_UP` | WDLV-009…014, WDLV-031, WRTY |
| US-E02-05 (parte engagement) Anonimizzazione | nome tolto da messaggi e consegne, snapshot `ANONYMIZED` | SNP-008, SNP-009 |

Nodi della foresta di docs/17 §5.8: ENG-01…04 → SEL, CAL, AUD, EXT, ORD, POP, POPA; ENG-05, ENG-25 → EDT, EDL; ENG-06, ENG-07 → LIF, END, AUDT; ENG-08 → DDP-006, WSUB-009, SNP; ENG-09, ENG-10 → RUL, DDP, IBX; ENG-11 → DDP-007…010, DDP-014; ENG-12, ENG-13 → TPL, TPV, TAD, RND; ENG-14 → RAD; ENG-15 → THM, THA; ENG-16 → IBX; ENG-17 → WURL, WPRF; ENG-18 → WDLV-018…023, WDLV-028…030; ENG-19 → WSUB (evento non di tipo fatto non raggiungibile); ENG-20 → WRTY, WDLV-001…008, WPRF-008, WPRF-009; ENG-21 → WDLV-009…014, WDLV-031; ENG-22 → WDLV-016, WDLV-017; ENG-23 → CLN (scheduler ogni 30 s non coperto); ENG-24 → ROL, WROL, RND-004.

