# TB-WEB — Interfaccia (backoffice e portale)

Testbook funzionale del dominio **Interfaccia** (`docs/16 §10`): la logica che vive nell'app `web/` — permessi e
navigazione del backoffice, identità simulata e proxy, stati delle viste, barra del ciclo di vita e approvazioni, frase
generata e costruttore di condizioni di BO-06, formati, catalogo e home del portale, conflitti di versione, conferme,
Demo Hub e keep-alive. Metodo ed esecuzione: `docs/16 §1` e `§1bis`.

- **Oracolo** = la specifica: `docs/07` (fondamenta), `docs/08` (BO-*, matrice §2, pattern §3), `docs/09` (PT-*),
  `docs/06 §3` e `§7`, `docs/03 §2`, `§3.3–3.6`, `§4`, `§5`, e le scelte registrate in `docs/15` (citate `Q-nn`). Mai il
  codice: dove una tabella della spec esiste (matrice dei permessi, voci della sidebar, stati della barra) è **trascritta
  nel test** e confrontata con quella del codice.
- **Esecuzione**: file vitest `*.testbook.test.ts(x)` accanto al codice, un caso per riga, nome che inizia con
  `[TB-WEB-<AREA>-NNN]`; tabelle `it.each` per le combinazioni (tuple `[id, descrizione, caso]` di `web/test/testbook.ts`).
  Comando: `cd web && pnpm -s exec vitest run testbook`, oppure `TESTBOOK_JAVA=0 bash scripts/testbook.sh`.
- **Legenda della colonna «atteso»**: **DIVERGENZA** = il test asserisce la specifica ed è rosso (registro in fondo);
  ~~DIVERGENZA~~ risolta = il codice di produzione è stato allineato alla specifica e il test, invariato, è verde (§22);
  AMBIGUO = la specifica tace e nessuna `Q-nn` decide: il test fissa il comportamento attuale con il commento
  `// TESTBOOK: ambiguo, vedi <ID>`.
- **Perimetro**: funzioni pure di `web/lib/**`, componenti condivisi (`components/bo`, `components/shared`), route
  handler (`app/api/**`) e la home del portale (PT-01) resa con i dati simulati. Le regole che il web si limita a mostrare
  (visibilità dei premi per finestra di validità, calcolo di tier e saldi) sono dei servizi e stanno nei rispettivi
  domini (`TB-RWD`, `TB-WAL`): il web non ha rami su quei dati.


## 1. PERM — Matrice ruolo × capacità

**Regole**
- R1 (docs/08 §2) Tabella ruolo × capacità: 19 capacità, `edition.close` con due colonne (anteprima / applica). La UI
  nasconde o disabilita; il backend rifiuta comunque (●).
- R2 (docs/08 §2, riga `instants.view`) MARKETING vede *solo l'istogramma*: la tabella degli istanti gli è negata.
- R3 (Q-52) `coupon.use` (cassa simulata di BO-12) a ADMIN, MARKETING, LEGAL, CARE, mai ANALYST.
- R4 (docs/08 §2, docs/06 §3) ANALYST è in sola lettura; un ruolo fuori dai cinque non ha capacità.

**Domini**

| Ingresso | Classi valide | Non valide | Note |
|---|---|---|---|
| ruolo | ADMIN, MARKETING, LEGAL, CARE, ANALYST | ruolo sconosciuto (`SUPERUSER`) | enumerato completo + 1 sconosciuto |
| capacità | le 19 di docs/08 §2 (+ `coupon.use` di Q-52), `edition.close` sdoppiata | — | 21 colonne |

**Strategia**: tabella decisionale **completa** 21 capacità × 5 ruoli = 105 righe (> 64, ma ogni cella è una regola della
matrice: nessuna riduzione), più il ruolo sconosciuto (1) e la sola lettura per ruolo (5). L'oracolo è la matrice di
docs/08 §2 trascritta nel test, non la `MATRIX` di `permissions.ts`.

**Rami del codice** (`lib/persona/permissions.ts`, `app/backoffice/program/currencies/page.tsx`): 19 righe di `MATRIX`
(una per capacità, tutte mappate su R1–R3); `isReadOnly` (R4); `requiredRoleHint` (docs/07 §4, vedi CAN); gate inline di
BO-08 — *Anteprima chiusura* senza gate, *Applica chiusura* dentro `program.config` (R1, colonna `edition.close`).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-PERM-001 | ADMIN × instants.view | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-002 | MARKETING × instants.view | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-003 | LEGAL × instants.view | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-004 | CARE × instants.view | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-005 | ANALYST × instants.view | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-006 | ADMIN × member.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-007 | MARKETING × member.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-008 | LEGAL × member.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-009 | CARE × member.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-010 | ANALYST × member.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-011 | ADMIN × member.anonymize | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-012 | MARKETING × member.anonymize | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-013 | LEGAL × member.anonymize | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-014 | CARE × member.anonymize | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-015 | ANALYST × member.anonymize | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-016 | ADMIN × points.adjust | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-017 | MARKETING × points.adjust | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-018 | LEGAL × points.adjust | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-019 | CARE × points.adjust | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-020 | ANALYST × points.adjust | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-021 | ADMIN × segment.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-022 | MARKETING × segment.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-023 | LEGAL × segment.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-024 | CARE × segment.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-025 | ANALYST × segment.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-026 | ADMIN × object.edit | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-027 | MARKETING × object.edit | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-028 | LEGAL × object.edit | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-029 | CARE × object.edit | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-030 | ANALYST × object.edit | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-031 | ADMIN × object.approve | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-032 | MARKETING × object.approve | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-033 | LEGAL × object.approve | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-034 | CARE × object.approve | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-035 | ANALYST × object.approve | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-036 | ADMIN × content.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-037 | MARKETING × content.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-038 | LEGAL × content.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-039 | CARE × content.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-040 | ANALYST × content.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-041 | ADMIN × program.config | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-042 | MARKETING × program.config | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-043 | LEGAL × program.config | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-044 | CARE × program.config | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-045 | ANALYST × program.config | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-046 | ADMIN × actiontype.custom | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-047 | MARKETING × actiontype.custom | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-048 | LEGAL × actiontype.custom | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-049 | CARE × actiontype.custom | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-050 | ANALYST × actiontype.custom | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-051 | ADMIN × edition.close:preview | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-052 | MARKETING × edition.close:preview | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-053 | LEGAL × edition.close:preview | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-054 | CARE × edition.close:preview | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-055 | ANALYST × edition.close:preview | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-056 | ADMIN × edition.close:apply | true | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-057 | MARKETING × edition.close:apply | false | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-058 | LEGAL × edition.close:apply | false | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-059 | CARE × edition.close:apply | false | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-060 | ANALYST × edition.close:apply | false | docs/08 §2 (`edition.close`) · BO-08 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-061 | ADMIN × redemption.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-062 | MARKETING × redemption.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-063 | LEGAL × redemption.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-064 | CARE × redemption.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-065 | ANALYST × redemption.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-066 | ADMIN × delivery.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-067 | MARKETING × delivery.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-068 | LEGAL × delivery.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-069 | CARE × delivery.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-070 | ANALYST × delivery.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-071 | ADMIN × coupon.void | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-072 | MARKETING × coupon.void | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-073 | LEGAL × coupon.void | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-074 | CARE × coupon.void | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-075 | ANALYST × coupon.void | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-076 | ADMIN × inbound.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-077 | MARKETING × inbound.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-078 | LEGAL × inbound.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-079 | CARE × inbound.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-080 | ANALYST × inbound.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-081 | ADMIN × webhook.write | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-082 | MARKETING × webhook.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-083 | LEGAL × webhook.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-084 | CARE × webhook.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-085 | ANALYST × webhook.write | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-086 | ADMIN × dlq.handle | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-087 | MARKETING × dlq.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-088 | LEGAL × dlq.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-089 | CARE × dlq.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-090 | ANALYST × dlq.handle | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-091 | ADMIN × demo.simulate | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-092 | MARKETING × demo.simulate | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-093 | LEGAL × demo.simulate | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-094 | CARE × demo.simulate | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-095 | ANALYST × demo.simulate | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-096 | ADMIN × demo.admin | true | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-097 | MARKETING × demo.admin | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-098 | LEGAL × demo.admin | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-099 | CARE × demo.admin | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-100 | ANALYST × demo.admin | false | docs/08 §2 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-101 | ADMIN × coupon.use | true | docs/08 §2 · Q-52 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-102 | MARKETING × coupon.use | true | docs/08 §2 · Q-52 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-103 | LEGAL × coupon.use | true | docs/08 §2 · Q-52 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-104 | CARE × coupon.use | true | docs/08 §2 · Q-52 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-105 | ANALYST × coupon.use | false | docs/08 §2 · Q-52 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-106 | ruolo sconosciuto (SUPERUSER) × object.edit | false | docs/08 §2 · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-107 | sola lettura per ADMIN | false | docs/08 §2 (ANALYST) · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-108 | sola lettura per MARKETING | false | docs/08 §2 (ANALYST) · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-109 | sola lettura per LEGAL | false | docs/08 §2 (ANALYST) · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-110 | sola lettura per CARE | false | docs/08 §2 (ANALYST) · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-111 | sola lettura per ANALYST | true | docs/08 §2 (ANALYST) · docs/06 §3 | `web/lib/persona/permissions.testbook.test.ts` |


## 2. CAN — Azioni nascoste o disabilitate

**Regole**
- R1 (docs/08 §2, docs/07 §4) `<Can>` **nasconde** se l'azione non ha senso per il ruolo, **disabilita con tooltip** col
  ruolo richiesto se conviene mostrarla ("Approva — richiede ruolo LEGAL").
- R2 (docs/07 §6 Forbidden) azione disabilitata = non eseguibile.

**Domini**: ruolo abilitato / non abilitato × modo `hide` / `disable`.
**Strategia**: completa sulle 3 uscite (abilitato; non abilitato + hide; non abilitato + disable) più la proprietà
"disabilitata davvero" (tastiera) come riga a sé.

**Rami del codice** (`components/bo/Can.tsx`): consentito → figli (R1); `hide` → nulla (R1); `disable` → involucro con
`title` e `pointer-events: none` (R1, R2 → CAN-004).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-CAN-001 | ruolo abilitato (LEGAL × object.approve, modo disable) | azione visibile, senza tooltip di divieto | docs/07 §4 · §6 (Forbidden) · docs/08 §2 | `web/components/bo/Can.testbook.test.tsx` |
| TB-WEB-CAN-002 | ruolo non abilitato, modo hide (default) | azione nascosta | docs/07 §4 · §6 (Forbidden) · docs/08 §2 | `web/components/bo/Can.testbook.test.tsx` |
| TB-WEB-CAN-003 | ruolo non abilitato, modo disable | azione visibile con tooltip «Richiede … LEGAL» | docs/07 §4 · §6 (Forbidden) · docs/08 §2 | `web/components/bo/Can.testbook.test.tsx` |
| TB-WEB-CAN-004 | ruolo non abilitato, modo disable | ~~DIVERGENZA~~ risolta (§22) — il controllo è disabilitato (non attivabile da tastiera) | docs/07 §4 · §6 (Forbidden) · docs/08 §2 | `web/components/bo/Can.testbook.test.tsx` |


## 3. NAV — Sidebar del backoffice

**Regole**
- R1 (docs/08 §1, tabella) ogni voce ha gruppo, etichetta, ID, route e milestone (BO-03 e BO-06 sono pagine di dettaglio).
- R2 (docs/08 §1) una voce la cui milestone non è realizzata **non compare** (mai pagine "in arrivo"); i gruppi vuoti
  spariscono; docs/14: M1–M7 completate.
- R3 (docs/08 §1) voce attiva con barra teal: le sottopagine accendono la voce del loro elenco.
- R4 (docs/08 §1) contatori su *Approvazioni* (oggetti `IN_REVIEW`), *Richieste premio* (da evadere + `needsAttention`),
  *DLQ* (`NEW`, API `OPEN` per Q-105).
- R5 (docs/08 §2) tutte le personas leggono tutto: la sidebar non dipende dal ruolo.

**Domini**

| Ingresso | Classi / valori |
|---|---|
| milestone realizzata | 0 (min − 1), 1 (min), 6 (M7 − 1), 7 (max), 8 (max + 1), default |
| percorso | route esatta, sottopagina, prefisso più lungo, radice, percorso sconosciuto, prefisso senza «/», portale, voce non realizzata |
| ruolo | i 5 ruoli |

**Strategia**: una riga per voce della tabella di docs/08 §1 (28); valori limite della milestone (5 + default);
classi del percorso (10); un caso per contatore (3); un caso per ruolo (5).

**Rami del codice** (`lib/nav.ts`, `components/bo/NavLinks.tsx`): filtro per milestone (R2); gruppo vuoto tolto (R2);
`activeHref` uguaglianza, prefisso con «/», prefisso più lungo (R3); contatori `dlq`, `redemptions`, nessuno (R4).
Ramo senza specifica: un percorso `/backoffice/<qualunque>` senza voce propria accende la Dashboard (NAV-041/042).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-NAV-001 | voce BO-01 | gruppo «Panoramica», etichetta «Dashboard», route /backoffice, milestone M2 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-002 | voce BO-02 | gruppo «Clienti», etichetta «Membri», route /backoffice/members, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-003 | voce BO-04 | gruppo «Clienti», etichetta «Segmenti», route /backoffice/segments, milestone M6 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-004 | voce BO-05 | gruppo «Programma», etichetta «Campagne», route /backoffice/campaigns, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-005 | voce BO-07 | gruppo «Programma», etichetta «Livelli», route /backoffice/program/tiers, milestone M3 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-006 | voce BO-08 | gruppo «Programma», etichetta «Valute ed edizioni», route /backoffice/program/currencies, milestone M3 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-007 | voce BO-09 | gruppo «Programma», etichetta «Azioni e fonti», route /backoffice/program/actions, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-008 | voce BO-10 | gruppo «Premi», etichetta «Catalogo», route /backoffice/rewards, milestone M4 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-009 | voce BO-11 | gruppo «Premi», etichetta «Fasce», route /backoffice/rewards/bands, milestone M4 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-010 | voce BO-12 | gruppo «Premi», etichetta «Coupon», route /backoffice/rewards/coupons, milestone M4 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-011 | voce BO-13 | gruppo «Premi», etichetta «Richieste premio», route /backoffice/rewards/redemptions, milestone M4 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-012 | voce BO-14 | gruppo «Gioco», etichetta «Concorsi», route /backoffice/game/contests, milestone M5 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-013 | voce BO-15 | gruppo «Gioco», etichetta «Obiettivi e badge», route /backoffice/game/achievements, milestone M5 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-014 | voce BO-16 | gruppo «Gioco», etichetta «Classifiche», route /backoffice/game/leaderboards, milestone M5 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-015 | voce BO-17 | gruppo «Gioco», etichetta «Referral», route /backoffice/game/referral, milestone M5 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-016 | voce BO-18 | gruppo «Contenuti», etichetta «Card e pop-up», route /backoffice/content, milestone M6 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-017 | voce BO-19 | gruppo «Contenuti», etichetta «Messaggi», route /backoffice/content/messages, milestone M6 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-018 | voce BO-20 | gruppo «Contenuti», etichetta «Tema e brand», route /backoffice/content/theme, milestone M6 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-019 | voce BO-21 | gruppo «Governance», etichetta «Approvazioni», route /backoffice/governance/approvals, milestone M7 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-020 | voce BO-22 | gruppo «Governance», etichetta «Audit», route /backoffice/governance/audit, milestone M2 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-021 | voce BO-23 | gruppo «Governance», etichetta «Webhook», route /backoffice/governance/webhooks, milestone M7 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-022 | voce BO-24 | ~~DIVERGENZA~~ risolta (§22) — gruppo «Osservabilità», etichetta «Flusso live», route /backoffice/observe/live, milestone M2 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-023 | voce BO-25 | gruppo «Osservabilità», etichetta «Tracciati», route /backoffice/observe/traces, milestone M2 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-024 | voce BO-26 | gruppo «Osservabilità», etichetta «Monitor ingressi», route /backoffice/observe/inbound, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-025 | voce BO-27 | gruppo «Osservabilità», etichetta «DLQ», route /backoffice/observe/dlq, milestone M7 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-026 | voce BO-28 | gruppo «Demo», etichetta «Simulatore eventi», route /backoffice/demo/simulator, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-027 | voce BO-29 | gruppo «Demo», etichetta «Scenari», route /backoffice/demo/scenarios, milestone M2 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-028 | voce BO-30 | gruppo «Demo», etichetta «Console demo», route /backoffice/demo/console, milestone M1 | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-029 | milestone realizzata 0 | nessuna voce né gruppo | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-030 | milestone 1 | solo le 6 voci M1, i gruppi senza voci non compaiono | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-031 | milestone 6 (M7 − 1) | BO-21, BO-23, BO-27 assenti | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-032 | milestone 7 | tutte le 28 voci | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-033 | milestone 8 (oltre l'ultima) | ancora le stesse 28 voci, nessuna in più | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-034 | sidebar di default (M1–M7 realizzate secondo docs/14) | tutte le 28 voci | docs/08 §1 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-035 | voce attiva per /backoffice/members (route esatta) | /backoffice/members | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-036 | voce attiva per /backoffice/members/MBR-000002 (dettaglio BO-03 accende Membri) | /backoffice/members | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-037 | voce attiva per /backoffice/rewards/bands (prefisso più lungo: Fasce, non Catalogo) | /backoffice/rewards/bands | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-038 | voce attiva per /backoffice/content/messages (Messaggi, non Card e pop-up) | /backoffice/content/messages | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-039 | voce attiva per /backoffice/campaigns/new (editor BO-06 accende Campagne) | /backoffice/campaigns | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-040 | voce attiva per /backoffice (radice accende la Dashboard) | /backoffice | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-041 | voce attiva per /backoffice/unknown (sottopagina senza voce) | AMBIGUO — Dashboard | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-042 | voce attiva per /backoffice/membersX (prefisso senza «/») | AMBIGUO — non Membri (Dashboard) | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-043 | voce attiva per /portal/rewards (percorso del portale) | null | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-044 | voce non ancora realizzata non si accende (milestone 1, /backoffice/segments) | nessuna voce attiva (`null`) | docs/08 §1 (voce attiva) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-045 | contatore su Approvazioni (BO-21, oggetti IN_REVIEW) | ~~DIVERGENZA~~ risolta (§22) — contatore presente su *Approvazioni* | docs/08 §1 (contatori) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-046 | contatore su Richieste premio (BO-13) | contatore `redemptions` (da evadere + `needsAttention`) | docs/08 §1 (contatori) | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-047 | contatore su DLQ (BO-27) | contatore `dlq` (voci nuove) | docs/08 §1 (contatori) · Q-105 | `web/lib/nav.testbook.test.ts` |
| TB-WEB-NAV-048 | sidebar per ADMIN | 28 voci, tutte le schermate in lettura | docs/08 §2 («tutte le personas leggono tutto») · §1 | `web/components/bo/NavLinks.testbook.test.tsx` |
| TB-WEB-NAV-049 | sidebar per MARKETING | 28 voci, tutte le schermate in lettura | docs/08 §2 («tutte le personas leggono tutto») · §1 | `web/components/bo/NavLinks.testbook.test.tsx` |
| TB-WEB-NAV-050 | sidebar per LEGAL | 28 voci, tutte le schermate in lettura | docs/08 §2 («tutte le personas leggono tutto») · §1 | `web/components/bo/NavLinks.testbook.test.tsx` |
| TB-WEB-NAV-051 | sidebar per CARE | 28 voci, tutte le schermate in lettura | docs/08 §2 («tutte le personas leggono tutto») · §1 | `web/components/bo/NavLinks.testbook.test.tsx` |
| TB-WEB-NAV-052 | sidebar per ANALYST | 28 voci, tutte le schermate in lettura | docs/08 §2 («tutte le personas leggono tutto») · §1 | `web/components/bo/NavLinks.testbook.test.tsx` |


## 4. PERS — Identità simulata (cookie `lh_persona`, `X-LH-Actor`)

**Regole**
- R1 (docs/07 §4) cookie `lh_persona` JSON, 30 giorni: `{kind: "BO", username, role}` o `{kind: "MEMBER", memberId}`;
  default `marta.admin` nel backoffice, `MBR-000002` nel portale; formati non validi → persona di default.
- R2 (docs/06 §3) `X-LH-Actor: <RUOLO>:<username>`; assente → `ANALYST:anonymous`; gli endpoint `/v1/portal/**` non
  richiedono l'intestazione.
- R3 (docs/07 §4, §8) selettore con le 5 personas (una per ruolo); il cambio persona scrive il cookie.

**Domini**

| Ingresso | Valide | Non valide / speciali |
|---|---|---|
| valore del cookie | BO codificato, BO in chiaro, MEMBER | assente, `null`, vuoto, non JSON, percentuale rotta, elenco, `null` JSON, `kind` ignoto, BO senza `role`/`username`, `role` non testo, ruolo fuori dai 5, MEMBER senza `memberId` o numerico, campi in più |
| corpo del cambio persona | BO noto, BO con ruolo forzato, MEMBER | `kind` assente, MEMBER senza id, non JSON |

**Strategia**: ogni classe non valida provata da sola (guasto singolo), ogni classe valida una volta; andata e ritorno
serializza → interpreta per i due `kind`.

**Rami del codice** (`lib/persona/cookie.ts`, `app/api/persona/route.ts`, layout): `parsePersona` assente / BO / MEMBER /
altro / eccezione (R1); `serializePersona` (R1); `actorHeader` BO / altro (R2); `backofficePersonaFromUsername` trovato
(R3) / non trovato → ANALYST (ramo senza specifica, PERS-028); `POST /api/persona` MEMBER / BO / non valido → 400
`INVALID_PERSONA` (codice senza specifica, PERS-033…035); layout del backoffice: cookie non BO → `marta.admin` (R1).
Ruolo non valido nel cookie: letto come ANALYST, nell'interfaccia e in `X-LH-Actor` (Q-186 DECISA, PERS-015).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-PERS-001 | parsePersona: BO valido (codificato) | `{kind: BO, username: luca.marketing, role: MARKETING}` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-002 | parsePersona: BO valido (JSON non codificato) | `{kind: BO, username: anna.care, role: CARE}` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-003 | parsePersona: MEMBER valido | `{kind: MEMBER, memberId: MBR-000007}` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-004 | parsePersona: cookie assente (undefined) | `null` (vale la persona di default) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-005 | parsePersona: cookie null | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-006 | parsePersona: cookie vuoto | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-007 | parsePersona: testo non JSON | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-008 | parsePersona: codifica percentuale rotta | `null` (nessuna eccezione) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-009 | parsePersona: JSON elenco | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-010 | parsePersona: JSON null | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-011 | parsePersona: kind sconosciuto | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-012 | parsePersona: BO senza role | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-013 | parsePersona: BO con role non testo | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-014 | parsePersona: BO senza username | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-015 | BO con ruolo fuori dai 5 (ROOT, minuscolo) | ruolo ANALYST, anche in `X-LH-Actor` (Q-186 DECISA) | docs/07 §4; Q-186 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-016 | parsePersona: MEMBER senza memberId | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-017 | parsePersona: MEMBER con memberId numerico | `null` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-018 | parsePersona: campi in più ignorati | solo `kind`, `username`, `role` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-019 | serializePersona | parsePersona restituisce la stessa persona (BO) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-020 | serializePersona | parsePersona restituisce la stessa persona (MEMBER) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-021 | X-LH-Actor per persona BO | MARKETING:luca.marketing | docs/06 §3 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-022 | X-LH-Actor per nessuna persona (header assente) | ANALYST:anonymous | docs/06 §3 (assente → ANALYST:anonymous) | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-023 | X-LH-Actor per persona MEMBER (endpoint portale, header non richiesto) | ANALYST:anonymous | docs/06 §3 (portale senza header) | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-024 | persona di default del backoffice: marta.admin (ADMIN) | `{kind: BO, username: marta.admin, role: ADMIN}` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-025 | persona di default del portale: MBR-000002 | `{kind: MEMBER, memberId: MBR-000002}` | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-026 | cookie lh_persona valido 30 giorni | nome `lh_persona`, durata 2 592 000 s | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-027 | username noto (elena.legal) | ruolo dall'elenco delle personas (LEGAL) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-028 | username sconosciuto | AMBIGUO — ANALYST (sola lettura) | docs/07 §4 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-029 | 5 personas del backoffice, una per ruolo | ruoli ADMIN, MARKETING, LEGAL, CARE, ANALYST; 5 username distinti | docs/07 §8 (5 schede persona) · docs/08 §1 | `web/lib/persona/cookie.testbook.test.ts` |
| TB-WEB-PERS-030 | cambio a persona BO nota | cookie con ruolo dall'elenco, Max-Age 30 giorni | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |
| TB-WEB-PERS-031 | il ruolo nel corpo non conta: BO giovanni.analyst con role ADMIN | ANALYST | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |
| TB-WEB-PERS-032 | cambio a membro | cookie MEMBER | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |
| TB-WEB-PERS-033 | corpo non valido (kind assente) | AMBIGUO — 400 INVALID_PERSONA, nessun cookie | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |
| TB-WEB-PERS-034 | corpo non valido (MEMBER senza memberId) | AMBIGUO — 400 INVALID_PERSONA, nessun cookie | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |
| TB-WEB-PERS-035 | corpo non valido (corpo non JSON) | AMBIGUO — 400 INVALID_PERSONA, nessun cookie | docs/07 §4 | `web/app/api/persona/route.testbook.test.ts` |


## 5. PRX — Proxy verso i servizi

**Regole**
- R1 (docs/07 §3) il browser chiama `/api/lh/<service>/v1/...`; il proxy inoltra a `LH_SVC_<SERVICE>_URL` copiando
  metodo, query e corpo, aggiunge `X-LH-Actor` dal cookie e `X-Correlation-Id` (nuovo ULID se assente). Timeout 25 s.
- R2 (docs/07 §3) `502/503/504` o errore di rete → `{type: "SERVICE_ASLEEP", service}` (stato degraded).
- R3 (docs/06 §2) gli altri errori (problemi RFC 9457) passano tali e quali.

**Domini**: metodo GET/POST; cookie BO / assente / non valido; correlazione presente / assente; risposta a valle 200,
422, 500, 502, 503, 504, errore di rete, nessuna risposta (24,999 s / 25 s); servizio noto / sconosciuto.
**Strategia**: ogni classe da sola (guasto singolo); il timeout ai due lati del limite.

**Rami del codice** (`app/api/lh/[service]/[...path]/route.ts`): servizio sconosciuto → 404 `UNKNOWN_SERVICE` (ramo
senza specifica); correlazione presente / generata (R1); corpo solo per metodi diversi da GET/DELETE (R1); 502/503/504 →
`SERVICE_ASLEEP` (R2); altri stati inoltrati con `content-type` (R3); eccezione (rete, timeout) → `SERVICE_ASLEEP` (R2);
timer di 25 s (R1).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-PRX-001 | GET inoltrato a LH_SVC_<SERVICE>_URL con percorso e query copiati | `GET http://wallet.test/v1/wallets/MBR-000002?size=3&page=1`, senza corpo; risposta 200 | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-002 | POST: metodo e corpo copiati | `POST` a valle con lo stesso corpo e `content-type` | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-003 | X-LH-Actor dal cookie persona BO | `X-LH-Actor: LEGAL:elena.legal` | docs/07 §3 · docs/06 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-004 | cookie assente | X-LH-Actor ANALYST:anonymous | docs/06 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-005 | cookie non valido | X-LH-Actor ANALYST:anonymous | docs/06 §3 · docs/07 §4 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-006 | X-Correlation-Id presente nella richiesta | inoltrato e restituito uguale | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-007 | X-Correlation-Id assente | nuovo ULID (26 caratteri Crockford) | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-008 | risposta 502 dal servizio | 503 {type: SERVICE_ASLEEP, service} | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-009 | risposta 503 dal servizio | 503 {type: SERVICE_ASLEEP, service} | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-010 | risposta 504 dal servizio | 503 {type: SERVICE_ASLEEP, service} | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-011 | errore di rete | 503 SERVICE_ASLEEP | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-012 | nessuna risposta entro 25 s | richiesta interrotta, 503 SERVICE_ASLEEP | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-013 | errore applicativo 422 (RFC 9457) | inoltrato tale e quale | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-014 | errore 500 del servizio sveglio | inoltrato come 500 (non degraded) | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |
| TB-WEB-PRX-015 | servizio sconosciuto | AMBIGUO — 404 UNKNOWN_SERVICE, nessuna chiamata a valle | docs/07 §3 | `web/app/api/lh/[service]/[...path]/route.testbook.test.ts` |


## 6. CLI — Client del proxy

**Regole**
- R1 (docs/07 §3) `SERVICE_ASLEEP` dal proxy → errore "addormentato" (vista degraded); ogni altro errore è ordinario.
- R2 (docs/06 §2, docs/07 §6 Validation) errori RFC 9457 con `errors[]` di campo mappati sui campi.

**Domini**: stato 200/204/409/422/500/503; corpo con `code`, `type`, `title`, `detail`, `errors[]` (validi e malformati),
non JSON; parametri di query vuoti, assenti, 0.
**Strategia**: ogni classe da sola.

**Rami del codice** (`lib/api/client.ts`): risposta ok / errore; `asleep` solo con 503 + `SERVICE_ASLEEP` (R1); codice da
`code` → `type` → `HTTP_<status>` (l'ultimo senza specifica, CLI-005); `detail` → `title` → testo; `errors[]` elenco /
filtro delle voci malformate (R2); parametri vuoti omessi; percorso senza «/» iniziale.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-CLI-001 | 503 {type: SERVICE_ASLEEP} dal proxy | errore «addormentato» | docs/07 §3 · docs/06 §2 | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-002 | 503 del servizio senza SERVICE_ASLEEP | errore ordinario (non degraded) | docs/07 §3 · docs/06 §2 | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-003 | 422 con errors[] | errori di campo {field, message} | docs/06 §2 · docs/07 §6 (Validation) | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-004 | problema con code e detail | code e detail dell'errore | docs/06 §2 (RFC 9457) | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-005 | problema senza code né type | AMBIGUO — HTTP_<status> | docs/07 §3 · docs/06 §2 | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-006 | 200 con JSON → corpo; 204 senza corpo | undefined | docs/07 §3 · docs/06 §2 | `web/lib/api/client.testbook.test.ts` |
| TB-WEB-CLI-007 | chiamata sempre via /api/lh/<service>/…, parametri vuoti o assenti omessi | `/api/lh/member/v1/members?page=0&status=ACTIVE` | docs/07 §3 · docs/06 §2 | `web/lib/api/client.testbook.test.ts` |


## 7. QST — Stati di interfaccia (componente condiviso `QueryState`)

**Regole** (docs/07 §6, obbligatorie per ogni vista con dati; backoffice e portale)
- R1 Loading: scheletro della forma finale, mai spinner a pagina intera; tabelle: 8 righe scheletro.
- R2 Empty: icona tenue + frase che spiega perché è vuoto + azione primaria.
- R3 Error: riquadro con `title` del problema RFC 9457, `detail`, `correlationId` copiabile, "Riprova".
- R4 Degraded (`SERVICE_ASLEEP`): riquadro ambra "Il servizio *wallet* si sta svegliando…" con barra indeterminata;
  riprova automatica ogni 5 s fino a 90 s; il resto della pagina resta usabile.

**Domini**: stato della query loading / errore ordinario / errore addormentato / dati vuoti / dati presenti; regola di
vuoto presente / assente; tempo 5 s, 90 s, 120 s.
**Strategia**: uno per stato + una riga per ogni elemento obbligatorio dello stato (guasto singolo).

**Rami del codice** (`components/shared/QueryState.tsx`): `isLoading` (R1); `isError` + `asleep` (R4); `isError` altro
(R3); `isEmpty` (R2); dati (contenuto). Regole non implementate: 8 righe per le tabelle, azione primaria del vuoto,
`title` e `correlationId` dell'errore, testo e riprova automatica del degraded (QST-002, 004, 008–011); *Stale*
("aggiornato alle 10:42" dopo 60 s senza SSE) e *Forbidden* a pagina intera non hanno un componente condiviso da
provare (vedi anche HOME-018).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-QST-001 | loading | scheletro della forma finale, nessuno spinner, nessun contenuto | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-002 | loading di una tabella | ~~DIVERGENZA~~ risolta (§22) — 8 righe scheletro | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-003 | empty | frase che spiega perché è vuoto | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-004 | empty | ~~DIVERGENZA~~ risolta (§22) — azione primaria (es. «Crea la prima») | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-005 | dati presenti | contenuto | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-006 | elenco vuoto senza regola di vuoto | AMBIGUO — contenuto (vuoto) invece dello stato empty | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-007 | error | dettaglio del problema e «Riprova» che rilancia la query | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-008 | error | ~~DIVERGENZA~~ risolta (§22) — «title» del problema RFC 9457 | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-009 | error | ~~DIVERGENZA~~ risolta (§22) — correlationId mostrato (copiabile) | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-010 | degraded (SERVICE_ASLEEP) | ~~DIVERGENZA~~ risolta (§22) — riquadro ambra «Il servizio wallet si sta svegliando…» | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-011 | degraded | ~~DIVERGENZA~~ risolta (§22) — riprova automatica ogni 5 s fino a 90 s | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |
| TB-WEB-QST-012 | degraded | «Riprova» manuale disponibile | docs/07 §6 | `web/components/shared/QueryState.testbook.test.tsx` |


## 8. LIFE — Barra del ciclo di vita

**Regole**
- R1 (docs/03 §3.6) macchina a stati: DRAFT →SUBMIT→ IN_REVIEW →APPROVE→ APPROVED →PUBLISH→ LIVE ⇄ PAUSED →END→ ENDED
  →ARCHIVE→ ARCHIVED; IN_REVIEW →REJECT (commento obbligatorio)→ DRAFT; DRAFT →PUBLISH→ LIVE solo senza approvazione;
  DRAFT →ARCHIVE.
- R2 (docs/08 §3.3) solo i pulsanti delle transizioni valide per stato × ruolo × policy: DRAFT *Invia in revisione* (se la
  policy la richiede) **oppure** *Pubblica* · *Archivia*; IN_REVIEW *Approva* · *Rifiuta* — per chi non può "In attesa di
  LEGAL da 2 h"; APPROVED *Pubblica*; LIVE *Metti in pausa* · *Termina*; PAUSED *Riprendi* · *Termina*; ENDED *Archivia* ·
  *Duplica*. Con `approval.enabled=false` da DRAFT c'è *Pubblica*.
- R3 (docs/08 §2) SUBMIT/PUBLISH/PAUSE/RESUME/END/ARCHIVE e duplica = `object.edit` (ADMIN, MARKETING); APPROVE/REJECT =
  `object.approve` (ADMIN, LEGAL); gli altri li vedono disabilitati con tooltip (docs/07 §4).
- R4 (docs/08 §3.3) ogni transizione apre un dialogo con commento → `POST …/transitions {action, comment}`.
- R5 (docs/08 §BO-05) le campagne di sistema non si archiviano.
- R6 (docs/06 §7, docs/08 §BO-18) i contenuti non richiedono approvazione: da DRAFT *Pubblica* diretto.

**Domini**

| Ingresso | Valori |
|---|---|
| stato | DRAFT, IN_REVIEW, APPROVED, LIVE, PAUSED, ENDED, ARCHIVED + sconosciuto (`SCHEDULED` come stato) |
| ruolo | ADMIN, MARKETING, LEGAL, CARE, ANALYST |
| policy (`approvalRequired`) | richiesta, non richiesta, non nota |
| oggetto | normale, di sistema; campagna/premio/concorso, contenuto |
| commento di rifiuto | vuoto, soli spazi, testo |

**Strategia**: macchina a stati completa per ruolo — DRAFT × 5 ruoli × 3 policy (15) e gli altri 6 stati × 5 ruoli (30),
la policy conta solo in DRAFT; ogni riga verifica l'insieme **esatto** dei pulsanti (le transizioni vietate non
compaiono) e se sono utilizzabili o disabilitati. Più: stato sconosciuto (1), oggetto di sistema (2), commento ai tre
valori (3), errore `APPROVAL_REQUIRED` (1), attesa per chi non decide (1), dialogo di R4 (1), pill (1), *Duplica* per
ruolo abilitato / non abilitato (2), contenuti per i 5 stati (5).

**Rami del codice** (`components/bo/LifecycleBar.tsx`, `DuplicateButton.tsx`, `lib/content/manage.ts`): tabella
`TRANSITIONS` per 6 stati + stato ignoto (R1, R2); filtro ARCHIVE per il sistema (R5); PUBLISH tolto da DRAFT con
approvazione richiesta, SUBMIT tolto senza (R2); capacità `object.approve` / `object.edit` (R3); REJECT apre il modulo del
commento, le altre azioni partono subito (R4 → LIFE-054); invio del rifiuto disabilitato a commento vuoto (R1); messaggio
per `APPROVAL_REQUIRED`, `renderError`, `detail`; `CONTENT_ACTIONS` per 5 stati (R6); *Duplica* in intestazione per ogni
stato (R3). Ramo senza specifica: policy non nota → entrambe le vie (LIFE-041…045). *Duplica* di ENDED sta
nell'intestazione della pagina (sempre presente), non nella barra.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-LIFE-001 | DRAFT, approvazione richiesta=true, ADMIN | [Invia in revisione, Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-002 | DRAFT, approvazione richiesta=true, MARKETING | [Invia in revisione, Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-003 | DRAFT, approvazione richiesta=true, LEGAL | [Invia in revisione, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-004 | DRAFT, approvazione richiesta=true, CARE | [Invia in revisione, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-005 | DRAFT, approvazione richiesta=true, ANALYST | [Invia in revisione, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-006 | DRAFT, approvazione richiesta=false, ADMIN | [Pubblica, Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-007 | DRAFT, approvazione richiesta=false, MARKETING | [Pubblica, Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-008 | DRAFT, approvazione richiesta=false, LEGAL | [Pubblica, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-009 | DRAFT, approvazione richiesta=false, CARE | [Pubblica, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-010 | DRAFT, approvazione richiesta=false, ANALYST | [Pubblica, Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 · docs/06 §7 · docs/08 §2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-011 | IN_REVIEW, ADMIN | [Approva, Rifiuta…] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-012 | IN_REVIEW, MARKETING | [Approva, Rifiuta…] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-013 | IN_REVIEW, LEGAL | [Approva, Rifiuta…] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-014 | IN_REVIEW, CARE | [Approva, Rifiuta…] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-015 | IN_REVIEW, ANALYST | [Approva, Rifiuta…] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-016 | APPROVED, ADMIN | [Pubblica] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-017 | APPROVED, MARKETING | [Pubblica] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-018 | APPROVED, LEGAL | [Pubblica] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-019 | APPROVED, CARE | [Pubblica] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-020 | APPROVED, ANALYST | [Pubblica] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-021 | LIVE, ADMIN | [Metti in pausa, Termina] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-022 | LIVE, MARKETING | [Metti in pausa, Termina] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-023 | LIVE, LEGAL | [Metti in pausa, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-024 | LIVE, CARE | [Metti in pausa, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-025 | LIVE, ANALYST | [Metti in pausa, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-026 | PAUSED, ADMIN | [Riprendi, Termina] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-027 | PAUSED, MARKETING | [Riprendi, Termina] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-028 | PAUSED, LEGAL | [Riprendi, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-029 | PAUSED, CARE | [Riprendi, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-030 | PAUSED, ANALYST | [Riprendi, Termina] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-031 | ENDED, ADMIN | [Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-032 | ENDED, MARKETING | [Archivia] utilizzabili | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-033 | ENDED, LEGAL | [Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-034 | ENDED, CARE | [Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-035 | ENDED, ANALYST | [Archivia] disabilitati con tooltip | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-036 | ARCHIVED, ADMIN | [] | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-037 | ARCHIVED, MARKETING | [] | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-038 | ARCHIVED, LEGAL | [] | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-039 | ARCHIVED, CARE | [] | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-040 | ARCHIVED, ANALYST | [] | docs/08 §3.3 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-041 | DRAFT, policy non nota, ADMIN | AMBIGUO — entrambe le vie (Invia in revisione, Pubblica) + Archivia | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-042 | DRAFT, policy non nota, MARKETING | AMBIGUO — entrambe le vie (Invia in revisione, Pubblica) + Archivia | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-043 | DRAFT, policy non nota, LEGAL | AMBIGUO — entrambe le vie (Invia in revisione, Pubblica) + Archivia | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-044 | DRAFT, policy non nota, CARE | AMBIGUO — entrambe le vie (Invia in revisione, Pubblica) + Archivia | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-045 | DRAFT, policy non nota, ANALYST | AMBIGUO — entrambe le vie (Invia in revisione, Pubblica) + Archivia | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-046 | stato sconosciuto (SCHEDULED come stato del servizio) | nessun pulsante | docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-047 | campagna di sistema in DRAFT (senza approvazione) | Pubblica, niente Archivia | docs/08 §BO-05 (sistema non si archivia) | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-048 | campagna di sistema ENDED | nessun pulsante (non si archivia) | docs/08 §BO-05 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-049 | Rifiuta: commento vuoto | invio disabilitato | docs/03 §3.6 (REJECT, commento obbligatorio) | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-050 | Rifiuta: commento di soli spazi | invio disabilitato | docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-051 | Rifiuta con commento | POST …/transitions {action: REJECT, comment} | docs/08 §3.3 · docs/06 §7 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-052 | Pubblica rifiutata con 409 APPROVAL_REQUIRED | invito a «Invia in revisione» | docs/06 §7 · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-053 | IN_REVIEW per chi non può decidere (MARKETING) | ~~DIVERGENZA~~ risolta (§22) — «In attesa di LEGAL da …» | docs/08 §3.3 (IN_REVIEW) | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-054 | ogni transizione apre un dialogo con commento prima dell'invio (Metti in pausa) | ~~DIVERGENZA~~ risolta (§22) — si apre un dialogo con commento; nessun `POST` prima della conferma | docs/08 §3.3 (ultimo capoverso) | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-055 | la pill mostra lo stato corrente | pill con «PAUSED» | docs/08 §3.3 · docs/07 §5.2 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-056 | contenuto DRAFT | Pubblica, Archivia (mai Invia in revisione) | docs/06 §7 (CONTENT) · docs/08 §BO-18 · docs/03 §3.6 | `web/lib/content/manage.testbook.test.ts` |
| TB-WEB-LIFE-057 | contenuto LIVE | Metti in pausa, Termina | docs/06 §7 (CONTENT) · docs/08 §BO-18 · docs/03 §3.6 | `web/lib/content/manage.testbook.test.ts` |
| TB-WEB-LIFE-058 | contenuto PAUSED | Riprendi, Termina | docs/06 §7 (CONTENT) · docs/08 §BO-18 · docs/03 §3.6 | `web/lib/content/manage.testbook.test.ts` |
| TB-WEB-LIFE-059 | contenuto ENDED | Archivia | docs/06 §7 (CONTENT) · docs/08 §BO-18 · docs/03 §3.6 | `web/lib/content/manage.testbook.test.ts` |
| TB-WEB-LIFE-060 | contenuto ARCHIVED | nessuna transizione | docs/06 §7 (CONTENT) · docs/08 §BO-18 · docs/03 §3.6 | `web/lib/content/manage.testbook.test.ts` |
| TB-WEB-LIFE-061 | Duplica (object.edit) per MARKETING | utilizzabile | docs/08 §2 (`object.edit`: duplica) · docs/03 §3.6 | `web/components/bo/LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-062 | Duplica (object.edit) per ANALYST | disabilitato con tooltip | docs/08 §2 · docs/07 §4 | `web/components/bo/LifecycleBar.testbook.test.tsx` |


## 9. PILL — Stati oggetto come pill

**Regole**
- R1 (docs/07 §5.2) `DRAFT` grigio, `IN_REVIEW` ambra, `APPROVED` blu, `SCHEDULED` indaco, `LIVE` verde, `PAUSED`
  arancio, `ENDED` slate, `REJECTED` rosso, `ARCHIVED` grigio chiaro (colori distinti per stati distinti).

**Domini**: i 9 stati elencati. **Strategia**: uno per stato (famiglia di colore Tailwind) + le due coppie che la spec
distingue (ENDED/ARCHIVED, PAUSED/IN_REVIEW) + il testo.

**Rami del codice** (`components/bo/primitives.tsx`): voci di `STATUS_TONE` per i 9 stati (8 presenti) + ramo di
default (dove finisce `SCHEDULED`).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-PILL-001 | pill DRAFT | grigio | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-002 | pill IN_REVIEW | ambra | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-003 | pill APPROVED | blu | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-004 | pill SCHEDULED | ~~DIVERGENZA~~ risolta (§22) — indaco | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-005 | pill LIVE | verde | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-006 | pill PAUSED | ~~DIVERGENZA~~ risolta (§22) — arancio | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-007 | pill ENDED | slate | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-008 | pill REJECTED | rosso | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-009 | pill ARCHIVED | grigio chiaro | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-010 | ARCHIVED (grigio chiaro) si distingue da ENDED (slate) | ~~DIVERGENZA~~ risolta (§22) — classi diverse | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-011 | PAUSED (arancio) si distingue da IN_REVIEW (ambra) | ~~DIVERGENZA~~ risolta (§22) — classi diverse | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-PILL-012 | la pill riporta il codice di stato come testo | testo «IN_REVIEW» | docs/07 §5.2 | `web/components/bo/primitives.testbook.test.tsx` |


## 10. APR — Policy delle approvazioni e coda di BO-21

**Regole**
- R1 (docs/06 §7, Q-08) CONTEST e REWARD: approvazione sempre (LEGAL); CAMPAIGN se `requiresLegal = true` **o** budget
  **> 100 000** punti; CONTENT mai (LIFE-056).
- R2 (docs/06 §7) con `loyaltyhub.approval.enabled=false` DRAFT → LIVE diretto per tutti.
- R3 (docs/06 §3, §7; docs/08 §BO-21) *Da approvare*: oggetti `IN_REVIEW` che il ruolo corrente può decidere (ruolo della
  policy o ADMIN).
- R4 (docs/08 §BO-21, F-APR-03, Q-96) aggregazione lato web delle code dei tre servizi; nell'hub le fonti rispondono
  dallo stesso processo (deduplica); un servizio addormentato non blocca gli altri.

**Domini**

| Ingresso | Valori |
|---|---|
| `requiresLegal` | true, false, assente |
| budget | assente, 99 999 (soglia − 1), 100 000 (soglia), 100 001 (soglia + 1) |
| tipo | CAMPAIGN, REWARD, CONTEST |
| policy | attiva, disattivata, non nota, soglia diversa (50 000) |
| ruolo × ruolo richiesto | 5 ruoli × LEGAL; LEGAL/MARKETING × assente; CARE/LEGAL × CARE |
| stato in coda | IN_REVIEW, APPROVED |

**Strategia**: tabella decisionale **completa** per CAMPAIGN 3 × 4 = 12; REWARD/CONTEST con policy attiva (2); ogni tipo
con policy disattivata (3); policy non nota (1); soglia letta dalla policy (1); *Da approvare* per i 5 ruoli (5) + ruolo
assente, stato non in revisione, ruolo diverso (3); aggregazione: deduplica, tipi diversi, ordine, fonte addormentata
(4); *Inviate da me* ed esiti (6); attore (3).

**Rami del codice** (`lib/approvals/queue.ts`): `requiresApproval` policy assente / disattivata / tipo non CAMPAIGN /
`requiresLegal` / budget > soglia (R1, R2); `toApproveBy` stato, ADMIN, ruolo della policy, default LEGAL (R3; default
senza specifica, APR-025); `mergeQueues` deduplica, ordine, fonti fallite (R4; ordine senza specifica); `sentByMe`
ordine; `outcomeOf` 5 esiti; `formatActor` 3 casi (parole e formati senza specifica, APR-030…040).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-APR-001 | CAMPAIGN requiresLegal=true, budget=assente | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-002 | CAMPAIGN requiresLegal=true, budget=99999 | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-003 | CAMPAIGN requiresLegal=true, budget=100000 | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-004 | CAMPAIGN requiresLegal=true, budget=100001 | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-005 | CAMPAIGN requiresLegal=false, budget=assente | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-006 | CAMPAIGN requiresLegal=false, budget=99999 | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-007 | CAMPAIGN requiresLegal=false, budget=100000 | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-008 | CAMPAIGN requiresLegal=false, budget=100001 | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-009 | CAMPAIGN requiresLegal=assente, budget=assente | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-010 | CAMPAIGN requiresLegal=assente, budget=99999 | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-011 | CAMPAIGN requiresLegal=assente, budget=100000 | approvazione non richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-012 | CAMPAIGN requiresLegal=assente, budget=100001 | approvazione richiesta | docs/06 §7 · Q-08 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-013 | REWARD con policy attiva | sempre richiesta (LEGAL) | docs/06 §7 · F-APR-02 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-014 | CONTEST con policy attiva | sempre richiesta (LEGAL) | docs/06 §7 · F-APR-02 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-015 | CAMPAIGN requiresLegal e budget 200 000 con approval.enabled=false → DRAFT | LIVE diretto | docs/06 §7 (approval.enabled) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-016 | REWARD con approval.enabled=false → DRAFT | LIVE diretto | docs/06 §7 (approval.enabled) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-017 | CONTEST con approval.enabled=false → DRAFT | LIVE diretto | docs/06 §7 (approval.enabled) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-018 | policy non ancora nota | AMBIGUO — indeciso (undefined) | docs/06 §7 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-019 | soglia letta dalla policy, non fissa: soglia 50 000, budget 60 000 | richiesta | docs/06 §7 (policy di configurazione) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-020 | «Da approvare» per ADMIN con oggetto IN_REVIEW che richiede LEGAL | presente | docs/06 §3 · §7 · docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-021 | «Da approvare» per MARKETING con oggetto IN_REVIEW che richiede LEGAL | assente | docs/06 §3 · §7 · docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-022 | «Da approvare» per LEGAL con oggetto IN_REVIEW che richiede LEGAL | presente | docs/06 §3 · §7 · docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-023 | «Da approvare» per CARE con oggetto IN_REVIEW che richiede LEGAL | assente | docs/06 §3 · §7 · docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-024 | «Da approvare» per ANALYST con oggetto IN_REVIEW che richiede LEGAL | assente | docs/06 §3 · §7 · docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-025 | ruolo richiesto assente | AMBIGUO — vale LEGAL (unico ruolo approvatore della policy) | docs/06 §7 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-026 | oggetto non più IN_REVIEW (APPROVED) | fuori da «Da approvare» anche per ADMIN | docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-027 | ruolo richiesto dalla policy diverso da LEGAL (CARE) | lo vede CARE, non LEGAL | docs/06 §3 · §7 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-028 | hub consolidato: stesso oggetto dalle tre fonti | una sola riga | docs/08 §BO-21 · F-APR-03 · Q-96 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-029 | stesso id ma tipo diverso | due righe | F-APR-03 · Q-96 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-030 | ordine: chi aspetta da più tempo prima, a parità per codice | AMBIGUO — più vecchio prima; a parità di data per codice (A, C, B) | docs/08 §BO-21 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-031 | una fonte addormentata non blocca le altre | le righe delle due fonti sveglie restano (2 righe) | docs/08 §BO-21 (fonte addormentata) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-032 | «Inviate da me»: dal più recente | AMBIGUO — dal più recente | docs/08 §BO-21 · Q-96 | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-033 | esito IN_REVIEW | AMBIGUO — «In attesa» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-034 | esito rifiutato (torna DRAFT) | AMBIGUO — «Rifiutato» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-035 | esito approvato (APPROVED) | AMBIGUO — «Approvato» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-036 | esito approvato e già LIVE | AMBIGUO — «Approvato e pubblicato» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-037 | esito nessuna decisione, stato DRAFT | AMBIGUO — codice di stato | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-038 | formatActor «LEGAL:elena.legal» | AMBIGUO — «elena.legal (LEGAL)» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-039 | formatActor attore assente | AMBIGUO — «—» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |
| TB-WEB-APR-040 | formatActor attore senza «:» («system») | AMBIGUO — «system» | docs/08 §BO-21 · §3.6 (ActorStamp) | `web/lib/approvals/queue.testbook.test.ts` |


## 11. DESC — Frase generata della campagna

**Regole**
- R1 (docs/08 §BO-06) frase che «rilegge la regola in italiano a ogni modifica», funzione pura `describeCampaign()`
  usata anche in BO-21; esempio: *"Quando arriva **Acquisto completato** da ecommerce o app, se **importo ≥ 50 €** e il
  membro è **GOLD o PLATINUM**, assegna **1 giocata a Ruota d'Autunno**, al massimo **1 volta al giorno**."*
- R2 (esempio) ordine: trigger, fonti, condizioni, pubblico, effetti, limiti; alternative unite da «o».
- R3 (docs/03 §3.3–3.4, docs/08 §BO-06 sezioni 2–6) ogni parte della regola ha un parametro: trigger e fonti, gruppi
  TUTTE/ALMENO UNA/NESSUNA (Q-90: NESSUNA = «non tutte vere»), 14 comparatori, pubblico per tier o segmenti, 6 tipi di
  effetto (GRANT_POINTS in 4 modi), limiti per periodo DAY/WEEK/MONTH/EDITION/ALWAYS.

**Domini**

| Ingresso | Valori |
|---|---|
| trigger | nessuno, uno noto, due, custom con nome, sconosciuto senza nome |
| fonti | ecommerce + app |
| condizioni | foglia per ciascuno dei 14 comparatori; gruppi all/any/not, vuoto, annidato |
| pubblico | tutti, tier, segmenti, elenchi vuoti, condizioni + pubblico |
| effetti | GRANT_POINTS FIXED/PER_AMOUNT/FROM_FIELD/LOOKUP, MULTIPLIER, GRANT_PLAYS (1, 2), ISSUE_COUPON, AWARD_BADGE, SEND_MESSAGE con/senza template, sconosciuto, nessuno, più effetti |
| limiti | 1/DAY, 2/WEEK, MONTH, EDITION, ALWAYS, nessuno, più limiti |

**Strategia**: prodotto dei domini troppo grande (> 10⁴): l'esempio completo della spec (1) più **ogni classe provata da
sola** su una bozza minima valida (trigger noto + 10 PTS fissi), perché le parti della frase sono indipendenti
(concatenazione). Oracolo per le classi che l'esempio non copre: il parametro compare nella frase, in italiano, senza il
codice interno (comparatori, modi).

**Rami del codice** (`lib/campaign/describe.ts`): trigger vuoto / etichetta del chiamante / mappa interna / codice;
pubblico assente o `all` / tier / segmenti; nodo any / not / all / gruppo vuoto / exists-nexists / foglia con valore;
etichetta del campo mappata / percorso; valore elenco / scalare; effetti nessuno / GRANT_POINTS PER_AMOUNT, FIXED, altro /
MULTIPLIER / GRANT_PLAYS / ISSUE_COUPON / AWARD_BADGE / SEND_MESSAGE con e senza template / sconosciuto; limiti
nessuno / primo / periodo noto / ignoto. Regole non implementate: fonti, «€» sull'importo, nome del concorso e
preposizione «a», singolare/plurale, campo di FROM_FIELD e LOOKUP (vedi registro). Rami senza specifica: DESC-005, 006,
030, 038–043, 051.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-DESC-001 | esempio completo della spec, parola per parola | ~~DIVERGENZA~~ risolta (§22) — «Quando arriva **Acquisto completato** da ecommerce o app, se **importo ≥ 50 €** e il membro è **GOLD o PLATINUM**, assegna **1 giocata a Ruota d'Autunno**, al massimo **1 volta al giorno**.» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-002 | un trigger noto | «Quando arriva **Acquisto completato**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-003 | due trigger | uniti da «o» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-004 | tipo custom col nome fornito dal chiamante (BO-09) | il nome, non il codice | docs/08 §BO-06 · §BO-09 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-005 | tipo sconosciuto senza nome | AMBIGUO — il codice | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-006 | nessun trigger | AMBIGUO — «Quando arriva un'azione» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-007 | fonti ammesse (ecommerce, app) | ~~DIVERGENZA~~ risolta (§22) — «da ecommerce o app» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-008 | importo ≥ 50 | ~~DIVERGENZA~~ risolta (§22) — «**importo ≥ 50 €**» (importo in euro) | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-009 | comparatore eq su context.hour | campo e valori in italiano, senza il codice «eq» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-010 | comparatore neq su context.hour | campo e valori in italiano, senza il codice «neq» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-011 | comparatore gt su context.hour | campo e valori in italiano, senza il codice «gt» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-012 | comparatore lt su context.hour | campo e valori in italiano, senza il codice «lt» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-013 | comparatore lte su context.hour | campo e valori in italiano, senza il codice «lte» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-014 | comparatore in su context.dayOfWeek | campo e valori in italiano, senza il codice «in» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-015 | comparatore nin su context.dayOfWeek | campo e valori in italiano, senza il codice «nin» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-016 | comparatore contains su member.labels | campo e valori in italiano, senza il codice «contains» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-017 | comparatore ncontains su member.labels | campo e valori in italiano, senza il codice «ncontains» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-018 | comparatore exists su data.coupon | campo e valori in italiano, senza il codice «exists» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-019 | comparatore nexists su data.coupon | campo e valori in italiano, senza il codice «nexists» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-020 | comparatore between su context.hour | campo e valori in italiano, senza il codice «between» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-021 | comparatore startsWith su data.sku | campo e valori in italiano, senza il codice «startsWith» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-022 | gruppo TUTTE | condizioni unite da «e» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-023 | gruppo ALMENO UNA | «(a oppure b)» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-024 | gruppo NESSUNA con due righe | «non (a e b)» come le valuta il motore (Q-90) | docs/08 §BO-06 · Q-90 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-025 | gruppo vuoto | nessun «se» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-026 | gruppi annidati (TUTTE › ALMENO UNA) | «a e (b oppure c)» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-027 | pubblico «tutti» | nessuna frase sul pubblico | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-028 | pubblico GOLD o PLATINUM | «il membro è **GOLD o PLATINUM**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-029 | pubblico con elenchi vuoti | nessuna frase sul pubblico | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-030 | pubblico per segmenti | AMBIGUO — «è nel segmento **SEG-1 o SEG-2**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-031 | condizioni e pubblico insieme | prima le condizioni, poi il pubblico (come nell'esempio) | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-032 | GRANT_POINTS FIXED 300 PTS | «assegna **300 PTS**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-033 | GRANT_POINTS PER_AMOUNT 1 STS ogni 1 € | valore, valuta e passo in euro | docs/08 §BO-06 · docs/03 §3.4 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-034 | GRANT_POINTS FROM_FIELD da data.points | ~~DIVERGENZA~~ risolta (§22) — la frase nomina il campo sorgente | docs/08 §BO-06 · docs/03 §3.4 (FROM_FIELD) | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-035 | GRANT_POINTS LOOKUP su data.plan | ~~DIVERGENZA~~ risolta (§22) — la frase nomina il campo della tabella | docs/08 §BO-06 · docs/03 §3.4 (LOOKUP) | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-036 | MULTIPLIER ×2 su PTS | «**PTS ×2**» | docs/08 §BO-06 · docs/09 §PT-02 («×2») | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-037 | GRANT_PLAYS 2 giocate | ~~DIVERGENZA~~ risolta (§22) — «2 giocate» (plurale) | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-038 | ISSUE_COUPON | AMBIGUO — «**un coupon**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-039 | AWARD_BADGE | AMBIGUO — «**un badge**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-040 | SEND_MESSAGE con template | AMBIGUO — «**il messaggio MSG-WELCOME**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-041 | SEND_MESSAGE senza template | AMBIGUO — «**un messaggio**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-042 | tipo di effetto sconosciuto | AMBIGUO — il codice | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-043 | nessun effetto | AMBIGUO — «**nessun effetto**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-044 | più effetti | uniti da «e» nell'ordine dell'elenco | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-045 | limite 1 al giorno | ~~DIVERGENZA~~ risolta (§22) — «al massimo **1 volta al giorno**» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-046 | limite 2 alla settimana | ~~DIVERGENZA~~ risolta (§22) — «**2 volte alla settimana**» (plurale) | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-047 | limite: periodo MONTH | «al mese» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-048 | limite: periodo EDITION | «per edizione» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-049 | limite: periodo ALWAYS («sempre») | «in totale» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-050 | nessun limite | nessun «al massimo» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-051 | più limiti per membro | AMBIGUO — solo il primo nella frase | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-052 | frase sempre chiusa dal punto finale | l'ultima lettera è «.» | docs/08 §BO-06 | `web/lib/campaign/describe.testbook.test.ts` |


## 12. COND — Costruttore delle condizioni (BO-06 «4 Se»)

**Regole**
- R1 (docs/08 §BO-06) gruppi TUTTE / ALMENO UNA / NESSUNA annidabili, **massimo 3 livelli**.
- R2 (docs/08 §BO-06, docs/03 §3.3, Q-92) campo da una combobox raggruppata per spazio `data`, `member`, `context`,
  `history`; campi di `member`/`context`/`history` fissi (docs/03 §3.3), `data.*` dagli schemi dei tipi azione, attributi
  da member; tier dal wallet.
- R3 (docs/08 §BO-06) operatore coerente col tipo; valore secondo il tipo (numero, testo, elenco, enum → select, data,
  booleano); date con i comparatori della spec (Q-91).
- R4 (docs/08 §BO-06) con più trigger i `data.*` sono l'**intersezione**; un campo non comune mostra un avviso.
- R5 (Q-90) avviso quando un gruppo NESSUNA ha più di una riga.
- R6 (docs/08 §3.2, docs/03 §3.3) validazione prima del salvataggio; il JSON salvato è `{op, rules}` / `{field, cmp,
  value}`, senza gruppi vuoti.

**Domini**

| Ingresso | Valori |
|---|---|
| profondità | 1, 3 (max), 4 (max + 1); aggiunta a livello 2 e 3; `canAddGroup` a livello 1, 2, 3 |
| tipo del campo | number, string, enum, boolean, list, date, object, unknown |
| schema del campo `data.*` | number, integer, boolean, array, object, string+enum, string+date, string+date-time, string |
| trigger | 1, 2 con campi comuni, 2 disgiunti, number/integer, enum diversi, `required` diversi, campi non arrivati |
| campo della riga | comune, di un solo trigger, di nessuno, catalogo non arrivato, member noto, member fuori catalogo, spazio sconosciuto, attributo con member addormentato |
| valore | vuoto, 0, testo su numero, «true» su booleano, data valida/non valida, enum fuori elenco, elenco vuoto/misto, intervallo incompleto/invertito/estremi uguali |
| JSON | null, `{}`, foglia sola, op sconosciuto, comparatore sconosciuto, testo non JSON, gruppi vuoti, `exists` con valore |

**Strategia**: valori limite della profondità; un caso per tipo (comparatori e valore); classi dell'intersezione e degli
avvisi una per una; classi non valide del valore provate da sole (guasto singolo) + i due valori limite validi (0,
estremi uguali); JSON non valido una classe per riga.

**Rami del codice** (`lib/campaign/conditions.ts`, `components/bo/campaigns/ConditionBuilder.tsx`): `dataFieldType`
(7 casi, R3); `commonDataFields` (liste assenti, campo mancante, fusione tipi ×3, enum, format; R4); `buildCatalog`
(tier di ripiego, segmenti, nota `[*]`, opzioni enum; R2); `attributeField` (5 casi, R2); `lookupField` noto / fuori
catalogo; `groupFields`; `isPlausiblePath` (ramo senza specifica, COND-087…089); `parseNode` (7 errori/casi) e `fromJson`
(null, avvolgimento, profondità, eccezione; R1, R6); `parseConditionsText`; `nodeToJson` (valore, gruppo vuoto; R6);
`appendChild`/`canAddGroup` (R1); `changeField`, `changeComparator` (4 forme), `coerceScalar` (4), `parseList`;
`leafProblem`/`scalarProblem` (14 esiti, R3, R6); `fieldWarnings` (6 esiti, R4); avviso NESSUNA (R5). Rami senza
specifica: catalogo parziale (COND-042, 046).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-COND-001 | JSON con 1 livello di gruppi | accettato | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-002 | JSON con 3 livelli (massimo) | accettato | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-003 | JSON con 4 livelli (massimo + 1) | rifiutato «Al massimo 3 livelli» | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-004 | aggiungere un sottogruppo al livello 2 (diventa livello 3) | aggiunto | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-005 | aggiungere un sottogruppo al livello 3 (diventerebbe livello 4) | albero invariato | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-006 | canAddGroup livello 1 | si può aggiungere un gruppo | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-007 | canAddGroup livello 2 | si può aggiungere un gruppo | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-008 | canAddGroup livello 3 | non si può aggiungere un gruppo | docs/08 §BO-06 · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-009 | comparatori per numero | confronti d'ordine e intervallo, niente testo | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-010 | comparatori per testo | uguaglianza, inizia con, contiene; niente ordine | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-011 | comparatori per enum | uguaglianza ed elenco; niente ordine né contiene | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-012 | comparatori per booleano | uguaglianza; niente ordine, elenco, contiene | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-013 | comparatori per elenco | contiene; niente ordine | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-014 | comparatori per data | dal/fino al/tra (Q-91) | docs/08 §BO-06 · Q-91 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-015 | comparatori per oggetto | solo presente/assente | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-016 | comparatori per percorso fuori catalogo | tutti i 14 comparatori | docs/08 §BO-06 «operatore coerente col tipo» · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-017 | tipo del valore: schema number | numero | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-018 | tipo del valore: schema integer | numero | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-019 | tipo del valore: schema boolean | booleano | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-020 | tipo del valore: schema array | elenco | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-021 | tipo del valore: schema object | oggetto | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-022 | tipo del valore: stringa con enum | enum (select) | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-023 | tipo del valore: stringa format date | data | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-024 | tipo del valore: stringa format date-time | data | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-025 | tipo del valore: stringa semplice | testo | docs/08 §BO-06 «valore secondo il tipo» | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-026 | campi raggruppati per spazio nell'ordine data, member, context, history | gruppi in quest'ordine, gli spazi vuoti omessi | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-027 | spazio member: tier, status, segments, labels, registeredDaysAgo, age | `member.tier`, `member.status`, `member.segments`, `member.labels`, `member.registeredDaysAgo`, `member.age` | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-028 | spazio context: source, dayOfWeek (MON…SUN), hour, date | `context.source`, `context.dayOfWeek` (opzioni MON…SUN), `context.hour`, `context.date` | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-029 | spazio history: actionCount, daysSinceLastAction | `history.actionCount`, `history.daysSinceLastAction` | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-030 | tier dal wallet; wallet assente | BASE, SILVER, GOLD, PLATINUM (docs/03 §4.3) | docs/03 §4.3 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-031 | attributo custom NUMBER con opzioni | enum di numeri (member.attributes.<k>) | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-032 | attributo custom BOOLEAN | booleano (member.attributes.<k>) | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-033 | attributo custom DATE | data (member.attributes.<k>) | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-034 | attributo custom STRING senza opzioni | testo (member.attributes.<k>) | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-035 | campo su elenco data.items[*].category | nota «vero se almeno un elemento soddisfa» | docs/03 §3.3 · docs/08 §BO-06 · Q-92 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-036 | un trigger | tutti i suoi campi | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-037 | due trigger | solo i campi comuni | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-038 | trigger senza campi in comune | nessun campo data.* | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-039 | number e integer | number | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-040 | enum diversi | unione dei valori | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-041 | obbligatorio solo se lo è in tutti i trigger | `data.amount` non obbligatorio, `data.channel` obbligatorio | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-042 | campi di un trigger non ancora arrivati | AMBIGUO — quel trigger è ignorato nell'intersezione | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-043 | campo data.* comune a tutti i trigger | nessun avviso | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-044 | campo data.* di un solo trigger | avviso «manca in visit» | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-045 | campo data.* di nessun trigger | avviso «potrebbe non essere mai vera» | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-046 | campi dei trigger non ancora arrivati | AMBIGUO — nessun avviso | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-047 | campo member del catalogo (member.tier) | nessun avviso | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-048 | campo member fuori catalogo (member.nickname) | «fuori catalogo» | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-049 | spazio sconosciuto (order.total) | «Spazio sconosciuto» | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-050 | attributo custom con member addormentato | nessun avviso «fuori catalogo» | docs/08 §BO-06 (intersezione, avviso) | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-051 | gruppo NESSUNA con due righe | avviso sulla semantica del motore (Q-90) | docs/08 §BO-06 · Q-90 | `web/components/bo/campaigns/ConditionBuilder.testbook.test.tsx` |
| TB-WEB-COND-052 | gruppo NESSUNA con una riga | nessun avviso | docs/08 §BO-06 · Q-90 | `web/components/bo/campaigns/ConditionBuilder.testbook.test.tsx` |
| TB-WEB-COND-053 | riga: campo vuoto | «Scegli un campo» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-054 | riga: gt su booleano | «Operatore non ammesso per questo campo» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-055 | riga: presente (exists) senza valore | valida | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-056 | riga: in con elenco vuoto | «Aggiungi almeno un valore» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-057 | riga: in su numero con un testo | «Serve un numero» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-058 | riga: tra con un solo estremo | «Servono due valori: da, a» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-059 | riga: tra 18 e 9 (invertito) | «Il primo valore supera il secondo» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-060 | riga: tra 9 e 9 (estremi uguali) | valida | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-061 | riga: numero con testo «abc» | «Serve un numero» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-062 | riga: numero vuoto | «Valore mancante» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-063 | riga: booleano con testo «true» | «Scegli sì o no» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-064 | riga: data «18/09/2026» | «Data non valida (AAAA-MM-GG)» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-065 | riga: data «2026-09-18» | valida | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-066 | riga: enum con valore fuori elenco | «Valore non ammesso: XYZ» | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-067 | riga: numero 0 | valido (zero non è «mancante») | docs/08 §BO-06 · §3.2 (validazione) · docs/03 §3.3 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-068 | condizioni null | gruppo TUTTE vuoto | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-069 | condizioni {} | gruppo TUTTE vuoto | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-070 | una foglia sola | avvolta in un gruppo TUTTE | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-071 | operatore di gruppo sconosciuto (xor) | errore | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-072 | comparatore sconosciuto (like) | errore | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-073 | testo non JSON nella vista JSON | «JSON non valido» | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-074 | salvataggio: i gruppi vuoti (anche NESSUNA) sono tolti | `{op: all, rules: [importo ≥ 10]}` senza il gruppo NESSUNA vuoto | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-075 | salvataggio senza condizioni | null (il servizio le tratta come sempre vere) | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-076 | salvataggio di «presente» | nessun valore nel JSON | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-077 | coerceScalar numero con virgola «1,5» | 1.5 | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-078 | coerceScalar numero con testo «abc» | resta testo (segnalato da COND-061) | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-079 | elenco «SAT, SUN, SAT» | senza doppioni | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-080 | coerceScalar percorso libero «12» | numero 12 | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-081 | cambio comparatore = 50 → in | [50] | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-082 | cambio comparatore in [50, 60] → = | 50 | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-083 | cambio comparatore = 9 → tra | [9, null] | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-084 | cambio comparatore = 9 → presente | nessun valore | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-085 | cambio campo tra due numeri | comparatore e valore conservati | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-086 | cambio campo da numero a booleano | comparatore «=» e valore sì | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-087 | percorso a mano «data.items[*].sku» → plausibile | AMBIGUO — plausibile | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-088 | percorso a mano in uno spazio sconosciuto «order.total» | AMBIGUO — non plausibile | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-089 | percorso a mano incompleto «data.» | AMBIGUO — non plausibile | docs/03 §3.3 · docs/08 §BO-06 | `web/lib/campaign/conditions.testbook.test.ts` |


## 13. FMT — Formati (punti, euro, date)

**Regole**
- R1 (docs/07 §9) punti `1.850` (separatore delle migliaia), valute `€ 129,90`, date `18 set 2026, 10:42`, relative
  "3 min fa" **sotto le 24 h**; fuso Europe/Rome (docs/07 §2, `format/dates.ts`).
- R2 (docs/08 §3.6) `PointsAmount`: segno, colore semantico, valuta.
- R3 (docs/08 §BO-08, docs/03 §4.1) esempio di scadenza `ROLLING_MONTHS`: n mesi + fine mese ("guadagnati oggi → scadono
  il 30 set 2027").

**Domini**

| Ingresso | Valori |
|---|---|
| punti | 0, 1, 999, 1 000, 1 850, 999 999, 1 000 000, −1, −1 850, −0; decimali 1,5 e 1,4 |
| euro | 0, 129,9, 1 234,5 |
| istante | esempio in ora legale; 23:59:59 / 00:00 di Roma; 31 gen → 1 feb (ora solare, fine mese); 29 mar 01:59:59 / 03:00 e 25 ott 02:59:59 / 02:00 (cambi dell'ora); 29 feb 2028; 31 dic → 1 gen; assente |
| distanza relativa | 0 s, 3 min, 59 min, 60 min, 23 h, 23 h 40 min, 24 h, futuro |
| mesi / partenza | 12 dal 18 set; 1 dal 31 gen; 13 dal 31 gen 2027 (29 feb); 12 dal 29 feb; 0; 30 set 23:59:59 / 1 ott 00:00; 31 dic |

**Strategia**: valori limite per ogni dominio numerico e temporale, uno per riga.

**Rami del codice** (`lib/format/points.ts`, `dates.ts`, `components/bo/primitives.tsx`): `formatPoints` (raggruppamento
sempre attivo), `formatEuro`, `formatDate` assente / valore, `formatDateTime`, `formatTime`, `formatRelative` < 1 min /
< 60 min / ore arrotondate < 24 / data estesa, `computeRollingExpiry` (calendario locale del browser), `PointsAmount`
positivo / negativo / zero. Rami senza specifica: "ora" sotto il minuto e nel futuro, forma delle ore, decimali, data
assente (FMT-011, 012, 027, 031–033, 036). Regola non implementata e non provata qui: il colore semantico per tipo di
movimento (spesa ambra, scadenza rossa, STS viola, docs/07 §5.2) — `PointsAmount` conosce solo il segno.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-FMT-001 | formatPoints 0 | «0» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-002 | formatPoints 1 | «1» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-003 | formatPoints 999 (ultimo senza separatore) | «999» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-004 | formatPoints 1000 (primo con separatore) | «1.000» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-005 | formatPoints 1850 (esempio della spec) | «1.850» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-006 | formatPoints 999999 | «999.999» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-007 | formatPoints 1000000 | «1.000.000» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-008 | formatPoints −1 | «-1» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-009 | formatPoints −1850 | «-1.850» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-010 | formatPoints −0 (zero negativo) | ~~DIVERGENZA~~ risolta (§22) — «0» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-011 | formatPoints 1,5 | AMBIGUO — «2» (arrotondato) | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-012 | formatPoints 1,4 | AMBIGUO — «1» (arrotondato) | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-013 | formatEuro 129,9 | ~~DIVERGENZA~~ risolta (§22) — «€ 129,90» (esempio della spec) | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-014 | formatEuro 1234,5 | ~~DIVERGENZA~~ risolta (§22) — «€ 1.234,50» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-015 | formatEuro 0 | ~~DIVERGENZA~~ risolta (§22) — «€ 0,00» | docs/07 §9 | `web/lib/format/points.testbook.test.ts` |
| TB-WEB-FMT-016 | formatDateTime esempio della spec, ora legale (08:42Z) | «18 set 2026, 10:42» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-017 | formatDateTime un secondo prima della mezzanotte di Roma (21:59:59Z) | «18 set 2026, 23:59» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-018 | formatDateTime mezzanotte di Roma (22:00Z) | «19 set 2026, 00:00» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-019 | formatDateTime ora solare, 31 gen 23:59:59 di Roma | «31 gen 2026, 23:59» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-020 | formatDateTime fine mese: 1 feb 00:00 di Roma | «1 feb 2026, 00:00» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-021 | formatDateTime cambio ora di marzo, prima (00:59:59Z) | «29 mar 2026, 01:59» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-022 | formatDateTime cambio ora di marzo, dopo (01:00Z) | «29 mar 2026, 03:00» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-023 | formatDateTime cambio ora di ottobre, prima (00:59:59Z) | «25 ott 2026, 02:59» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-024 | formatDateTime cambio ora di ottobre, dopo (01:00Z) | «25 ott 2026, 02:00» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-025 | formatDateTime 29 febbraio (anno bisestile 2028) | «29 feb 2028, 12:00» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-026 | formatDate a cavallo d'anno: 31 dic 23:30Z è già 1 gen a Roma | «1 gen 2027» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-027 | formatDate di un valore assente | AMBIGUO — «—» | docs/07 §9 · §2 (Europe/Rome) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-028 | formatTime (etichetta «aggiornato alle 10:42», docs/07 §6) | «10:42» | docs/07 §6 (Stale) · §9 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-029 | formatRelative 3 min fa (esempio della spec) | «3 min fa» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-030 | formatRelative 59 min fa | «59 min fa» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-031 | formatRelative adesso (0 s) | AMBIGUO — «ora» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-032 | formatRelative 60 min fa | AMBIGUO — «1 h fa» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-033 | formatRelative 23 h fa | AMBIGUO — «23 h fa» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-034 | formatRelative 23 h 40 min fa (sotto le 24 h) | ~~DIVERGENZA~~ risolta (§22) — ancora relativa | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-035 | formatRelative esattamente 24 h fa | data estesa «17 set 2026, 12:00» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-036 | formatRelative tra 5 minuti (futuro) | AMBIGUO — «ora» | docs/07 §9 (relative sotto le 24 h) | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-037 | computeRollingExpiry 12 mesi dal 18 set 2026 | 30 set 2027 (esempio di BO-08) | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-038 | computeRollingExpiry 1 mese dal 31 gen 2026 | 28 feb 2026 (non marzo) | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-039 | computeRollingExpiry 13 mesi dal 31 gen 2027 | 29 feb 2028 (bisestile) | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-040 | PointsAmount +1850 PTS | «+1.850 PTS» | docs/08 §3.6 (PointsAmount) · docs/07 §9 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-FMT-041 | PointsAmount −1850 | «-1.850» (segno meno, nessun +) | docs/08 §3.6 (PointsAmount) · docs/07 §9 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-FMT-042 | PointsAmount 0 | «0» senza segno | docs/08 §3.6 (PointsAmount) · docs/07 §9 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-FMT-043 | PointsAmount +1 STS | «+1 STS» | docs/08 §3.6 (PointsAmount) · docs/07 §9 | `web/components/bo/primitives.testbook.test.tsx` |
| TB-WEB-FMT-044 | computeRollingExpiry 12 mesi dal 29 feb 2028 | 28 feb 2029 | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-045 | computeRollingExpiry 0 mesi | fine del mese corrente (30 set 2026) | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-046 | computeRollingExpiry 12 mesi dal 30 set 2026 23:59 | 30 set 2027 | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-047 | computeRollingExpiry 12 mesi dal 1 ott 2026 00:00 | 31 ott 2027 | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |
| TB-WEB-FMT-048 | computeRollingExpiry 1 mese dal 31 dic 2026 | 31 gen 2027 (cambio d'anno) | docs/08 §BO-08 · docs/03 §4.1 | `web/lib/format/dates.testbook.test.ts` |


## 14. RWD — Catalogo premi e richieste nel portale

**Regole**
- R1 (docs/09 §PT-04, docs/03 §5) un premio si richiede se non esaurito, non riservato a un altro livello, limite per
  membro non raggiunto e saldo ≥ costo; altrimenti la barra mostra il motivo ("Ti mancano 350 punti", "Riservato a GOLD",
  "Esaurito"; "Già richiesto" da PT-03). L'interruttore "Solo quelli che posso richiedere" usa la stessa regola.
- R2 (docs/09 §PT-03) stati del premio: `LOW` → "Ultimi pezzi", `SOLD_OUT` → "Esaurito", `lockedByTier` → "Riservato a
  GOLD", `perMemberLimitReached` → "Già richiesto".
- R3 (docs/09 §PT-03, docs/03 §5) fasce in ordine di soglia: *raggiunta* oppure "ti mancano N punti" con barra.
- R4 (docs/09 §PT-13) stato della richiesta in parole: *in conferma*, *confermata*, *spedita*, *annullata — punti
  restituiti*, *non andata a buon fine*; PT-04 3b "Punti non sufficienti".
- R5 (docs/09 §PT-04) attesa della saga: CONFIRMED/FULFILLED → esito positivo (per i coupon il codice), REJECTED → esito
  negativo.
- R6 (docs/09 §PT-13) coupon: stato *attivo*, *usato*, *scaduto*; i non attivi in fondo.
La finestra di validità del premio è applicata dal servizio reward (docs/03 §5 "Visibile al membro se: LIVE, dentro
validità…"): il web riceve solo i premi visibili e non ha rami su quel dato (fuori perimetro, `TB-RWD`).

**Domini**

| Ingresso | Valori |
|---|---|
| stock | AVAILABLE, LOW, SOLD_OUT |
| riservato a | nessuno, GOLD, GOLD+PLATINUM, elenco vuoto |
| limite per membro | libero, raggiunto |
| saldo vs costo 1 500 | 1 150 (< costo, mancano 350), 1 500 (=), 1 501 (>) ; fascia: 0, 1 499, 1 500, 1 501, soglia 0 |
| richiesta | PENDING, CONFIRMED, FULFILLED (nota, coupon, nessuno), CANCELLED (assistenza, membro), REJECTED (saldo, altro) |
| coupon | ISSUED, USED, EXPIRED; scadenze diverse |

**Strategia**: tabella decisionale **completa** 3 × 2 × 2 × 3 = **36** per il motivo del blocco (con più motivi la spec
non fissa quale mostrare: l'oracolo accetta uno qualunque dei motivi applicabili, "richiedibile" solo se nessuno) e
**completa** 3 × 2 × 2 = 12 per l'etichetta della griglia; valori limite della fascia; un caso per stato della richiesta
e per esito dell'attesa.

**Rami del codice** (`lib/reward/portal.ts`): `bandProgress` (mancanti ≥ 0, soglia ≤ 0, barra ≤ 100); `blockReason`
(esaurito, riservato, limite, saldo, nessuno); `rewardBadge` (esaurito, riservato, limite, ultimi pezzi, nessuno);
`redemptionWords` (8 esiti); `isSettled` (esiti finali, CONFIRMED non coupon, coupon da verificare); `sortCoupons`
(attivi prima, scadenza); `COUPON_WORDS`. Rami senza specifica: RWD-054, 056, 057, 064–066, 070, 075.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-RWD-001 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite libero, saldo 1.150 < costo | Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-002 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite libero, saldo = costo | richiedibile | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-003 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite libero, saldo 1.501 > costo | richiedibile | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-004 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite raggiunto, saldo 1.150 < costo | Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-005 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite raggiunto, saldo = costo | Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-006 | PT-04 motivo del blocco: stock AVAILABLE, per tutti, limite raggiunto, saldo 1.501 > costo | Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-007 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite libero, saldo 1.150 < costo | Riservato a GOLD \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-008 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite libero, saldo = costo | Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-009 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite libero, saldo 1.501 > costo | Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-010 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite raggiunto, saldo 1.150 < costo | Riservato a GOLD \| Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-011 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite raggiunto, saldo = costo | Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-012 | PT-04 motivo del blocco: stock AVAILABLE, riservato a GOLD, limite raggiunto, saldo 1.501 > costo | Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-013 | PT-04 motivo del blocco: stock LOW, per tutti, limite libero, saldo 1.150 < costo | Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-014 | PT-04 motivo del blocco: stock LOW, per tutti, limite libero, saldo = costo | richiedibile | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-015 | PT-04 motivo del blocco: stock LOW, per tutti, limite libero, saldo 1.501 > costo | richiedibile | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-016 | PT-04 motivo del blocco: stock LOW, per tutti, limite raggiunto, saldo 1.150 < costo | Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-017 | PT-04 motivo del blocco: stock LOW, per tutti, limite raggiunto, saldo = costo | Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-018 | PT-04 motivo del blocco: stock LOW, per tutti, limite raggiunto, saldo 1.501 > costo | Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-019 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite libero, saldo 1.150 < costo | Riservato a GOLD \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-020 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite libero, saldo = costo | Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-021 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite libero, saldo 1.501 > costo | Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-022 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite raggiunto, saldo 1.150 < costo | Riservato a GOLD \| Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-023 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite raggiunto, saldo = costo | Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-024 | PT-04 motivo del blocco: stock LOW, riservato a GOLD, limite raggiunto, saldo 1.501 > costo | Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-025 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite libero, saldo 1.150 < costo | Esaurito \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-026 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite libero, saldo = costo | Esaurito | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-027 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite libero, saldo 1.501 > costo | Esaurito | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-028 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite raggiunto, saldo 1.150 < costo | Esaurito \| Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-029 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite raggiunto, saldo = costo | Esaurito \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-030 | PT-04 motivo del blocco: stock SOLD_OUT, per tutti, limite raggiunto, saldo 1.501 > costo | Esaurito \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-031 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite libero, saldo 1.150 < costo | Esaurito \| Riservato a GOLD \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-032 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite libero, saldo = costo | Esaurito \| Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-033 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite libero, saldo 1.501 > costo | Esaurito \| Riservato a GOLD | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-034 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite raggiunto, saldo 1.150 < costo | Esaurito \| Riservato a GOLD \| Già richiesto \| Ti mancano 350 punti | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-035 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite raggiunto, saldo = costo | Esaurito \| Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-036 | PT-04 motivo del blocco: stock SOLD_OUT, riservato a GOLD, limite raggiunto, saldo 1.501 > costo | Esaurito \| Riservato a GOLD \| Già richiesto | docs/09 §PT-04 · §PT-03 · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-037 | PT-03 etichetta: stock AVAILABLE, per tutti, limite libero | nessuna etichetta | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-038 | PT-03 etichetta: stock AVAILABLE, per tutti, limite raggiunto | Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-039 | PT-03 etichetta: stock AVAILABLE, riservato a GOLD, limite libero | Riservato a GOLD | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-040 | PT-03 etichetta: stock AVAILABLE, riservato a GOLD, limite raggiunto | Riservato a GOLD \| Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-041 | PT-03 etichetta: stock LOW, per tutti, limite libero | Ultimi pezzi | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-042 | PT-03 etichetta: stock LOW, per tutti, limite raggiunto | Ultimi pezzi \| Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-043 | PT-03 etichetta: stock LOW, riservato a GOLD, limite libero | Ultimi pezzi \| Riservato a GOLD | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-044 | PT-03 etichetta: stock LOW, riservato a GOLD, limite raggiunto | Ultimi pezzi \| Riservato a GOLD \| Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-045 | PT-03 etichetta: stock SOLD_OUT, per tutti, limite libero | Esaurito | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-046 | PT-03 etichetta: stock SOLD_OUT, per tutti, limite raggiunto | Esaurito \| Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-047 | PT-03 etichetta: stock SOLD_OUT, riservato a GOLD, limite libero | Esaurito \| Riservato a GOLD | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-048 | PT-03 etichetta: stock SOLD_OUT, riservato a GOLD, limite raggiunto | Esaurito \| Riservato a GOLD \| Già richiesto | docs/09 §PT-03 (stati del premio) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-049 | PT-03 fascia: saldo 1.150, soglia 1.500 | non raggiunta, mancano 350 | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-050 | PT-03 fascia: saldo 1.499 (soglia − 1) | mancano 1 | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-051 | PT-03 fascia: saldo = soglia | raggiunta, barra piena | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-052 | PT-03 fascia: saldo 1.501 (soglia + 1) | raggiunta, barra non oltre il 100 % | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-053 | PT-03 fascia: saldo 0 | mancano 1.500, barra vuota | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-054 | fascia con soglia 0 | AMBIGUO — raggiunta, barra piena | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-055 | punti mancanti col separatore delle migliaia | «Ti mancano 1.350 punti» | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-056 | riservato a più livelli (GOLD, PLATINUM) | AMBIGUO — «Riservato a GOLD e PLATINUM» | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-057 | lockedByTier con elenco vuoto | AMBIGUO — non riservato | docs/09 §PT-03 (fasce) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-058 | PT-13 stato in parole: PENDING | «in conferma» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-059 | PT-13 stato in parole: CONFIRMED | «confermata» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-060 | PT-13 stato in parole: FULFILLED fisico con nota di spedizione | «spedita» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-061 | PT-13 stato in parole: CANCELLED dall'assistenza (rimborso) | «annullata — punti restituiti» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-062 | PT-13 stato in parole: REJECTED per timeout | «non andata a buon fine» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-063 | PT-13 stato in parole: REJECTED per saldo | «Punti non sufficienti» (PT-04 3b) | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-064 | PT-13 stato in parole: FULFILLED con coupon | AMBIGUO — «Coupon emesso» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-065 | PT-13 stato in parole: FULFILLED senza nota né coupon | AMBIGUO — «Completata» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-066 | PT-13 stato in parole: CANCELLED dal membro (da PENDING, nessun addebito) | AMBIGUO — «Annullata da te» | docs/09 §PT-13 · §PT-04 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-067 | PT-04 attesa: PENDING | si continua ad attendere | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-068 | PT-04 attesa: CONFIRMED premio fisico | esito positivo | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-069 | PT-04 attesa: CONFIRMED coupon senza codice | si attende il codice | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-070 | CONFIRMED coupon da verificare (needsAttention, pool vuoto) | AMBIGUO — si smette di attendere | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-071 | PT-04 attesa: FULFILLED | esito | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-072 | PT-04 attesa: REJECTED | esito negativo | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-073 | PT-04 attesa: CANCELLED | esito | docs/09 §PT-04 (flusso di richiesta) · docs/03 §5 | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-074 | PT-13 coupon: gli attivi prima, i non attivi in fondo | il coupon ISSUED in cima, USED/EXPIRED dopo | docs/09 §PT-13 (coupon) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-075 | PT-13 coupon attivi: scadenza più vicina in cima | AMBIGUO — scadenza 1 ott prima di 1 dic | docs/09 §PT-13 (coupon) | `web/lib/reward/portal.testbook.test.ts` |
| TB-WEB-RWD-076 | PT-13 stato del coupon in parole: attivo, usato, scaduto | «attivo», «usato», «scaduto» | docs/09 §PT-13 (coupon) | `web/lib/reward/portal.testbook.test.ts` |


## 15. HOME — Home del portale (PT-01)

**Regole**
- R1 (docs/09 §PT-01, docs/03 §4.3) barra verso il prossimo livello: "Ti mancano 120 punti status per GOLD"; al livello
  massimo "Hai raggiunto il livello più alto".
- R2 (docs/09 §PT-01, wallet-service §2) se `keepWarning` (da ottobre, `periodSts` < soglia del tier attuale): "Per
  mantenere GOLD servono ancora 2.350 punti status entro il 31 dic".
- R3 (docs/09 §PT-01) avviso scadenza solo se `expiringSoon.amount > 0`: "1.900 punti scadono il 31 ott — usali" → PT-03.
- R4 (docs/09 §2) membro `BLOCKED`/`INACTIVE`: banda "Il tuo profilo è sospeso: puoi consultare ma non accumulare o
  richiedere premi"; `ANONYMIZED` non selezionabile.
- R5 (docs/09 §2) servizio che dorme: la sezione degrada da sola; la tessera usa l'ultimo saldo noto con "aggiornato
  alle 10:42".
- R6 (docs/09 §PT-08, docs/07 §7) dopo l'iscrizione "+100 punti in arrivo…" finché il wallet non li accredita.

**Domini**: livello successivo presente (mancanti 1, 120, 2 350) / assente; progresso 0, 57, 100 %; `keepWarning`
presente; scadenza 1 900 con data / 0 / assente / senza data; stato del membro ACTIVE, BLOCKED, INACTIVE, ANONYMIZED;
wallet addormentato senza e con dato precedente; `?welcome=1` con saldo 0 / già accreditato.
**Strategia**: valori limite dei punti mancanti (1 = soglia − 1), ogni stato del membro, ogni classe della scadenza, uno
per stato del wallet.

**Rami del codice** (`app/portal/page.tsx`): livello successivo / massimo (R1); larghezza della barra (R1); condizione
dell'avviso scadenza (importo > 0 e data presente; R3, la data è un ramo senza specifica HOME-012); anonimizzato →
messaggio e saluto senza nome (ramo senza specifica HOME-016); `?welcome=1` con saldo 0 (R6); `QueryState` sul wallet
(R5). Regole non implementate: R2 (`keepWarning` assente da `WalletView` e dalla pagina), R4, R5 con dato precedente,
singolare "Ti manca 1 punto".


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-HOME-001 | SILVER, mancano 120 STS a GOLD | «Ti mancano 120 punti status per GOLD» | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-002 | manca 1 punto status (soglia − 1) | ~~DIVERGENZA~~ risolta (§22) — «Ti manca 1 punto status per GOLD» | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-003 | mancano 2.350 | separatore delle migliaia | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-004 | livello massimo (PLATINUM, nessun successivo) | «Hai raggiunto il livello più alto» | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-005 | barra verso il prossimo livello: appena salito (periodSts = soglia, progresso 0 %) | barra vuota | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-006 | barra verso il prossimo livello: a metà (57 %) | barra al 57 % | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-007 | barra verso il prossimo livello: livello massimo (100 %) | barra piena | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-008 | keepWarning GOLD, mancano 2.350 | ~~DIVERGENZA~~ risolta (§22) — «Per mantenere GOLD servono ancora 2.350 punti status…» | docs/09 §PT-01 · docs/03 §4.3 · wallet-service §2 (`keepWarning`) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-009 | 1.900 punti in scadenza il 31 ott | avviso «1.900 punti scadono il 31 ott — usali» verso i premi | docs/09 §PT-01 (avviso scadenza) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-010 | importo in scadenza 0 | nessun avviso | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-011 | dato di scadenza assente | nessun avviso | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-012 | importo > 0 senza data di scadenza | AMBIGUO — nessun avviso | docs/09 §PT-01 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-013 | membro BLOCKED | ~~DIVERGENZA~~ risolta (§22) — banda «Il tuo profilo è sospeso…» | docs/09 §2 (membro non attivo) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-014 | membro INACTIVE | ~~DIVERGENZA~~ risolta (§22) — banda «Il tuo profilo è sospeso…» | docs/09 §2 (membro non attivo) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-015 | membro ACTIVE | nessuna banda di sospensione | docs/09 §2 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-016 | membro ANONYMIZED | AMBIGUO — avviso di profilo anonimizzato, saluto senza nome | docs/09 §2 · F-MBR-05 · Q-120 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-017 | wallet addormentato al primo caricamento | riquadro degraded, il resto della pagina resta | docs/09 §2 (servizio che dorme) · docs/07 §6 | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-018 | wallet addormentato con saldo già noto | ~~DIVERGENZA~~ risolta (§22) — tessera con l'ultimo saldo e «aggiornato alle …» | docs/09 §2 (servizio che dorme) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-019 | saluto col nome del membro | «Ciao Giulia» | docs/09 §PT-01 (saluto col nome) | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-020 | dopo l'iscrizione (?welcome=1) con saldo ancora 0 | riga «+100 punti di benvenuto in arrivo…» | docs/09 §PT-08 (registrazione) · §2 «il saldo non mente» | `web/app/portal/page.testbook.test.tsx` |
| TB-WEB-HOME-021 | dopo l'iscrizione con i punti di benvenuto già sul saldo | nessuna riga «in arrivo» | docs/09 §PT-08 · docs/07 §7 | `web/app/portal/page.testbook.test.tsx` |


## 16. VER — Conflitto di versione negli editor

**Regole**
- R1 (docs/08 §3.2, Q-112) il `PUT` porta la `version` letta; `409 VERSION_CONFLICT` → «Qualcun altro ha modificato:
  ricarica / sovrascrivi»; *Ricarica* rilegge, *Sovrascrivi* rimanda le modifiche senza controllo di versione.

**Domini**: errore 409/VERSION_CONFLICT, 409/altro codice, 422/VERSION_CONFLICT, null, assente; versione 3, 0, null,
assente; scelta Ricarica / Sovrascrivi / in corso. **Strategia**: ogni classe da sola; versione 0 come valore limite.

**Rami del codice** (`lib/api/version.ts`, `components/bo/VersionConflict.tsx`): `isVersionConflict` (stato e codice);
`withVersion` con / senza versione; `withoutVersion`; avviso con due azioni e stato `busy`.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-VER-001 | isVersionConflict: 409 VERSION_CONFLICT | conflitto | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-002 | isVersionConflict: 409 con altro codice (APPROVAL_REQUIRED) | non è un conflitto di versione | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-003 | isVersionConflict: 422 con codice VERSION_CONFLICT | non è il 409 atteso | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-004 | isVersionConflict: nessun errore (null) | no | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-005 | isVersionConflict: nessun errore (undefined) | no | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-006 | withVersion: versione 3 | aggiunta al corpo del PUT | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-007 | withVersion: versione 0 (prima versione) | aggiunta, non scambiata per assente | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-008 | withVersion: versione null | corpo invariato | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-009 | withVersion: versione assente | corpo invariato | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-010 | Sovrascrivi | stesse modifiche senza versione (nessun controllo) | docs/08 §3.2 · Q-112 | `web/lib/api/version.testbook.test.ts` |
| TB-WEB-VER-011 | avviso con «Qualcun altro ha modificato» e le due scelte Ricarica / Sovrascrivi | «Qualcun altro ha modificato la campagna…», pulsanti Ricarica e Sovrascrivi | docs/08 §3.2 · Q-112 | `web/components/bo/VersionConflict.testbook.test.tsx` |
| TB-WEB-VER-012 | Ricarica | rilegge l'oggetto (onReload), non sovrascrive | docs/08 §3.2 · Q-112 | `web/components/bo/VersionConflict.testbook.test.tsx` |
| TB-WEB-VER-013 | Sovrascrivi | rimanda le modifiche (onOverwrite), non ricarica | docs/08 §3.2 · Q-112 | `web/components/bo/VersionConflict.testbook.test.tsx` |
| TB-WEB-VER-014 | durante il salvataggio (busy) | entrambe le scelte disabilitate | docs/08 §3.2 · Q-112 | `web/components/bo/VersionConflict.testbook.test.tsx` |


## 17. CONF — Conferme delle azioni irreversibili

**Regole**
- R1 (docs/08 §3.5) azioni irreversibili → dialogo con digitazione del codice dell'oggetto.
- R2 (docs/08 §BO-30) *Ripristina tutto*: conferma digitando `RESET`.
- R3 (docs/08 §BO-03) *Anonimizza*: conferma con digitazione dell'ID del membro.

**Domini**: testo esatto, minuscolo, una lettera in meno/in più, vuoto, null, assente, spazi ai bordi, lettere
spaziate; ID esatto, minuscolo, di un altro membro, con spazi, ID vuoto. **Strategia**: ogni classe da sola.

**Rami del codice** (`lib/demo/reset.ts`, `lib/member/anonymized.ts`): confronto dopo `trim` (spazi ai bordi tollerati:
senza specifica, CONF-008, 013); ID vuoto mai confermato.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-CONF-001 | BO-30 reset: «RESET» | confermato | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-002 | BO-30 reset: «reset» (minuscolo) | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-003 | BO-30 reset: «RESE» (una lettera in meno) | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-004 | BO-30 reset: «RESETT» (una lettera in più) | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-005 | BO-30 reset: vuoto | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-006 | BO-30 reset: null | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-007 | BO-30 reset: assente | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-008 | «  RESET  » (spazi ai bordi) | AMBIGUO — confermato | docs/08 §BO-30 · §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-009 | BO-30 reset: «R E S E T» | no | docs/08 §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-010 | BO-03 anonimizza: ID esatto | confermato | docs/08 §BO-03 (Anonimizza) · §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-011 | BO-03 anonimizza: ID in minuscolo | no | docs/08 §BO-03 (Anonimizza) · §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-012 | BO-03 anonimizza: ID di un altro membro | no | docs/08 §BO-03 (Anonimizza) · §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-013 | ID con spazi ai bordi | AMBIGUO — confermato | docs/08 §BO-03 · §3.5 | `web/lib/demo/reset.testbook.test.ts` |
| TB-WEB-CONF-014 | BO-03 anonimizza: ID membro vuoto e testo vuoto | no | docs/08 §BO-03 (Anonimizza) · §3.5 | `web/lib/demo/reset.testbook.test.ts` |


## 18. MBR — Azioni sul membro nella scheda 360°

**Regole**
- R1 (docs/08 §BO-03, docs/03 §2, F-MBR-04) menu *Blocca/Sblocca*, *Disattiva*: BLOCKED ⇄ ACTIVE, → INACTIVE;
  `member.write` (ADMIN, CARE, PERM-006…010).
- R2 (docs/03 §2, F-MBR-05, docs/08 §BO-03) ANONYMIZED è irreversibile: dopo, i campi personali mostrano "Membro anonimo"
  e le azioni sono disabilitate (Q-120 per il segnaposto).
- R3 (Q-137) per un membro INACTIVE *Blocca* e *Disattiva* sono disabilitati (nessun *Riattiva*).
- R4 (Q-138) il motivo del cambio stato è facoltativo, inviato solo se compilato.

**Domini**: stato ACTIVE, BLOCKED, INACTIVE, ANONYMIZED, non noto; motivo vuoto / soli spazi / testo; errore
addormentato / codice ignoto; nome completo / solo nickname / anonimizzato; campo vuoto / valorizzato / anonimizzato.
**Strategia**: macchina a stati del membro completa sulle voci del menu (4 stati × 2 voci, una riga per stato) + stato
non noto; ogni classe del resto da sola.

**Rami del codice** (`lib/member/status.ts`, `lib/member/anonymized.ts`): voce *Blocca*/*Sblocca* per BLOCKED; motivo di
disabilitazione per anonimizzato / inattivo (Blocca e Disattiva); `statusChangeBody` con / senza motivo;
`statusChangeErrorMessage` addormentato / MEMBER_ANONYMIZED / FORBIDDEN_ROLE / default (parole senza specifica,
MBR-008/009); `memberDisplayName` (3 casi); `personalValue` (3 casi).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-MBR-001 | ACTIVE | Blocca, Disattiva utilizzabili | docs/08 §BO-03 · docs/03 §2 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-002 | BLOCKED | Sblocca (torna ACTIVE), Disattiva | docs/08 §BO-03 · docs/03 §2 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-003 | INACTIVE | Blocca e Disattiva disabilitati (Q-137: niente Riattiva) | docs/08 §BO-03 · docs/03 §2 · Q-137 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-004 | ANONYMIZED | tutte le azioni disabilitate (irreversibile) | docs/08 §BO-03 (Anonimizza) · docs/03 §2 · F-MBR-05 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-005 | stato non noto (null o fuori elenco) | voci disabilitate (Q-206 DECISA) | docs/08 §BO-03 · docs/03 §2 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-006 | corpo del cambio stato: motivo vuoto o di soli spazi | non inviato (Q-138) | Q-138 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-007 | corpo del cambio stato: motivo compilato | inviato senza spazi ai bordi | Q-138 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-008 | errore «servizio addormentato» | messaggio che invita a riprovare a demo accesa (Q-206 DECISA) | docs/07 §6 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-009 | errore con codice non previsto | detail del problema (Q-206 DECISA) | docs/07 §6 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-010 | nome mostrato: ANONYMIZED | «Membro anonimo» (Q-120) | docs/03 §2 · Q-120 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-011 | nome mostrato: nome e cognome | «Giulia Neri» | docs/08 §3.6 (MemberChip) | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-012 | nome mostrato: senza nome | nickname | docs/08 §3.6 · Q-120 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-013 | campo personale di un anonimizzato | «Membro anonimo» | docs/08 §BO-03 (Anonimizza) · docs/03 §2 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-014 | campo personale vuoto | «—» | docs/08 §BO-03 | `web/lib/member/status.testbook.test.ts` |
| TB-WEB-MBR-015 | campo personale valorizzato | il valore | docs/08 §BO-03 | `web/lib/member/status.testbook.test.ts` |


## 19. ADJ — Rettifica punti (BO-03)

**Regole**
- R1 (docs/08 §BO-03, Q-45) dialogo con valuta, direzione, quantità, motivo (`GOODWILL`, `CORRECTION`, `COMPLAINT`,
  `TEST`), nota ≥ 10 caratteri; anteprima "saldo dopo".
- R2 (Q-46) solo PTS: gli STS non si rettificano.
- R3 (docs/03 §4.2) un addebito non porta il saldo sotto zero.
- R4 (docs/08 §BO-03, docs/07 §6 Validation) errori `INSUFFICIENT_BALANCE` e `NOTE_TOO_SHORT` sul campo.

**Domini**

| Ingresso | Valori |
|---|---|
| quantità | vuota, −5, 0 (min − 1), 1 (min), 5, 1 000 |
| nota (caratteri dopo il trim) | 9 (min − 1), 10 (min), 11 (min + 1), 9 con spazi ai bordi |
| direzione × quantità con saldo 100 | accredito; addebito 100 (= saldo), 101 (saldo + 1) |
| risposta del wallet | 200, 422 NOTE_TOO_SHORT, 422 INSUFFICIENT_BALANCE |

**Strategia**: valori limite di quantità e nota provati da soli su un modulo altrimenti valido; saldo al limite; un
caso per errore del servizio; corpo della richiesta.

**Rami del codice** (`components/bo/AdjustPointsDialog.tsx`): quantità valida (intera > 0), saldo dopo, nota ≥ 10 dopo
il trim, conferma disabilitata (4 condizioni), errore su quantità / nota / modulo.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-ADJ-001 | motivi proposti: GOODWILL, CORRECTION, COMPLAINT, TEST | esattamente GOODWILL, CORRECTION, COMPLAINT, TEST | docs/08 §BO-03 · Q-45 | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-002 | valuta: solo PTS (gli STS non si rettificano, Q-46) | selettore valuta disabilitato con la sola voce PTS | Q-46 | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-003 | quantità 0 | conferma disabilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-004 | quantità 1 e nota di 10 caratteri | conferma abilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-005 | nota di 9 caratteri | conferma disabilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-006 | nota di 11 caratteri | conferma abilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-007 | nota di 9 caratteri con spazi ai bordi | conferma disabilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-008 | addebito pari al saldo (100) | abilitata, saldo dopo 0 | docs/03 §4.2 (saldo mai negativo) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-009 | addebito oltre il saldo (101) | disabilitata, «saldo insufficiente» | docs/03 §4.2 · docs/08 §BO-03 | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-010 | quantità negativa (−5) | disabilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-011 | quantità vuota | disabilitata | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-012 | anteprima «saldo dopo» di un accredito di 1.000 su 100 | «Saldo dopo: 1.100 PTS» | docs/08 §BO-03 (Rettifica punti) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-013 | addebito oltre il saldo | anteprima negativa con «saldo insufficiente» | docs/08 §BO-03 («saldo dopo») · docs/03 §4.2 | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-014 | rifiuto NOTE_TOO_SHORT dal wallet | errore sotto la nota | docs/08 §BO-03 · docs/07 §6 (Validation) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-015 | rifiuto INSUFFICIENT_BALANCE dal wallet | errore sotto la quantità | docs/08 §BO-03 · docs/07 §6 (Validation) | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |
| TB-WEB-ADJ-016 | invio: POST /v1/wallets/{id}/adjustments con currency PTS, direzione, quantità intera, motivo e nota ripulita | `POST /api/lh/wallet/v1/wallets/MBR-000002/adjustments` `{currency: PTS, direction: CREDIT, amount: 5, reason: GOODWILL, note: <senza spazi ai bordi>}` | docs/08 §BO-03 · wallet-service §3 · Q-46 | `web/components/bo/AdjustPointsDialog.testbook.test.tsx` |


## 20. HUB — Demo Hub

**Regole**
- R1 (docs/07 §8) due ingressi attivi quando ingestion, member, campaign e wallet sono `UP`; stato non noto → bloccati
  (Q-133).
- R2 (docs/07 §8) tessere `SLEEPING`/`WAKING`/`UP`/`DOWN`; durante "Accendi la demo" ciò che non è UP appare WAKING;
  "pronti N/10".
- R3 (docs/07 §8, Q-134) polling di `/api/demo/status` ogni 3 s dopo "Accendi la demo"; 8 s a regime; fermo se il
  keep-alive è fermo per inattività.
- R4 (docs/07 §8, Q-135) Kafka `DOWN` **per più di 2 min** → riquadro "riaccendi dalla console"; `SLEEPING` non conta.
- R5 (docs/07 §8) tempo trascorso sotto la barra.

**Domini**: 4 servizi core UP / non UP; stato assente; 4 stati × risveglio sì/no; pronti 9/10, 10/10; risveglio ×
inattività; durata DOWN 119 999 / 120 000 / 120 001 ms, SLEEPING, ritorno UP; tempo 0, 61 s, negativo.
**Strategia**: tabella **completa** 2⁴ = 16 sui servizi core (i 4 non core sempre addormentati: non contano); completa
4 × 2 = 8 per la tessera; completa 2 × 2 = 4 per il polling; valori limite dei 2 minuti.

**Rami del codice** (`lib/api/status.ts`): `entrancesReady` stato assente / core UP; `displayState` UP / altro in
risveglio; `wakeComplete`; `statusPollInterval` inattivo / risveglio / regime; `trackKafkaDown` non DOWN / inizio;
`kafkaLongDown`; `formatElapsed` (valore negativo: ramo senza specifica, HUB-039).


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-HUB-001 | core UP: nessuno | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-002 | core UP: ingestion | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-003 | core UP: member | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-004 | core UP: ingestion, member | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-005 | core UP: campaign | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-006 | core UP: ingestion, campaign | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-007 | core UP: member, campaign | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-008 | core UP: ingestion, member, campaign | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-009 | core UP: wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-010 | core UP: ingestion, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-011 | core UP: member, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-012 | core UP: ingestion, member, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-013 | core UP: campaign, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-014 | core UP: ingestion, campaign, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-015 | core UP: member, campaign, wallet | ingressi bloccati | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-016 | core UP: ingestion, member, campaign, wallet | ingressi attivi | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-017 | stato non ancora noto (in caricamento o in errore) | ingressi bloccati (Q-133) | docs/07 §8 · Q-133 | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-018 | tessera: UP, senza risveglio in corso | UP | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-019 | tessera: UP, durante «Accendi la demo» | UP | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-020 | tessera: WAKING, senza risveglio in corso | WAKING | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-021 | tessera: WAKING, durante «Accendi la demo» | WAKING | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-022 | tessera: DOWN, senza risveglio in corso | DOWN | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-023 | tessera: DOWN, durante «Accendi la demo» | WAKING | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-024 | tessera: SLEEPING, senza risveglio in corso | SLEEPING | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-025 | tessera: SLEEPING, durante «Accendi la demo» | WAKING | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-026 | 9/10 pronti | risveglio non concluso | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-027 | 10/10 pronti | risveglio concluso | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-028 | dopo «Accendi la demo» | polling ogni 3 s | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-029 | a regime | ogni 8 s (Q-134) | Q-134 | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-030 | keep-alive fermo per inattività | nessun polling (Q-134) | Q-134 | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-031 | inattivo durante il risveglio | nessun polling (Q-134) | Q-134 | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-032 | Kafka DOWN da 1 min 59,999 s | nessun riquadro | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-033 | Kafka DOWN da esattamente 2 min | nessun riquadro («più di 2 min») | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-034 | Kafka DOWN da 2 min + 1 ms | riquadro «riaccendi dalla console» | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-035 | Kafka SLEEPING (ingestion irraggiungibile) | non conta come DOWN (Q-135) | docs/07 §8 · Q-135 | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-036 | Kafka torna UP | il conteggio riparte da zero | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-037 | tempo trascorso 0 ms | AMBIGUO — «0:00» | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-038 | tempo trascorso 61 s | AMBIGUO — «1:01» | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |
| TB-WEB-HUB-039 | tempo trascorso valore negativo | AMBIGUO — «0:00» | docs/07 §8 (HUB-01) | `web/lib/api/status.testbook.test.ts` |


## 21. KA — Keep-alive gentile

**Regole**
- R1 (docs/07 §8, F-DEMO-07) ogni 4 min chiama `/api/demo/wake` **solo se** la scheda è visibile; si ferma dopo 45 min
  senza interazione.
- R2 (Q-132) una nuova interazione lo fa ripartire con cadenza da capo, senza risveglio immediato.

**Domini**: tempo 3:59 / 4:00, 44 min, 48 min, oltre; scheda visibile / nascosta; interazione a 40 min, dopo lo stop;
smontaggio. **Strategia**: valori limite dei 4 e dei 45 minuti, ogni evento da solo.

**Rami del codice** (`lib/keepalive/keepAlive.ts`): `tick` fermo per inattività / scheda nascosta / risveglio;
`markInteraction` dopo lo smontaggio / ripartenza dallo stop; `dispose`.


| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-WEB-KA-001 | scheda visibile: nessuna chiamata a 3:59, una a 4:00 | 0 chiamate a 3:59, 1 a 4:00 | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-002 | scheda nascosta | nessuna chiamata a 4 min | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-003 | senza interazioni: l'ultimo risveglio è a 44 min (sotto i 45) | 11 chiamate in 44 min, non fermo | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-004 | oltre 45 min senza interazioni (controllo dei 48 min) | fermo, nessun'altra chiamata | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-005 | interazione dopo lo stop | riparte, senza risveglio immediato, poi ogni 4 min (Q-132) | docs/07 §8 · Q-132 | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-006 | un'interazione a 40 min sposta la finestra: a 48 min si chiama ancora | non fermo, 12 chiamate a 48 min | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |
| TB-WEB-KA-007 | smontato (dispose) | nessuna chiamata | docs/07 §8 (keep-alive, F-DEMO-07) | `web/lib/keepalive/keepAlive.testbook.test.ts` |


## 22. Registro delle divergenze

Test che asserivano la specifica ed erano rossi. **Tutte risolte** correggendo il codice di produzione (i test sono
rimasti quelli del testbook; unica modifica: LIFE-052 conferma il dialogo che D5 introduce prima dell'invio). Numerazione
propria (la prima colonna non è l'ID di riga); la colonna «Causa» riporta i riferimenti prima della correzione.

| # | Righe | Specifica | Osservato | Causa (file:riga) | Correzione |
|---|---|---|---|---|---|
| D1 | TB-WEB-NAV-022 | docs/08 §1: voce BO-24 «Flusso live» | «Flusso eventi live» | `web/lib/nav.ts:82` | `web/lib/nav.ts`: etichetta «Flusso live» |
| D2 | TB-WEB-NAV-045 | docs/08 §1: contatore su *Approvazioni* (oggetti `IN_REVIEW`) | BO-21 senza `counter`; `NavLinks` conosce solo `redemptions` e `dlq` | `web/lib/nav.ts:74`, `web/components/bo/NavLinks.tsx:37` | `web/lib/nav.ts` (`counter: "approvals"`), `web/components/bo/NavLinks.tsx` (`ApprovalsCounter`: tre code `GET /v1/approvals?status=IN_REVIEW`, ogni 30 s) |
| D3 | TB-WEB-CAN-004 | docs/07 §4, §6: azione non consentita **disabilitata** | pulsante solo avvolto in `pointer-events: none` con tooltip: resta attivabile da tastiera (Tab + Invio) e da tecnologie assistive | `web/components/bo/Can.tsx:21-22` | `web/components/bo/Can.tsx`: controllo nativo `disabled` + `aria-disabled`, link `aria-disabled` fuori dal Tab, altro contenuto in `<fieldset disabled>` |
| D4 | TB-WEB-LIFE-053 | docs/08 §3.3: IN_REVIEW, per chi non può decidere "In attesa di LEGAL da 2 h" | solo *Approva*/*Rifiuta* disabilitati, nessun testo d'attesa | `web/components/bo/LifecycleBar.tsx:20-23`, `:73-84` | `web/components/bo/LifecycleBar.tsx` (props `requiredRole`, `submittedAt`), `web/lib/approvals/usePolicy.ts` (`useReviewEntry`), `web/lib/format/dates.ts` (`formatElapsed`); cablato in BO-06 e BO-10 |
| D5 | TB-WEB-LIFE-054 | docs/08 §3.3: ogni transizione apre un dialogo con commento → `POST {action, comment}` | solo *Rifiuta* ha il commento; le altre transizioni partono al clic senza dialogo | `web/components/bo/LifecycleBar.tsx:75-78` | `web/components/bo/LifecycleBar.tsx`: `TransitionDialog` per ogni transizione (commento facoltativo, obbligatorio per il rifiuto); LIFE-052 conferma ora il dialogo prima dell'invio |
| D6 | TB-WEB-PILL-004, 006, 010, 011 | docs/07 §5.2: SCHEDULED indaco, PAUSED arancio, ARCHIVED grigio chiaro distinto da ENDED slate | SCHEDULED senza voce (grigio di default); PAUSED ambra come IN_REVIEW; ENDED e ARCHIVED con le stesse classi | `web/components/bo/primitives.tsx:9-40` (`:20`, `:25-26`) | `web/components/bo/primitives.tsx`: SCHEDULED indaco, PAUSED arancio, ARCHIVED grigio chiaro |
| D7 | TB-WEB-QST-002 | docs/07 §6 Loading: tabelle con 8 righe scheletro | 5 righe per ogni vista | `web/components/shared/QueryState.tsx:53` | `web/components/shared/QueryState.tsx`: `skeletonRows` = 8 |
| D8 | TB-WEB-QST-004 | docs/07 §6 Empty: icona + frase + **azione primaria** | `EmptyState` ha solo titolo e suggerimento | `web/components/shared/QueryState.tsx:60-67` | `web/components/shared/QueryState.tsx`: prop facoltativa `emptyAction`; senza, *Aggiorna* (rilancia la query) |
| D9 | TB-WEB-QST-008 | docs/07 §6 Error: `title` del problema RFC 9457 | mostra "Errore: <code>"; il client scarta `title` quando c'è `detail` | `web/lib/api/client.ts:59`, `web/components/shared/QueryState.tsx:32` | `web/lib/api/client.ts` (`LhError.title`), `web/components/shared/QueryState.tsx` (`ErrorBox`) |
| D10 | TB-WEB-QST-009 | docs/07 §6 Error: `correlationId` copiabile | `LhError` non porta la correlazione, il riquadro non la mostra | `web/lib/api/client.ts:15-24`, `web/components/shared/QueryState.tsx:30-42` | `web/lib/api/client.ts` (`LhError.correlationId` dal problema o da `X-Correlation-Id`), `ErrorBox` con *copia* |
| D11 | TB-WEB-QST-010, 011 | docs/07 §6 Degraded: "Il servizio *wallet* si sta svegliando…" con barra; riprova automatica ogni 5 s fino a 90 s | "Servizio «wallet» non raggiungibile… Accendi la demo dal Demo Hub"; solo *Riprova* manuale | `web/components/shared/QueryState.tsx:27-29`, `:69-83` | `web/components/shared/QueryState.tsx` (`DegradedBox`: testo della spec, barra indeterminata, `autoRetry` 5 s × 90 s) |
| D12 | TB-WEB-HOME-018 | docs/09 §2: servizio che dorme → la tessera usa l'ultimo saldo noto con "aggiornato alle 10:42" | con dato precedente e servizio addormentato la sezione mostra solo il riquadro degraded | `web/components/shared/QueryState.tsx:26-29` (l'errore prevale sul dato), `web/app/portal/page.tsx:64` | `web/components/shared/QueryState.tsx`: con un dato già noto e servizio addormentato, riquadro degraded + «aggiornato alle HH:MM» + ultimo dato |
| D13 | TB-WEB-HOME-002 | docs/09 §2 (linguaggio del cliente) e §PT-01: "Ti mancano N punti status" | "Ti mancano 1 punti status" (nessun singolare) | `web/app/portal/page.tsx:76` | `web/app/portal/page.tsx`: «Ti manca 1 punto status» |
| D14 | TB-WEB-HOME-008 | docs/09 §PT-01, wallet-service §2: avviso `keepWarning` "Per mantenere GOLD servono ancora …" | `keepWarning` assente da `WalletView` e dalla pagina (regola non implementata) | `web/lib/api/types.ts:129-137`, `web/app/portal/page.tsx:73-83` | `web/app/portal/page.tsx`: avviso `keepWarning`; la data «entro il …» è la fine dell'edizione `ACTIVE` (`wallet GET /v1/editions`), omessa se non nota |
| D15 | TB-WEB-HOME-013, 014 | docs/09 §2: membro BLOCKED/INACTIVE → banda "Il tuo profilo è sospeso…" e azioni disabilitate | nessuna banda (solo il caso ANONYMIZED) | `web/app/portal/page.tsx:55-60` | `web/app/portal/page.tsx`: banda «Il tuo profilo è sospeso…» per BLOCKED/INACTIVE |
| D16 | TB-WEB-DESC-001, 007 | docs/08 §BO-06: "…**Acquisto completato** da ecommerce o app…" | le fonti ammesse non entrano nella frase (`CampaignDraft` non ha fonti) | `web/lib/campaign/describe.ts:4-12`, `:99-102` | `web/lib/campaign/describe.ts` (`CampaignDraft.sources`). **Dato mancante**: campaign-service non modella le fonti ammesse, le viste non possono passarle → Q-208 |
| D17 | TB-WEB-DESC-001, 008 | "se **importo ≥ 50 €**" | "importo ≥ 50" | `web/lib/campaign/describe.ts:145-148` | `web/lib/campaign/describe.ts`: «€» dopo il valore di `data.amount` |
| D18 | TB-WEB-DESC-001, 037 | "assegna **1 giocata a Ruota d'Autunno**" | "1 giocata su IW-AUTUNNO" (codice, preposizione «su», nessun plurale: "2 giocata") | `web/lib/campaign/describe.ts:166` | `web/lib/campaign/describe.ts` (`contestNames`, plurale, «a»), `web/components/bo/GeneratedSentence.tsx` (nomi da `gamification GET /v1/contests`) |
| D19 | TB-WEB-DESC-001, 045, 046 | "al massimo **1 volta al giorno**" | "1 volta/e al giorno", "2 volta/e alla settimana" | `web/lib/campaign/describe.ts:182` | `web/lib/campaign/describe.ts`: «1 volta» / «N volte» |
| D20 | TB-WEB-DESC-034, 035 | docs/08 §BO-06 (rilettura in italiano) · docs/03 §3.4: FROM_FIELD e LOOKUP leggono `amountField` | "PTS (FROM_FIELD)", "PTS (LOOKUP)": codice del modo, nessun campo | `web/lib/campaign/describe.ts:157-161` | `web/lib/campaign/describe.ts`: «PTS pari al campo points», «PTS dalla tabella sul campo plan» |
| D21 | TB-WEB-FMT-010 | docs/07 §9: punti formattati, zero = "0" | −0 → "-0" (Intl mostra il segno dello zero negativo) | `web/lib/format/points.ts:5`, `:9-11` | `web/lib/format/points.ts`: zero sempre senza segno |
| D22 | TB-WEB-FMT-013, 014, 015 | docs/07 §9: valute `€ 129,90` | "129,90 €", "1234,50 €" (simbolo dopo, nessun separatore a 4 cifre); `formatEuro` oggi non è usato da nessuna vista | `web/lib/format/points.ts:6`, `:13-15` | `web/lib/format/points.ts`: «€ 129,90», «€ 1.234,50» (simbolo davanti, separatore sempre) |
| D23 | TB-WEB-FMT-034 | docs/07 §9: relative sotto le 24 h | 23 h 40 min → arrotondato a 24 → data estesa | `web/lib/format/dates.ts:45-46` | `web/lib/format/dates.ts`: ore per difetto (23 h 40 min → «23 h fa») |

## 23. Ambiguità (righe AMBIGUO)

Nessuna fonte decide: il test fissa il comportamento attuale, che non è stato cambiato. Ogni gruppo ha una voce in
`docs/15` (SPEC-GAP, `Q-186…Q-207`); dove il comportamento attuale non è il più prudente la voce lo dice e propone
l'alternativa, senza implementarla (A1 → Q-186, A12 → Q-197, A21 → Q-206).

| # | Righe | Dubbio | Comportamento fissato | Domanda |
|---|---|---|---|---|
| A1 | TB-WEB-PERS-015 | ruolo fuori dai 5 nel cookie | DECISA: letto come ANALYST; il layout del backoffice e il proxy usano lo stesso ruolo del cookie | Q-186 |
| A2 | TB-WEB-PERS-028 | username fuori dalle 5 personas | ruolo ANALYST | Q-187 |
| A3 | TB-WEB-PERS-033…035 | codice del rifiuto di una persona non valida | 400 `INVALID_PERSONA` | Q-188 |
| A4 | TB-WEB-PRX-015 | servizio inesistente nel percorso del proxy | 404 `UNKNOWN_SERVICE` | Q-189 |
| A5 | TB-WEB-CLI-005 | errore senza problema RFC 9457 | codice `HTTP_<status>` | Q-190 |
| A6 | TB-WEB-NAV-041, 042 | voce attiva per un percorso senza voce | Dashboard | Q-191 |
| A7 | TB-WEB-LIFE-041…045, TB-WEB-APR-018 | policy delle approvazioni non nota (servizio che dorme) | DRAFT offre *Invia in revisione* e *Pubblica*; il servizio risponde 409 `APPROVAL_REQUIRED` (LIFE-052) | Q-192 |
| A8 | TB-WEB-APR-025 | voce di coda senza `requiredRole` | vale LEGAL | Q-193 |
| A9 | TB-WEB-APR-030, 032 | ordine delle schede di BO-21 | *Da approvare* dal più vecchio (poi per codice), *Inviate da me* dal più recente | Q-194 |
| A10 | TB-WEB-APR-033…040 | parole dell'esito e formato dell'attore in BO-21 | "In attesa", "Rifiutato", "Approvato (e pubblicato)", "username (RUOLO)" | Q-195 |
| A11 | TB-WEB-DESC-005, 006, 030, 038…043, 051 | frase per tipo senza nome, bozza senza trigger/effetti, segmenti, coupon/badge/messaggio, più limiti | codice del tipo, "un'azione", codici dei segmenti, "un coupon"/"un badge"/"il messaggio …", solo il primo limite | Q-196 |
| A12 | TB-WEB-COND-042, 046 | catalogo dei campi parziale (servizio che dorme) | il trigger senza campi è ignorato nell'intersezione e negli avvisi | Q-197 |
| A13 | TB-WEB-COND-087…089 | percorso scritto a mano | ammesso se inizia con `data.`/`member.`/`context.`/`history.` | Q-198 |
| A14 | TB-WEB-FMT-011, 012 | punti decimali | arrotondamento it-IT a 0 decimali | Q-199 |
| A15 | TB-WEB-FMT-027, 031, 032, 033, 036 | data assente, sotto il minuto, forma delle ore, futuro | "—", "ora", "N h fa", "ora" | Q-200 |
| A16 | TB-WEB-RWD-054, 056, 057 | fascia a 0 punti, premio riservato a più livelli, `lockedByTier` vuoto | raggiunta; "Riservato a GOLD e PLATINUM"; non riservato | Q-201 |
| A17 | TB-WEB-RWD-064…066, 070, 075 | parole di stati non elencati in PT-13, coupon che non arriva, ordine dei coupon attivi | "Coupon emesso", "Completata", "Annullata da te"; attesa conclusa; scadenza più vicina prima | Q-202 |
| A18 | TB-WEB-HOME-012, 016 | scadenza senza data; membro anonimizzato come persona attiva | nessun avviso; messaggio di profilo anonimizzato | Q-203 |
| A19 | TB-WEB-QST-006 | vista senza regola di vuoto | contenuto (vuoto) invece dello stato empty | Q-204 |
| A20 | TB-WEB-CONF-008, 013 | spazi ai bordi del codice digitato | tollerati | Q-205 |
| A21 | TB-WEB-MBR-005, 008, 009 | stato del membro non noto; parole degli errori | DECISA: voci disabilitate; testi del codice | Q-206 |
| A22 | TB-WEB-HUB-037…039 | formato del tempo trascorso, valore negativo | "m:ss", negativo → "0:00" | Q-207 |

## 24. Rami senza specifica e regole non implementate

**Rami del codice senza specifica** (tutti con riga AMBIGUO, vedi §23): PERS-015, 028, 033–035; PRX-015; CLI-005;
NAV-041/042; LIFE-041…045; APR-025, 030, 032, 033–040; DESC-005, 006, 030, 038–043, 051; COND-042, 046, 087–089;
FMT-011, 012, 027, 031–033, 036; RWD-054, 056, 057, 064–066, 070, 075; HOME-012, 016; QST-006; CONF-008, 013; MBR-005,
008, 009; HUB-039.

**Regole della specifica senza codice** (erano righe rosse, §22): ora implementate — contatore *Approvazioni* (D2);
attesa "In attesa di LEGAL" (D4); dialogo con commento per ogni transizione (D5); pill SCHEDULED (D6); 8 righe
scheletro, azione primaria del vuoto, `title` e `correlationId` dell'errore, testo e riprova automatica del degraded
(D7–D11); ultimo saldo noto (D12); `keepWarning` (D14); banda del membro non attivo (D15); «€», nome del concorso, campo
di FROM_FIELD/LOOKUP nella frase (D17, D18, D20). Le fonti ammesse (D16) entrano nella frase quando la bozza le porta,
ma campaign-service non le espone: dato mancante, Q-208.
**Senza codice e senza riga** (nessuna unità web da provare): stato *Stale* ("aggiornato alle" dopo 60 s senza SSE,
docs/07 §6); colore semantico dei punti per tipo di movimento (docs/07 §5.2: spesa ambra, scadenza rossa, STS viola —
`PointsAmount` distingue solo il segno); *Storico* delle transizioni in popover accanto alla barra (docs/08 §3.3; lo
storico è in `ApprovalHistoryList` di BO-21).

## 25. Copertura

| Area | Righe | Regole | Rami del codice mappati | Combinazioni ridotte |
|---|---|---|---|---|
| PERM | 111 | 4 | 23 | nessuna (matrice completa 21 × 5) |
| CAN | 4 | 2 | 3 | — |
| NAV | 52 | 5 | 8 | — |
| PERS | 35 | 3 | 14 | classi non valide una per riga |
| PRX | 15 | 3 | 9 | guasto singolo |
| CLI | 7 | 2 | 11 | guasto singolo |
| QST | 12 | 4 | 5 | — |
| LIFE | 62 | 6 | 24 | policy solo in DRAFT (45 righe invece di 7 × 5 × 3 = 105) |
| PILL | 12 | 1 | 10 | — |
| APR | 40 | 4 | 21 | CAMPAIGN completa 3 × 4; altri tipi solo per policy |
| DESC | 52 | 3 | 37 | classi una per volta su bozza minima (prodotto > 10⁴) |
| COND | 89 | 6 | 78 | valori limite e classi una per volta |
| FMT | 48 | 3 | 14 | valori limite uno per riga |
| RWD | 76 | 6 | 27 | nessuna: 36 e 12 complete |
| HOME | 21 | 6 | 11 | classi una per volta |
| VER | 14 | 1 | 8 | — |
| CONF | 14 | 3 | 4 | — |
| MBR | 15 | 4 | 17 | — |
| ADJ | 16 | 4 | 7 | valori limite su modulo valido |
| HUB | 39 | 5 | 12 | nessuna: 16, 8 e 4 complete |
| KA | 7 | 2 | 6 | — |
| **Totale** | **741** | **77** | **349** | 5 tabelle complete, 4 riduzioni dichiarate |

- Righe: **741**, di cui **33** DIVERGENZA (23 cause, §22, tutte risolte: 0 righe rosse) e **67** AMBIGUO (§23,
  `Q-186…Q-207`).
- Rami del codice senza specifica: 22 gruppi (§23–24). Regole non implementate: 16 con riga rossa, 3 senza unità da
  provare (§24).
- Esecuzione: `cd web && pnpm -s exec vitest run testbook` (un caso per riga) e `TESTBOOK_JAVA=0 bash scripts/testbook.sh`.
