# TB-GOV: Testbook Governance e Ciclo di Vita Member

**Ambito:** Transizioni di stato (DRAFT -> IN_REVIEW -> APPROVED -> LIVE -> PAUSED -> ENDED -> ARCHIVED), ruoli (MARKETING, LEGAL, ADMIN, ecc.), approvazione con/senza policy (LH_APPROVAL_ENABLED), member status (ACTIVE, INACTIVE, BLOCKED, CLOSED, ANONYMIZED) e attributi.
**Riferimenti:** docs/03 §3.6, docs/06 §7, docs/08 §2, F-APR-*, F-MBR-*, F-SEG-*.

## 1. ApprovalStateMachine e GovernedTransitions (Unit Test lh-common)
Testati isolatamente: transizioni di stato e ruoli (ApprovalPolicy, ApprovalStateMachine, GovernedTransitions).

### Domini di input
*   **Stato Iniziale:** DRAFT, IN_REVIEW, APPROVED, LIVE, PAUSED, ENDED, ARCHIVED
*   **Azione:** SUBMIT, APPROVE, REJECT, PUBLISH, PAUSE, RESUME, END, ARCHIVE, edit (azione implicita per permessi generici)
*   **Ruolo:** ADMIN, MARKETING, LEGAL, CARE, ANALYST, vuoto (invalid)
*   **ApprovalPolicy (enabled):** true, false
*   **Policy Rule (required):** true, false
*   **Commento (per REJECT):** presente, vuoto/null

### Strategia di combinazione
*   *Stati x Azioni (Macchina a stati):* Tutte le combinazioni valide (transizioni corrette) + per ogni stato, le transizioni invalide (error code).
*   *Ruoli x Azioni:* Ogni azione con tutti i ruoli per testare `GovernedTransitions` (403/ForbiddenRole).
*   *PUBLISH da DRAFT:* Combina (enabled=true/false, required=true/false).
*   *REJECT con/senza commento:* Test REJECT_COMMENT_REQUIRED.

### Casi di Test (TestbookGovTransitionsTest e TestbookGovPolicyTest)

| ID | Condizioni | Atteso | Riferimento | Test |
|---|---|---|---|---|
| TB-GOV-001 | DRAFT + SUBMIT | IN_REVIEW | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-002 | IN_REVIEW + APPROVE | APPROVED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-003 | IN_REVIEW + REJECT + commento | DRAFT | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-004 | APPROVED + PUBLISH | LIVE | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-005 | LIVE + PAUSE | PAUSED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-006 | PAUSED + RESUME | LIVE | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-007 | LIVE + END | ENDED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-008 | PAUSED + END | ENDED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-009 | ENDED + ARCHIVE | ARCHIVED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-010 | DRAFT + ARCHIVE | ARCHIVED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-011 | LIVE + SUBMIT | 409 INVALID_TRANSITION | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-012 | IN_REVIEW + REJECT + no comment | 422 REJECT_COMMENT_REQUIRED | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-013 | DRAFT + PUBLISH, policy=on, rule=req | 409 APPROVAL_REQUIRED | docs/06 §7 | TestbookGovTransitionsTest |
| TB-GOV-014 | DRAFT + PUBLISH, policy=on, rule=not_req| LIVE | docs/03 §3.6 | TestbookGovTransitionsTest |
| TB-GOV-015 | DRAFT + SUBMIT, policy=off | LIVE | docs/06 §7 | TestbookGovTransitionsTest |
| TB-GOV-016 | DRAFT + PUBLISH, policy=off | LIVE | docs/06 §7 | TestbookGovTransitionsTest |
| TB-GOV-017 | Azione APPROVE con ruolo LEGAL | APPROVED, not override| docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-018 | Azione APPROVE con ruolo ADMIN | APPROVED, is override | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-019 | Azione APPROVE con ruolo MARKETING | 403 ForbiddenRole | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-020 | Azione SUBMIT con ruolo MARKETING | IN_REVIEW | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-021 | Azione SUBMIT con ruolo LEGAL | 403 ForbiddenRole | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-022 | Azione SUBMIT con ruolo ADMIN | IN_REVIEW | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-023 | Azione SUBMIT con ruolo CARE | 403 ForbiddenRole | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-024 | Azione SUBMIT con ruolo ANALYST | 403 ForbiddenRole | docs/06 §7, docs/08 §2 | TestbookGovTransitionsTest |
| TB-GOV-025 | Policy CONTEST, policy=on | required=true, LEGAL | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-026 | Policy REWARD, policy=on | required=true, LEGAL | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-027 | Policy CONTENT, policy=on | required=false | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-028 | Policy CAMPAIGN, policy=on, reqLegal=true | required=true, LEGAL | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-029 | Policy CAMPAIGN, policy=on, reqLegal=false, budget=100000 | required=false | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-030 | Policy CAMPAIGN, policy=on, reqLegal=false, budget=100001 | required=true, LEGAL | docs/06 §7 | TestbookGovPolicyTest |
| TB-GOV-031 | Policy CONTEST, policy=off | required=false | docs/06 §7 | TestbookGovPolicyTest |


## 2. Member Lifecycle e Anonimizzazione (Integration Test member-service)
Test di integrazione con DB per validare i cambi di stato del membro e l'anonimizzazione.

### Domini di input
*   **Stato Iniziale:** ACTIVE, INACTIVE, BLOCKED, CLOSED, ANONYMIZED
*   **Azione:** changeStatus (da API), anonymize (da API)
*   **Ruolo chiamante:** ADMIN, CARE, MARKETING, vuoto
*   **Anonymize confirm:** uguale a ID, diverso da ID, null/vuoto

### Strategia di combinazione
*   *Ruoli x changeStatus:* CARE e ADMIN validi, gli altri 403. Transizioni di stato ammesse validano l'aggiornamento.
*   *Ruoli x anonymize:* Solo ADMIN valido.
*   *Anonymize data validation:* Controllo puntuale di ogni campo azzerato (nome, email, telefono, ecc.) e di quelli mantenuti (id, iscrizione, labels, profileCompletedAt).
*   *Anonymize confirm validation:* Errore se `confirm` non corrisponde all'ID.

### Casi di Test (TestbookGovMemberIT)

| ID | Condizioni | Atteso | Riferimento | Test |
|---|---|---|---|---|
| TB-GOV-032 | changeStatus(INACTIVE) + ruolo ADMIN | status=INACTIVE | F-MBR-04, BO-03 | TestbookGovMemberIT#changeStatusRoles |
| TB-GOV-033 | changeStatus(BLOCKED) + ruolo CARE | status=BLOCKED | F-MBR-04, BO-03 | TestbookGovMemberIT#changeStatusRoles |
| TB-GOV-034 | changeStatus(CLOSED) + ruolo MARKETING | 403 ForbiddenRole | docs/08 §2 | TestbookGovMemberIT#changeStatusForbiddenRoles |
| TB-GOV-035 | changeStatus(ACTIVE) + no role | 401/403 (no header) | docs/06 §7 | TestbookGovMemberIT#changeStatusForbiddenRoles |
| TB-GOV-036 | anonymize + ruolo ADMIN, confirm=ID | status=ANONYMIZED, dati azzerati | F-MBR-05, Q-120, Q-121 | TestbookGovMemberIT#anonymizeAdminSuccess |
| TB-GOV-037 | anonymize + ruolo CARE | 403 ForbiddenRole | docs/08 §2 | TestbookGovMemberIT#anonymizeForbiddenRole |
| TB-GOV-038 | anonymize + ruolo ADMIN, confirm errato | 422 o bad request | docs §3 | TestbookGovMemberIT#anonymizeWrongConfirm |

## 3. Copertura
*   **Regole gestite:** 38
*   **Branch implementati:** tutte le combinazioni ruoli/stati (ApprovalPolicy, MemberStatus, Anonymization).
*   **Combinazioni ridotte:** test isolati per ruoli (CARE/ADMIN ammessi, gli altri accorpati nel test di failure).
*   **Branch senza spec:** nessuno rilevato; applicate regole conservative (Q-120, Q-121).
*   **Divergenze trovate:** nessuno finora.
