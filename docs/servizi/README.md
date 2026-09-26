# Schede dei servizi

Una scheda per servizio: scopo e confini, modello dati, API, eventi, regole, seed, accettazione. Per le API e le tabelle del singolo servizio la scheda vince su `docs/02` e `docs/03` (`CLAUDE.md §6`). Da Fase 2 ogni scheda ha una sezione «Fase 2 (profilo `enterprise`)» e la classificazione `x-lh-class` delle colonne (`docs/18 §3.15`).

| Servizio | Scheda | Schema | Stato |
|---|---|---|---|
| ingestion-service | [ingestion-service.md](ingestion-service.md) | `ingestion` | Fase 1 |
| member-service | [member-service.md](member-service.md) | `member` | Fase 1 |
| campaign-service | [campaign-service.md](campaign-service.md) | `campaign` | Fase 1 |
| wallet-service | [wallet-service.md](wallet-service.md) | `wallet` | Fase 1 |
| reward-service | [reward-service.md](reward-service.md) | `reward` | Fase 1 |
| gamification-service | [gamification-service.md](gamification-service.md) | `gamification` | Fase 1 |
| engagement-service | [engagement-service.md](engagement-service.md) | `engagement` | Fase 1; diventa `experience-service` in M10.3 |
| insight-service | [insight-service.md](insight-service.md) | `insight` | Fase 1 |
| experience-service | `experience-service.md` (nasce con M10.3) | `experience` | segnaposto: ex engagement, composizione versionata (ADR-031) |
| assistant-service | `assistant-service.md` (nasce con M14.1) | `assistant` | segnaposto: agente regolamento self-hosted (ADR-035) |
