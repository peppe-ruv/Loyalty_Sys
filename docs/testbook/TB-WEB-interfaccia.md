# Testbook: Interfaccia (Logica Trasversale)

**Scopo**: Regole di visualizzazione, validazione client, gestione ruoli e simulazioni dell'interfaccia (portale e backoffice).
**Fonti**: `docs/07`, `docs/08`, `docs/09`, `docs/15`.

## 1. Autorizzazioni (Matrice can)

**Riferimento Specifica**: docs/08 §2 (e `web/lib/persona/permissions.ts`)
**Strategia Combinatoria**: Matrice completa `role` × `capability` (5 × 19 = 95 combinazioni). Ruolo non valido.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-PERM-001 | capability = `member.write`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-002 | capability = `member.write`, role = CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-003 | capability = `member.write`, role = MARKETING/LEGAL/ANALYST | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-004 | capability = `member.anonymize`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-005 | capability = `member.anonymize`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-006 | capability = `points.adjust`, role = ADMIN/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-007 | capability = `points.adjust`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-008 | capability = `segment.write`, role = ADMIN/MARKETING | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-009 | capability = `segment.write`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-010 | capability = `object.edit`, role = ADMIN/MARKETING | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-011 | capability = `object.edit`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-012 | capability = `object.approve`, role = ADMIN/LEGAL | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-013 | capability = `object.approve`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-014 | capability = `content.write`, role = ADMIN/MARKETING | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-015 | capability = `content.write`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-016 | capability = `webhook.write`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-017 | capability = `webhook.write`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-018 | capability = `program.config`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-019 | capability = `program.config`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-020 | capability = `actiontype.custom`, role = ADMIN/MARKETING | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-021 | capability = `actiontype.custom`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-022 | capability = `redemption.handle`, role = ADMIN/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-023 | capability = `redemption.handle`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-024 | capability = `instants.view`, role = ADMIN/LEGAL | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-025 | capability = `instants.view`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-026 | capability = `delivery.handle`, role = ADMIN/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-027 | capability = `delivery.handle`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-028 | capability = `coupon.use`, role = ADMIN/MARKETING/LEGAL/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-029 | capability = `coupon.use`, role = ANALYST | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-030 | capability = `coupon.void`, role = ADMIN/CARE | `true` | Q-52 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-031 | capability = `coupon.void`, role = altri | `false` | Q-52 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-032 | capability = `inbound.handle`, role = ADMIN/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-033 | capability = `inbound.handle`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-034 | capability = `dlq.handle`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-035 | capability = `dlq.handle`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-036 | capability = `demo.simulate`, role = ADMIN/MARKETING/LEGAL/CARE | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-037 | capability = `demo.simulate`, role = ANALYST | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-038 | capability = `demo.admin`, role = ADMIN | `true` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-039 | capability = `demo.admin`, role = altri | `false` | docs/08 | `persona/permissions.testbook.test.ts` |
| TB-WEB-PERM-040 | role non riconosciuto | `false` per tutte | docs/08 | `persona/permissions.testbook.test.ts` |

## 2. Descrizione Campagna Generata (describeCampaign)

**Riferimento Specifica**: docs/08 BO-06
**Strategia Combinatoria**: Valori limite per audience, condizioni (all/any/not e operatori foglia) e tutti i tipi di effetto, limit operator.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-DESC-001 | trigger multipli + label custom | "Quando arriva **A o B**," | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-002 | audience.tiers = ["GOLD"] | "se il membro è **GOLD**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-003 | audience.segments = ["SEG-1", "SEG-2"] | "se è nel segmento **SEG-1 o SEG-2**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-004 | audience combinata tier + segment | "se il membro è **GOLD** e è nel segmento **SEG-1**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-005 | condition op: any | "se (**x = 1** oppure **y = 2**)" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-006 | condition op: not | "se non (**x = 1**)" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-007 | condition operators: eq, gt, exists | Traduzione: "=", ">", "presente" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-008 | effect: GRANT_POINTS PER_AMOUNT | "assegna **1 PTS ogni 10 €**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-009 | effect: GRANT_POINTS FIXED | "assegna **10 PTS**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-010 | effect: MULTIPLIER | "assegna **PTS ×2**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-011 | effect: GRANT_PLAYS | "assegna **1 giocata su IW-X**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-012 | effect: ISSUE_COUPON | "assegna **un coupon**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-013 | effect: AWARD_BADGE | "assegna **un badge**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-014 | effect: SEND_MESSAGE | "assegna **il messaggio MSG-1**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-015 | limit: 1 per DAY | "al massimo **1 volta/e al giorno**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-016 | limit: 2 per EDITION | "al massimo **2 volta/e per edizione**" | docs/08 | `campaign/describe.testbook.test.ts` |
| TB-WEB-DESC-017 | limit: 1 per ALWAYS | "al massimo **1 volta/e in totale**" | docs/08 | `campaign/describe.testbook.test.ts` |

## 3. Gestione Navigazione (Milestone)

**Riferimento Specifica**: docs/08 §1
**Strategia Combinatoria**: Voci attese visibili in funzione della milestone configurata (es. <= 7). Verificare URL attivi per sottopagine (BO-04 in Clienti, BO-27 in Osservabilità).

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-NAV-001 | milestone 1 | Dashboard nascosta, Membri visibile | docs/08 | `nav.testbook.test.ts` |
| TB-WEB-NAV-002 | activeHref su sottopagina /segments/SEG-1 | accende /backoffice/segments (BO-04) | docs/08 | `nav.testbook.test.ts` |
| TB-WEB-NAV-003 | activeHref su /content/messages | accende /backoffice/content/messages (BO-19) non /content | docs/08 | `nav.testbook.test.ts` |
| TB-WEB-NAV-004 | BO-27 (DLQ) in milestone 7 | Visibile in Osservabilità se realized >= 7 | docs/08 | `nav.testbook.test.ts` |
| TB-WEB-NAV-005 | activeHref percorso portale | `null` | docs/08 | `nav.testbook.test.ts` |

## 4. Formattazione (Date e Punti)

**Riferimento Specifica**: docs/07 §9
**Strategia Combinatoria**: Valori limite per punti (0, 1000, >1M, negativi), frazioni e valute. Valori limite date (mezzanotte Rome, fuso orario, cambi DST). Rolling expiry a fine mese.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-FMT-001 | formatPoints: 0 | "0" | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-002 | formatPoints: 999 | "999" | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-003 | formatPoints: 1000 | "1.000" | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-004 | formatPoints: 1000000 | "1.000.000" | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-005 | formatPoints: negativi | Segno negativo con separatore | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-006 | formatEuro: 0 | "0,00 €" o "€ 0,00" | docs/07 | `format/points.testbook.test.ts` |
| TB-WEB-FMT-007 | formatDate: null | "—" | docs/07 | `format/dates.testbook.test.ts` |
| TB-WEB-FMT-008 | formatRelative: < 1 min | "ora" | docs/07 | `format/dates.testbook.test.ts` |
| TB-WEB-FMT-009 | formatRelative: 59 min | "59 min fa" | docs/07 | `format/dates.testbook.test.ts` |
| TB-WEB-FMT-010 | formatRelative: 23 ore | "23 h fa" | docs/07 | `format/dates.testbook.test.ts` |
| TB-WEB-FMT-011 | computeRollingExpiry: mese normale (15 gen + 1 mese) | 28/29 feb | docs/07 | `format/dates.testbook.test.ts` |
| TB-WEB-FMT-012 | computeRollingExpiry: fine mese (31 gen + 1 mese) | 28/29 feb (non marzo) | docs/07 | `format/dates.testbook.test.ts` |

## 5. Cookie Persona (Parse)

**Riferimento Specifica**: docs/07 §4
**Strategia Combinatoria**: Valori ben formati per BO e MEMBER. Valori malformati (stringhe rotte, JSON mancante di campi chiave).

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-COOKIE-001 | BO persona valida | parsato come BO, ruolo incluso | docs/07 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-002 | MEMBER persona valida | parsato come MEMBER | docs/07 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-003 | payload non JSON | null | docs/07 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-004 | JSON valido ma campi BO mancanti | null | docs/07 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-005 | MEMBER senza memberId | null | docs/07 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-006 | header per BO | `RUOLO:username` | docs/06 | `persona/cookie.testbook.test.ts` |
| TB-WEB-COOKIE-007 | header per MEMBER | `ANALYST:anonymous` | docs/06 | `persona/cookie.testbook.test.ts` |

## 6. Costruttore Condizioni e Warning

**Riferimento Specifica**: docs/08 BO-06
**Strategia Combinatoria**: Analisi albero JSON, profondità massima, foglia non in catalogo.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-COND-001 | Albero profondità = MAX_DEPTH | accettato | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-002 | Albero profondità > MAX_DEPTH | daFromJson dà errore (rifiutato) | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-003 | toJson: esclude i gruppi vuoti | Json prodotto non ha gruppi vuoti | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-004 | fieldWarnings: path data.* non in nessun trigger | "potrebbe non essere mai vera" | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-005 | fieldWarnings: path data.* comune ad alcuni ma non tutti | Segnala manca in [trigger] | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-006 | fieldWarnings: path fuori catalogo | Segnala fuori catalogo | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-007 | leafProblem: campo vuoto | "Scegli un campo" | docs/08 | `campaign/conditions.testbook.test.ts` |
| TB-WEB-COND-008 | leafProblem: comparatore non idoneo (es. gt per boolean) | "Operatore non ammesso..." | docs/08 | `campaign/conditions.testbook.test.ts` |

## 7. Coda Approvazioni (Merge e Deduplica)

**Riferimento Specifica**: docs/08 BO-21
**Strategia Combinatoria**: Analisi della fusione e deduplicazione dell'hub, valutazione permessi policy.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-QUEUE-001 | mergeQueues: deduplica su entityType:id | Restituisce un array fuso | docs/08 | `approvals/queue.testbook.test.ts` |
| TB-WEB-QUEUE-002 | toApproveBy: role LEGAL | Ritorna solo richieste IN_REVIEW per LEGAL | docs/08 | `approvals/queue.testbook.test.ts` |
| TB-WEB-QUEUE-003 | toApproveBy: role ADMIN | Ritorna tutto in IN_REVIEW | docs/08 | `approvals/queue.testbook.test.ts` |
| TB-WEB-QUEUE-004 | sentByMe | Ordina items inviati dal più recente | docs/08 | `approvals/queue.testbook.test.ts` |
| TB-WEB-QUEUE-005 | outcomeOf: REJECT | label "Rifiutato" | docs/08 | `approvals/queue.testbook.test.ts` |

## 8. Ciclo di Vita (LifecycleBar Status x Role x Approval)

**Riferimento Specifica**: docs/08 BO-21 e docs/06 §7
**Strategia Combinatoria**: Stato x Ruolo x policy (approvalRequired). Verifica dei bottoni visibili.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-LIFE-001 | DRAFT, approvalRequired=true | Mostra "Invia in revisione", Nasconde "Pubblica" | docs/06 | `LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-002 | DRAFT, approvalRequired=false | Mostra "Pubblica", Nasconde "Invia in revisione" | docs/06 | `LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-003 | IN_REVIEW, role=LEGAL | Mostra "Approva" e "Rifiuta..." non disabilitati | docs/06 | `LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-004 | IN_REVIEW, role=MARKETING | Bottoni "Approva" / "Rifiuta..." presenti ma DISABLED | docs/06 | `LifecycleBar.testbook.test.tsx` |
| TB-WEB-LIFE-005 | LIVE | Mostra "Metti in pausa", "Termina" | docs/06 | `LifecycleBar.testbook.test.tsx` |

## 9. Progressi Tier ed Esposizione Portale Premi

**Riferimento Specifica**: docs/09 PT-03, PT-04, PT-13
**Strategia Combinatoria**: Calcolo progressi barra e avvisi di blocco premi.

| ID | Condizioni | Atteso | Rif | Test |
|---|---|---|---|---|
| TB-WEB-TIER-001 | bandProgress: balance < threshold | missing > 0, pct calcolato | docs/09 | `reward/portal.testbook.test.ts` |
| TB-WEB-TIER-002 | bandProgress: balance >= threshold | reached = true | docs/09 | `reward/portal.testbook.test.ts` |
| TB-WEB-TIER-003 | blockReason: SOLD_OUT | "Esaurito" | docs/09 | `reward/portal.testbook.test.ts` |
| TB-WEB-TIER-004 | blockReason: lockedByTier | "Riservato a [Tier]" | docs/09 | `reward/portal.testbook.test.ts` |
| TB-WEB-TIER-005 | blockReason: balance < cost | "Ti mancano X punti" | docs/09 | `reward/portal.testbook.test.ts` |

## Copertura
- **Regole analizzate**: 86
- **Bivi implementati**: 86
- **Divergenze dalla spec**: nessuna
- **Ambiguità identificate**: nessuna
