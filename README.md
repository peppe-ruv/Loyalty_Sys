# Loyalty Hub — piattaforma loyalty vendor neutral

Evoluzione interna Iren del programma loyalty, a fianco di BeIren: azioni premianti da fonti eterogenee → punti (doppia valuta premio/status) e tier → fasce premi; programma annuale e instant win con istanti vincenti pre-generati; backoffice unico che fa anche da CMS. Core proprietario in Java 25/Spring Boot su Kubernetes (Amazon EKS, eu-south-1), event-driven su Kafka, Next.js + widget, CMS headless open source, osservabilità Prometheus/Grafana e BI Superset incorporata.

## Stato del repository

Questa versione (0.6.1) contiene la documentazione di prodotto e il **design system del backoffice**, pubblicato come pacchetto npm compilabile e coperto da test. Il resto del monorepo (`services/`, `web/backoffice`, `cms/`, `deploy/`) segue al prossimo push dalla copia di lavoro.

| Cartella | Contenuto |
| --- | --- |
| `docs/LINEE-GUIDA-UX-BACKOFFICE.md` | 48 linee guida UX del backoffice (LG-01..LG-48) derivate da Open Loyalty Feature Showcase, con mappa video → LG → requisiti |
| `docs/adr/` | Decisioni architetturali (ADR-026: adozione delle linee guida UX) |
| `docs/SPECIFICA-ADDENDUM-UX.md` | Requisiti RF-137..RF-142 |
| `web/backoffice-design-system/` | Pacchetto `@loyalty-hub/backoffice-design-system`: contratti dei 15 pattern UI (`src/patterns.ts`), regole eseguibili (workflow, frasi, formati) e token (`tokens.css`) |
| `scripts/publish-github.sh` | Pubblicazione del monorepo su GitHub |
| `CHANGELOG.md` | Storico delle versioni |

Specifica completa e registro decisioni: documenti «Specifica Loyalty Hub» e «Registro decisioni Loyalty Hub», da versionare in `docs/` con il prossimo push.

## Sviluppo

Serve Node 22.13 o successivo (`.nvmrc`); il repository è un workspace npm.

```bash
npm ci          # installa le dipendenze di tutti i workspace
npm run check   # lint + controllo dei tipi + test + build (ciò che gira in CI)
```

Comandi singoli:

| Comando | Cosa fa |
| --- | --- |
| `npm run lint` | ESLint con regole type-aware su tutti i workspace |
| `npm run typecheck` | `tsc` in modalità stretta, sorgenti e test |
| `npm test` | Vitest (61 test sul design system) |
| `npm run build` | Compila i pacchetti in `dist/` con dichiarazioni e source map |

La CI (`.github/workflows/ci.yml`) esegue gli stessi comandi su ogni push e pull request, più ShellCheck sugli script.

### Design system

```ts
import {
  buildWorkflowInfo,
  describeCondition,
  formatCount,
  type SectionFormProps,
} from '@loyalty-hub/backoffice-design-system';
import '@loyalty-hub/backoffice-design-system/tokens.css';
```

Il pacchetto esporta i contratti dei 15 pattern e le poche regole che nessun modulo deve
reimplementare: workflow D14 (RF-137), semantica AND/OR delle condizioni (RF-138), frasi senza
identificativi tecnici (RF-139), ordine delle sezioni del form (LG-06) e formattazione italiana
di liste e KPI (LG-04, LG-40). Dettagli in [`web/backoffice-design-system/README.md`](web/backoffice-design-system/README.md).

## Installazione della piattaforma (obiettivo)

Tre comandi su Amazon EKS partendo da immagini container già costruite: `terraform apply`, `helm install loyalty-hub`, `make seed`. Ambiente locale con `docker compose up`.
