# Loyalty Hub — piattaforma loyalty vendor neutral

Evoluzione interna Iren del programma loyalty, a fianco di BeIren: azioni premianti da fonti eterogenee → punti (doppia valuta premio/status) e tier → fasce premi; programma annuale e instant win con istanti vincenti pre-generati; backoffice unico che fa anche da CMS. Core proprietario in Java 25/Spring Boot su Kubernetes (Amazon EKS, eu-south-1), event-driven su Kafka, Next.js + widget, CMS headless open source, osservabilità Prometheus/Grafana e BI Superset incorporata.

## Stato del repository

Questa versione (0.6.0) contiene la documentazione di prodotto e il design system del backoffice. Il codice del monorepo (services/, web/, cms/, deploy/) segue al prossimo push dalla copia di lavoro 0.5.0.

| Cartella | Contenuto |
| --- | --- |
| `docs/LINEE-GUIDA-UX-BACKOFFICE.md` | 48 linee guida UX del backoffice (LG-01..LG-48) derivate da Open Loyalty Feature Showcase, con mappa video → LG → requisiti |
| `docs/adr/` | Decisioni architetturali (ADR-026: adozione delle linee guida UX) |
| `docs/SPECIFICA-ADDENDUM-UX.md` | Requisiti RF-137..RF-142 e CHANGELOG 0.6.0 |
| `web/backoffice-design-system/` | Contratti TypeScript dei 15 pattern UI (`patterns.ts`) e token (`tokens.css`) |
| `scripts/publish-github.sh` | Pubblicazione del monorepo su GitHub |

Specifica completa e registro decisioni: documenti «Specifica Loyalty Hub» e «Registro decisioni Loyalty Hub» (Claude Docs), da versionare in `docs/` con il prossimo push.

## Installazione (obiettivo)

Tre comandi su Amazon EKS partendo da immagini container già costruite: `terraform apply`, `helm install loyalty-hub`, `make seed`. Ambiente locale con `docker compose up`.
