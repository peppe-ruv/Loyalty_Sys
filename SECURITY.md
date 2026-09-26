# Politica di sicurezza

Loyalty Hub è un progetto open source. Questa pagina spiega come segnalare una vulnerabilità e cosa aspettarsi dopo la segnalazione (`docs/18 §3.15` punto 6, ADR-044, F2-SEC-12).

## Versioni supportate

Il progetto è in sviluppo (Fase 2, verso la prima versione `enterprise`). Fino al primo rilascio con numero di versione si corregge solo il ramo `main`. La politica LTS, con periodo di supporto e fine vita per ogni versione, si pubblica con la pipeline di rilascio (M12.4).

| Versione | Supportata |
|---|---|
| `main` | ✅ |
| profilo `demo` (dati fittizi) | ✅, ma non va esposto con dati reali |

## Come segnalare una vulnerabilità

**Non aprire una issue pubblica.** Usa la segnalazione privata di GitHub: scheda **Security** del repository → **Report a vulnerability**. La segnalazione resta visibile solo ai manutentori finché l'advisory non viene pubblicato.

Indica, se puoi:

- la componente coinvolta (servizio, `web`, contratti evento, immagine, chart);
- il profilo (`demo` o `enterprise`) e la versione o il commit;
- i passi per riprodurre e l'impatto che ti aspetti;
- se la vulnerabilità è già sfruttata attivamente.

Non includere dati personali reali: il profilo `demo` contiene solo dati fittizi ed è sufficiente per riprodurre.

## Cosa succede dopo

La segnalazione riceve una conferma e una valutazione di gravità (CVSS) appena possibile, comunque in tempo per rispettare i termini di correzione qui sotto.

| Gravità | Correzione o mitigazione |
|---|---|
| **Critica** | entro 7 giorni |
| **Alta** | entro 30 giorni |
| Media e bassa | nel primo rilascio utile |

I termini di critiche e alte vengono da `docs/18 §3.15` punto 6. La correzione esce con un advisory GitHub pubblico che cita chi ha segnalato, se lo desidera, e con una voce nel changelog di sicurezza.

Una vulnerabilità **sfruttata attivamente** segue anche il processo di segnalazione del Cyber Resilience Act verso la piattaforma unica ENISA: preallarme entro 24 ore, notifica entro 72 ore, rapporto finale. Il regime del titolare del progetto è una decisione aperta (Q-357); il processo si applica comunque.

## Ambito

Rientrano nell'ambito: il codice di questo repository, i contratti in `contracts/`, l'immagine e il chart pubblicati dal progetto.

Non rientrano:

- la demo ospitata a costo zero, che usa solo dati fittizi, non ha login per scelta (profilo `demo`, CLAUDE.md regola 6) e si riavvia da sola;
- le installazioni gestite da chi adotta il prodotto, con le loro configurazioni;
- i componenti di terze parti (Kafka, Postgres, Keycloak, Directus): segnala al loro progetto e, se il Loyalty Hub li configura in modo insicuro, anche qui.

Il profilo `demo` accetta per scelta alcuni rischi, elencati in `docs/security/threat-model.md §3`: segnalarli non è necessario, a meno che valgano anche nel profilo `enterprise`.

## Riferimenti

- Threat model per confine: `docs/security/threat-model.md`
- Conformità OWASP ASVS 5.0 livello 2: `docs/security/asvs.md`
- Mappa dei controlli ISO/IEC 27001 Annex A: `docs/compliance/iso27001-annex-a.md`
