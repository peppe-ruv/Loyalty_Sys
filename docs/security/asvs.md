# Conformità OWASP ASVS 5.0 livello 2

> Fonte: `docs/18 §3.10` (ADR-042). Riferimento di verifica del profilo `enterprise`: **OWASP ASVS 5.0, livello 2** e **OWASP API Security Top 10 (2023)**. Deliverable di M8.11 (F2-SEC-12). La tabella si aggiorna a ogni milestone e a ogni fetta che cambia lo stato di un capitolo. Il contesto delle minacce è in `docs/security/threat-model.md`.

La tabella è per **capitolo** dell'ASVS: per ciascuno indica come il prodotto lo soddisfa, dove sta la prova e a che punto è. Il dettaglio per singolo requisito si aggiunge quando il job `security` produce le evidenze automatiche (M8.11): fino ad allora un requisito non verificato dal software non si dichiara soddisfatto (regola 13).

**Legenda stato:** ✅ soddisfatto e verificato · 🟡 parziale o in revisione · ⏳ pianificato · ➖ non applicabile.

## 1. Capitoli ASVS 5.0

| Capitolo | Come lo soddisfa il prodotto | Prova (dove si verifica) | Stato | Fetta |
|---|---|---|---|---|
| V1 Codifica e sanitizzazione | SQL solo parametrico e builder `SqlWhere`/`SqlOrder` con colonne da enum; React senza HTML non sanitizzato; contenuti del CMS sanitizzati con allowlist alla pubblicazione; template dei messaggi senza logica | regola Semgrep su `.sql(` non costante, query CodeQL `java/sql-injection`, test del builder | 🟡 SQL parametrico oggi, builder e regole da fare | M8.10, M8.11 |
| V2 Validazione e logica di business | Record DTO espliciti (niente *mass assignment*); validazione dei payload contro JSON Schema anche in consumo; `Idempotency-Key` sulle POST di valore; blocco ottimistico; limiti per membro su giocate e riscatti | test di contratto, testbook `TB-SEC` (mass assignment, idempotenza, rate limit) | 🟡 DTO e blocco ottimistico presenti | M8.10, M8.11 |
| V3 Sicurezza del frontend web | CSP con nonce e `strict-dynamic`, `frame-ancestors 'none'`, HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, COOP/CORP; widget come web component | ZAP API scan, test degli header nel ruolo `web` | ⏳ | M8.5 |
| V4 API e servizi web | REST `/v1` con errori RFC 9457 senza dettagli interni; OpenAPI generata e verificata; CORS esplicito per origine registrata | `contracts/api/` e `check-contracts`, fuzzing Schemathesis sulle OpenAPI | 🟡 RFC 9457 presente, OpenAPI in lavorazione | M8.8, M8.11 |
| V5 Gestione dei file | Import file asincrono con limiti; regolamenti e media con dimensione massima, tipo reale, antivirus, fuori dalla radice web, `Content-Disposition` al download; SVG solo sanitizzati | test dell'import (BO-32), `TB-SEC` sui file | ⏳ | M8.7, M8.10 |
| V6 Autenticazione | Delegata all'IdP (Keycloak): passkey, MFA per gli operatori, federazione LDAP e broker verso IdP aziendale; nessun codice che gestisce password nel prodotto (regola 6-bis) | realm as code in `deploy/idp/`, prova con IdP di test | ⏳ | M8.2 |
| V7 Gestione della sessione | Sessione server-side nel BFF, cookie `__Host-`, CSRF, back-channel logout, scadenza e rotazione | test del BFF, `TB-SEC` | ⏳ | M8.2 |
| V8 Autorizzazione | Deny by default: `@RequiresRole` o `@PublicEndpoint` motivato su ogni endpoint; `MemberPrincipal` nel portale (niente `memberId` da parametri, API1 BOLA); controllo di proprietà; niente auto-approvazione (quattro occhi) | test ArchUnit in `./mvnw verify`, `TB-SEC` (BOLA, BFLA) | 🟡 ruoli sulle scritture presenti, letture aperte nel PoC | M8.10, M8.11, M8.13 |
| V9 Token autocontenuti | JWT validati con firma da JWKS, `iss`, `aud=hub`, `exp`; ruoli solo dal claim `lh_roles` | `OidcActorFilterTest` | 🟡 PR #51 | M8.2 |
| V10 OAuth e OIDC | Authorization code con PKCE nel BFF; client credentials `private_key_jwt` per fonti e job; token exchange RFC 8693 per i widget; token mai nel browser | configurazione del realm, test del BFF | ⏳ | M8.2 |
| V11 Crittografia | AES-GCM a colonna per i contatti con chiave derivata (`LH_MASTER_KEY` o KMS); HMAC-SHA256 per pseudonimi (`emailHash`), webhook e notifiche CMS; Ed25519 per i messaggi del bus | test di cifratura e firma | 🟡 HMAC per pseudonimi e webhook presenti | M8.4, M8.10 |
| V12 Comunicazione sicura | TLS 1.2+ ovunque (1.3 dove possibile); `verify-full` verso Postgres; TLS verso Kafka; mTLS della mesh tra pod | chart Helm, rifiuto all'avvio in `enterprise` senza TLS | ⏳ | M8.3, M8.5 |
| V13 Configurazione | Nessun segreto nel codice (secret manager, convenzione `*_FILE`); rifiuto all'avvio con configurazioni insicure (`INSECURE_CONFIG`); Actuator su porta separata; nessuna telemetria in uscita; dipendenze aggiornate e scansionate | `IdentityGuardTest`, `lh doctor --security`, Dependabot e Trivy | 🟡 guardia sull'identità in revisione (PR #51), Dependabot attivo | M8.2, M8.5, M12.6 |
| V14 Protezione dei dati | `x-lh-pii` su ogni campo evento e nessun dato personale sul bus; classificazione `x-lh-class`; retention per categoria; anonimizzazione propagata; `erasure_log` e crypto-shredding; esportazioni con permesso e motivo | test di contratto `personalDataOnlyInSupersededVersions`, `check-contracts` | 🟡 contratti PII in revisione (PR #50) | M8.4, M8.13 |
| V15 Codifica sicura e architettura | Confini dei servizi e un solo proprietario per entità; nessuna chiamata sincrona tra servizi; moduli isolati verificati; SBOM e dipendenze tracciate; threat model per confine | ArchUnit, SBOM CycloneDX, `docs/security/threat-model.md` | 🟡 threat model presente, ArchUnit e SBOM da fare | M8.5, M8.11 |
| V16 Registrazione ed errori | Audit sola-inserzione con attore reale; eventi di sicurezza (401/403, firme non valide, rate limit) nel log strutturato senza dati personali; export OCSF verso il SIEM; errori senza stack trace | `GET /v1/audit`, catena di hash e `lh audit verify` | 🟡 audit presente, eventi di sicurezza e catena di hash da fare | M8.6, M8.12 |
| V17 WebRTC | Il prodotto non usa WebRTC | — | ➖ | — |

## 2. OWASP API Security Top 10 (2023)

| Rischio | Contromisura | Stato | Fetta |
|---|---|---|---|
| API1 Broken Object Level Authorization | `MemberPrincipal` nel portale; controllo di proprietà nelle API di gestione | ⏳ (oggi `memberId` in query nel profilo `demo`) | M8.10 |
| API2 Broken Authentication | OIDC ovunque in `enterprise`, validazione JWT, client credentials per le fonti | 🟡 PR #51 | M8.2 |
| API3 Broken Object Property Level Authorization | DTO espliciti in ingresso e in uscita; nessun campo interno legabile | 🟡 convenzione presente, ArchUnit da fare | M8.10, M8.11 |
| API4 Unrestricted Resource Consumption | Paginazione obbligatoria, batch fino a 1000, limiti sui file, rate limit al gateway e per membro | 🟡 paginazione presente | M8.5, M8.7, M8.10 |
| API5 Broken Function Level Authorization | `@RequiresRole` su ogni endpoint, deny by default | 🟡 scritture protette, letture aperte nel PoC | M8.10 |
| API6 Unrestricted Access to Sensitive Business Flows | `Idempotency-Key`, limiti per membro su giocate, riscatti e registrazioni, segnali di velocità in insight | ⏳ | M8.10 |
| API7 Server Side Request Forgery | Rifiuto di destinazioni private dopo la risoluzione, niente redirect, egress limitato | ⏳ | M8.10, M8.5 |
| API8 Security Misconfiguration | Rifiuto all'avvio in `enterprise`, header di sicurezza, Actuator separato | 🟡 PR #51 | M8.5, M12.6 |
| API9 Improper Inventory Management | OpenAPI generata e verificata in `contracts/api/`, versioni `/v1`, contratti evento versionati | 🟡 in lavorazione | M8.8 |
| API10 Unsafe Consumption of APIs | Validazione delle risposte dei fornitori esterni, timeout, stato `DEGRADED`, circuit breaker | ⏳ | M13 |

## 3. Come si aggiorna

- Una fetta che porta un capitolo o un rischio a un nuovo stato aggiorna la riga nella stessa PR, con il numero della PR nella colonna *Stato*.
- Un capitolo passa a ✅ solo quando la prova indicata gira in CI (`./mvnw verify`, job `security` o `e2e/`), non quando la funzione esiste.
