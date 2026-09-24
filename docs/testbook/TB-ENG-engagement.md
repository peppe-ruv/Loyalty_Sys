# Testbook: Engagement

Questo testbook copre la logica del servizio di engagement, che funge da CMS del programma. I riferimenti incrociati includono i requisiti funzionali (F-CNT-*, F-MSG-*, F-THM-01, F-WBH-01), i requisiti di backoffice (BO-18..20, BO-23), quelli del portale (PT-12), il dominio (docs/03 §9) e le Domande Aperte (Q-70, Q-71, Q-72, Q-98, Q-99).

---

## 1. Selezione Contenuti (TB-ENG-CNT)

**Testo della regola:** Un contenuto per essere idoneo (`eligible`) deve avere stato `LIVE`, rientrare nel calendario (`start_at` ≤ oggi ≤ `end_at`), e l'audience (livelli, segmenti, stati, `daysOfWeek`, `registeredWithinDays`) deve essere soddisfatta da chi lo guarda. La lista finale è ordinata per `priority` decrescente, e a parità, per `code` crescente. Vengono poi imposti dei limiti per i placement (`HOME_HERO`: 1, `HOME_GRID`: 6, ecc.).

**Riferimenti Spec:** docs/03 §9, F-CNT-01/04, Q-71, Q-72.

**Input e Domini di Classe:**
- `status`: LIVE, DRAFT, ENDED
- `schedule` (now = Instant): before (start_at > now), in (start_at <= now <= end_at), after (end_at < now)
- `tiers`: assente, soddisfatto (viewer in tiers), non soddisfatto (viewer non in tiers)
- `segments`: assente, soddisfatto (almeno uno in comune), non soddisfatto
- `statuses`: assente, soddisfatto, non soddisfatto
- `registeredWithinDays` (Q-71): assente, nei limiti (iscrizione a meno di N giorni fa), fuori limiti
- `daysOfWeek` (Q-71): assente, soddisfatto, non soddisfatto

**Combinazioni (Single Fault / Boundary):**
A partire da un contenuto base valido (LIVE, in schedule, tutti target assenti = valid), testiamo singolarmente ogni difetto (single-fault).

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-CNT-001 | Base valid: LIVE, no dates, no audience limits | Visible | docs/03 §9 | `TestbookEngContentTest` |
| TB-ENG-CNT-002 | status = DRAFT | Excluded (NOT_LIVE) | docs/03 §9 | CSV |
| TB-ENG-CNT-003 | status = ENDED | Excluded (NOT_LIVE) | docs/03 §9 | CSV |
| TB-ENG-CNT-004 | schedule = before start | Excluded (OUT_OF_SCHEDULE) | docs/03 §9 | CSV |
| TB-ENG-CNT-005 | schedule = after end | Excluded (OUT_OF_SCHEDULE) | docs/03 §9 | CSV |
| TB-ENG-CNT-006 | schedule = boundary at start | Visible | docs/03 §9 | CSV |
| TB-ENG-CNT-007 | schedule = boundary at end | Excluded (OUT_OF_SCHEDULE) | docs/03 §9 | CSV |
| TB-ENG-CNT-008 | tier = GOLD, PLATINUM, viewer = GOLD | Visible | docs/03 §9 | CSV |
| TB-ENG-CNT-009 | tier = GOLD, PLATINUM, viewer = SILVER | Excluded (NOT_IN_AUDIENCE) | docs/03 §9 | CSV |
| TB-ENG-CNT-010 | tier = non empty, viewer = null | Excluded (NOT_IN_AUDIENCE) | docs/03 §9 | CSV |
| TB-ENG-CNT-011 | segments = [A, B], viewer = [B, C] | Visible | docs/03 §9 | CSV |
| TB-ENG-CNT-012 | segments = [A, B], viewer = [C] | Excluded (NOT_IN_AUDIENCE) | docs/03 §9 | CSV |
| TB-ENG-CNT-013 | status limit = [ACTIVE], viewer = ACTIVE | Visible | docs/03 §9 | CSV |
| TB-ENG-CNT-014 | status limit = [ACTIVE], viewer = INACTIVE | Excluded (NOT_IN_AUDIENCE) | docs/03 §9 | CSV |
| TB-ENG-CNT-015 | daysOfWeek = [MON, TUE], today = MON | Visible | Q-71 | CSV |
| TB-ENG-CNT-016 | daysOfWeek = [MON, TUE], today = WED | Excluded (NOT_IN_AUDIENCE) | Q-71 | CSV |
| TB-ENG-CNT-017 | registeredWithinDays = 7, registered 5d ago | Visible | Q-71 | CSV |
| TB-ENG-CNT-018 | registeredWithinDays = 7, registered 10d ago | Excluded (NOT_IN_AUDIENCE) | Q-71 | CSV |
| TB-ENG-CNT-019 | Priority ties break: 3 items, A (prio 10, code "Z"), B (prio 20, code "X"), C (prio 10, code "A"). Limit 2. | B, poi C | docs/03 §9 | `TestbookEngContentTest` |
| TB-ENG-CNT-020 | Limite CATALOG_TOP (Q-72) = 1. Presenti 2 validi. | Solo 1 restituito. | Q-72 | `TestbookEngContentTest` |

---

## 2. Pop-up Frequenza (TB-ENG-POP)

**Testo della regola:** Oltre alle condizioni di selezione dei contenuti, i Pop-up aggiungono il blocco di frequenza (frequency) misurato in `Europe/Rome`. `ONCE`: visibile solo se `lastSeen` è null. `ONCE_PER_DAY`: visibile se `lastSeen` è precedente ad oggi. `ALWAYS`: sempre visibile.

**Riferimenti Spec:** docs/03 §9

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-POP-001 | ONCE, lastSeen = null | Visible | docs/03 §9 | CSV |
| TB-ENG-POP-002 | ONCE, lastSeen = today | Excluded (FREQUENCY) | docs/03 §9 | CSV |
| TB-ENG-POP-003 | ONCE_PER_DAY, lastSeen = yesterday | Visible | docs/03 §9 | CSV |
| TB-ENG-POP-004 | ONCE_PER_DAY, lastSeen = today | Excluded (FREQUENCY) | docs/03 §9 | CSV |
| TB-ENG-POP-005 | ALWAYS, lastSeen = today | Visible | docs/03 §9 | CSV |

---

## 3. Template Engine (TB-ENG-TPL)

**Testo della regola:** Sostituzione `{{path}}` o `{{path|formatter}}`. Formattatori ammessi: `number`, `date`. Radici: `data, member, event`. Path non trovati → vuoto con log WARN. Errori di parsing su number/date tornano la stringa originale con WARN.

**Riferimenti Spec:** docs/servizi/engagement-service.md §5, docs/03 §9, F-MSG-02

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-TPL-001 | Placeholder normale: {{data.amount}} (valore 1500) | "1500" | docs/03 §9 | CSV |
| TB-ENG-TPL-002 | Placeholder formattato: {{data.amount\|number}} | "1.500" | §5 | CSV |
| TB-ENG-TPL-003 | Placeholder formattato decimale: {{data.rate\|number}} (1.25) | "1,25" | §5 | CSV |
| TB-ENG-TPL-004 | Placeholder data ISO: {{data.exp\|date}} (2026-10-31) | "31 ottobre 2026" | §5 | CSV |
| TB-ENG-TPL-005 | Placeholder mancante: {{data.missing}} | "" | §5 | CSV |
| TB-ENG-TPL-006 | Placeholder root invalid (non in ROOTS): {{boh.value}} | "" | §5 | CSV |
| TB-ENG-TPL-007 | Placeholder nested array: {{data.items.0.name}} | Il valore | §5 | CSV |
| TB-ENG-TPL-008 | Data non valida: {{data.inv\|date}} (CIAO) | "CIAO" (fallback) | §5 | CSV |
| TB-ENG-TPL-009 | Syntax errata validation: "graffe sbilanciate {{ " | Error in problems list | §5 | `TestbookEngTemplateTest` |
| TB-ENG-TPL-010 | Formatter sconosciuto: {{data.v\|magic}} | Restituisce il valore senza format | §5 | CSV |

---

## 4. Regole di Notifica e Condizioni (TB-ENG-CND)

**Testo della regola:** L'emissione di notifiche verifica le condizioni (DataCondition) di `notification_rule`. Operatori supportati tra comparatori e booleani nidificati.

**Riferimenti Spec:** docs/servizi/engagement-service.md §5

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-CND-001 | Op: eq. Actual = 10, value = 10 | True | §5 | CSV |
| TB-ENG-CND-002 | Op: gt. Actual = 10, value = 5 | True | §5 | CSV |
| TB-ENG-CND-003 | Op: in. Actual = "A", value = ["A", "B"] | True | §5 | CSV |
| TB-ENG-CND-004 | Op: contains su Array. Actual = ["A", "B"], value = "B" | True | §5 | CSV |
| TB-ENG-CND-005 | Op: exists. Actual present, value ignored | True | §5 | CSV |
| TB-ENG-CND-006 | Op: nexists. Actual absent | True | §5 | CSV |
| TB-ENG-CND-007 | Group: all. [{eq: 1}, {eq: 2}] su actual 1 | False | §5 | `TestbookEngConditionTest` |
| TB-ENG-CND-008 | Nested path non esistente, comparatore eq | False | §5 | CSV |

---

## 5. Inbox Lifecycle (TB-ENG-IBX)

**Testo della regola:** Generazione messaggi da eventi. deduplica su `(memberId, sourceEventId, templateCode)`. `ANONYMIZED` members non ricevono.
**Riferimenti Spec:** Q-70, docs/03 §9

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-IBX-001 | Evento duplicato (stesso sourceEventId) | Deduplicato, no nuovo messaggio | docs/03 §9 | `TestbookEngInboxIT` |
| TB-ENG-IBX-002 | Membro ANONYMIZED | Nessun messaggio inviato | Q-70 | `TestbookEngInboxIT` |
| TB-ENG-IBX-003 | Membro BLOCKED | Messaggio inviato | Q-70 | `TestbookEngInboxIT` |
| TB-ENG-IBX-004 | Regola disabilitata | Nessun messaggio inviato | F-MSG-02 | `TestbookEngInboxIT` |
| TB-ENG-IBX-005 | Conteggio unread | Aggiornato correttamente | PT-12 | `TestbookEngInboxIT` |

---

## 6. Tema (TB-ENG-THM)

**Testo della regola:** Validazione stringhe HEX. Il contrasto tra il testo (night) e primary / bg deve essere >= 4.5.
**Riferimenti Spec:** BO-20, Q-79

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-THM-001 | #FFFFFF (bg) vs #000000 (night) contrast | >= 4.5 (Valid) | BO-20 | CSV |
| TB-ENG-THM-002 | #555555 (bg) vs #555555 (night) contrast | < 4.5 (Invalid) | BO-20 | CSV |
| TB-ENG-THM-003 | Hex invalido: "red" o "000000" (no hash) | Invalid Hex format | BO-20 | `TestbookEngThemeTest` |

---

## 7. Webhook (TB-ENG-WBH)

**Testo della regola:** Signature HMAC SHA-256. Ritenti previsti (0 -> 1m, 1 -> 5m, 2 -> 15m, >2 -> GAVE_UP). Riprova manuale è un colpo secco. Indirizzi privati esclusi salvo config.
**Riferimenti Spec:** docs/servizi/engagement-service.md §5, Q-98, Q-99

| ID | Condizioni / Valori | Atteso (da spec) | Rif | Test |
|---|---|---|---|---|
| TB-ENG-WBH-001 | Calcolo Signature Payload + Secret | Verifica corretta base64 sha256 | §5 | `TestbookEngWebhookIT` |
| TB-ENG-WBH-002 | Ritentativo step 0 -> fallimento | nextAt = now + 1 min | §5 | CSV / `TestbookEngWebhookIT` |
| TB-ENG-WBH-003 | Ritentativo step 2 -> fallimento | Status = GAVE_UP | §5 | CSV |
| TB-ENG-WBH-004 | Riprova manuale su GAVE_UP fallisce | Rimane GAVE_UP senza re-schedule | Q-98 | `TestbookEngWebhookIT` |
| TB-ENG-WBH-005 | URL con IP Privato o Localhost in non-local | Rejection SSRF | Q-99 | CSV |
| TB-ENG-WBH-006 | Disattivato globalmente | Webhook ignorato (no deliver) | BO-23 | `TestbookEngWebhookIT` |

---

## 8. Copertura
- **Regole coperte:** Selezione contenuti, Template text/formatters, Rule Conditions, Inbox deduplication, Theme contrasts, Webhook retry/SSRF.
- **Strategia:** Decision table per casi base e boundaries, CSV data-driven per combinazioni indipendenti.
- **Divergenze dalla spec:** Nessuna annotata per engagement (Q-72 e Q-98 sono definite ma non comportano deviazioni in attesa di spec fix, implementate come da domanda aperta).
