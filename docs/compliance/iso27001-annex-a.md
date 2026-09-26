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
