# Mappa dei controlli ISO/IEC 27001:2022 Annex A

Documento previsto da `docs/18 §3.15` e ADR-044; la mappa completa dei 93 controlli, con responsabilità
**Prodotto / Adottante / Condivisa**, arriva con M12.6. Fino ad allora ogni fetta aggiunge qui i controlli che
tocca e l'evidenza che produce (`CLAUDE.md` regola 22).

| Controllo | Responsabilità | Funzione del prodotto | Evidenza | Fetta |
|---|---|---|---|---|
| A.5.15 Controllo degli accessi | Condivisa | `@RequiresRole` su ogni scrittura; ruoli dal claim `lh_roles` del token; più ruoli operatore ⇒ sola lettura (Q-365) | `OidcActorFilterTest`, `403 forbidden-role` sulle chiamate non ammesse | M8.2 |
| A.5.16 Gestione delle identità | Adottante (IdP), Prodotto (verifica) | identità solo dall'IdP OIDC nel profilo `enterprise`; `X-LH-Actor` ignorato | `OidcActorFilterTest` (header ignorato) | M8.2 |
| A.8.5 Autenticazione sicura | Condivisa | token verificato: firma dal JWKS, `iss`, `aud=hub`, scadenza; stesso `401` per ogni errore | `OidcActorFilterTest` (firma altrui, emittente, audience, scadenza) | M8.2 |
| A.8.9 Gestione della configurazione | Prodotto | il profilo `enterprise` non parte con identità insicura (`INSECURE_CONFIG`) | `IdentityGuardTest`, log di avvio | M8.2 |
| A.8.26 Requisiti di sicurezza delle applicazioni | Prodotto | import file (BO-32): limite di dimensione e di righe, estensione e tipo dichiarato in allowlist, contenuto riconosciuto (UTF-8, niente binari, formato atteso), nome ripulito, file cancellato a fine lavoro; batch fino a 1000 eventi | `ImportParserTest`, `ImportsIT` (`IMPORT_*` 422, `BATCH_TOO_LARGE`) | M8.7 |
| A.8.12 Prevenzione della fuga di dati | Prodotto | rapporto CSV dell'import senza il soggetto dei non abbinati e con le formule neutralizzate; motivi delle righe non valide senza il valore letto | `ImportsIT` (rapporto), `ImportParserTest` (celle e motivi) | M8.7 |
| A.8.15 Registrazione | Prodotto | audit `CREATE` dell'import con attore, `sha256` e conteggi (mai il contenuto), `JOB` a fine lavoro, una voce per ogni riga riprovata | `ImportsIT` (voci su `lh.audit.v1`) | M8.7 |
| A.8.7 Protezione dal malware | Condivisa | file di import solo testo UTF-8 riconosciuto dal contenuto e mai eseguito né servito; antivirus sui file caricati in M8.10 (docs/18 §3.10 punto 8) | `ImportParserTest` (`IMPORT_NOT_TEXT`) | M8.7 (parziale) |
