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
| A.8.9 Gestione della configurazione | Condivisa | chart Helm e compose di riferimento versionati; il chart rifiuta valori non supportati (ruoli assenti, `embedded`, `enterprise` senza emittente, forma dei topic impossibile); l'avvio rifiuta una forma dei topic impossibile | `scripts/check-helm.mjs`, `KafkaScalingSettingsTest`, `helm template` che fallisce con messaggio | M8.3 |
| A.8.14 Ridondanza delle strutture di elaborazione | Condivisa | ≥2 repliche per `hub`, `web`, `idp` distribuite tra le zone, PDB, HPA; Kafka a 3 broker con RF 3 e ISR 2; Postgres a 3 istanze (CloudNativePG) o servizio gestito | valori del chart, manifest resi da `helm template` | M8.3 |
| A.8.20 Sicurezza delle reti | Condivisa | hub non esposto; Ingress solo per `web` e per i percorsi pubblici di `idp` (console di amministrazione fuori); Kafka interno senza TLS fino a M8.5 dichiarato (Q-375) | `templates/ingress.yaml`, note del chart | M8.3 |
| A.5.17 Informazioni di autenticazione | Condivisa | nessun segreto nel chart né nel compose: riferimenti a Secret esistenti, variabili d'ambiente obbligatorie con arresto se assenti | `scripts/check-helm.mjs` (segreti in chiaro), guardia `x-lh-require-env` | M8.3 |
| A.8.32 Gestione dei cambiamenti | Prodotto | migrazioni Flyway come Job `pre-upgrade` prima della sostituzione dei Pod, con lock (ADR-038) | `HubMigrateIT`, annotazioni hook nel manifest | M8.3 |
