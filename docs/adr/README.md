# Registro delle decisioni · Decision log (ADR)

> 🇮🇹 Ogni scelta strutturale è registrata in un ADR numerato e **immutabile**: se una decisione cambia si scrive un ADR
> nuovo che supera il precedente, non si riscrive quello approvato. Formato: contesto, opzioni (dove rilevanti),
> decisione, conseguenze — in italiano e inglese.
> 🇬🇧 Every structural choice is recorded in a numbered, **immutable** ADR: if a decision changes, a new ADR supersedes
> the previous one; an accepted ADR is never rewritten. Format: context, options (where relevant), decision,
> consequences — in Italian and English.

| ADR | Tema · Topic | Stato · Status |
| --- | --- | --- |
| [ADR-001](ADR-001.md) | Motore loyalty: core proprietario componibile · Loyalty engine: composable in-house core | approvata · accepted |
| [ADR-002](ADR-002.md) | Piattaforma di esercizio: Kubernetes gestito in region italiana · Runtime platform: managed Kubernetes in an Italian region | approvata · accepted |
| [ADR-003](ADR-003.md) | Linguaggio dei servizi di dominio: Java e Spring Boot · Domain service language: Java and Spring Boot | approvata · accepted |
| [ADR-004](ADR-004.md) | Sito cliente: Next.js con widget incorporabili · Customer site: Next.js with embeddable widgets | approvata · accepted |
| [ADR-005](ADR-005.md) | Doppia valuta: punti premio e punti status · Dual currency: reward points and status points | approvata · accepted |
| [ADR-006](ADR-006.md) | Livelli per anno programma con discesa morbida · Programme-year tiers with soft downgrade | approvata · accepted |
| [ADR-007](ADR-007.md) | Instant win con istanti vincenti pre-generati · Instant win with pre-generated winning moments | approvata · accepted |
| [ADR-008](ADR-008.md) | Azioni interne nello stesso circuito · Internal actions in the same loop | approvata · accepted |
| [ADR-009](ADR-009.md) | Ingressi multipli, evento canonico unico · Multiple inbounds, one canonical event | approvata · accepted |
| [ADR-010](ADR-010.md) | Event-driven con eccezioni sincrone · Event-driven with synchronous exceptions | approvata · accepted |
| [ADR-011](ADR-011.md) | Continuità: multi-zona e ripristino in seconda region · Continuity: multi-zone and secondary-region recovery | approvata · accepted |
| [ADR-012](ADR-012.md) | Identità: IAM esterno via OIDC · Identity: external IAM over OIDC | approvata · accepted |
| [ADR-013](ADR-013.md) | Backoffice su CMS headless open source · Back office on an open source headless CMS | approvata · accepted |
| [ADR-014](ADR-014.md) | Pubblicazione per tipo di oggetto · Publication workflow per object type | approvata · accepted |
| [ADR-015](ADR-015.md) | Programma autonomo con import opzionale · Self-contained programme with optional import | approvata · accepted |
| [ADR-016](ADR-016.md) | Strategia di rilascio: un go-live con rollout graduale · Release strategy: one go-live with progressive rollout | approvata · accepted |
| [ADR-017](ADR-017.md) | Perimetro funzionale: baseline di mercato · Functional scope: market baseline | approvata · accepted |
| [ADR-018](ADR-018.md) | Da motore di regole a motore di campagne, con modulo di gamification · From rules engine to campaign engine, with a gamification module | approvata · accepted |
| [ADR-019](ADR-019.md) | Osservabilità: metriche, log e tracce open source · Observability: open-source metrics, logs and traces | approvata · accepted |
| [ADR-020](ADR-020.md) | BI nel backoffice su warehouse alimentato dai topic · Back-office BI on a topic-fed warehouse | approvata · accepted |
| [ADR-021](ADR-021.md) | Livello decisionale separato dal motore regole · Decision layer separate from the rules engine | approvata · accepted |
| [ADR-022](ADR-022.md) | Previsioni dietro una porta, con provider a regole e routing · Predictions behind a port, rule-based provider and routing | approvata · accepted |
| [ADR-023](ADR-023.md) | Antifrode a segnali configurabili con blocco sul registro · Configurable fraud signals with a ledger block | approvata · accepted |
| [ADR-024](ADR-024.md) | Consegna omnicanale tramite adattatori di canale · Omnichannel delivery through channel adapters | approvata · accepted |
| [ADR-025](ADR-025.md) | Consensi con base giuridica e grafo delle identità · Consents with legal basis and an identity graph | approvata · accepted |
| [ADR-026](ADR-026.md) | Linee guida UX del backoffice e design system · Back-office UX guidelines and design system | approvata · accepted |
| [ADR-027](ADR-027.md) | Design Tokens Italia come fondamenta del design system · Design Tokens Italia as the design system's foundation | approvata · accepted |

Aggiungere un ADR · Adding an ADR: copiare il file più recente, usare il numero successivo, mantenere le quattro sezioni
e citare i requisiti coinvolti; se supera un ADR precedente, dirlo nella prima riga di entrambi.

**EN** — Copy the most recent file, take the next number, keep the four sections and cite the requirements involved; if it
supersedes an earlier ADR, say so in the first line of both.
