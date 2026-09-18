# Playground · Loyalty Hub

> 🇮🇹 Documento bilingue. 🇬🇧 Bilingual document.

Un sito statico che mostra due parti di Loyalty Hub che di solito restano invisibili: i **pattern del backoffice**
con cui si configura il programma e il **motore decisionale** con i suoi vincoli. Dati inventati, nessun backend,
nessun dato personale: gira tutto nel browser.

**EN** — A static site showing two parts of Loyalty Hub that usually stay invisible: the **back-office patterns** used
to configure the programme and the **decision engine** with its constraints. Fake data, no backend, no personal data:
everything runs in the browser.

## A che serve · What it is for

| Pagina · Page | Mostra · Shows |
| --- | --- |
| `/` | Che cosa fa la piattaforma e come si muove un evento · what the platform does and how an event flows |
| `/pattern` | Lista con filtri, form a sezioni, regole leggibili, KPI, scheda membro — **gli stessi componenti del backoffice** · the very same components the back office uses |
| `/decisioni` | Simulatore del motore: cambia profilo, ora e budget e guarda scelte e scarti con il loro codice motivo · engine simulator with choices and rejections, each with its reason code |
| `/token` | I token del tema, chiaro e scuro · theme tokens, light and dark |

Il valore della pagina «Decisioni» è lo **scarto motivato**: è lì che si capisce perché un cliente non ha ricevuto
l'offerta, che è la domanda che il marketing fa davvero.

## Come si costruisce · How to build

```sh
make playground          # dalla radice: costruisce design system + playground
npm run dev --workspace @loyalty-hub/playground   # sviluppo su http://localhost:3010
```

L'esportazione è **statica** (`output: 'export'` → `out/`): il playground non ha un backend e non deve averne uno.
Si pubblica su qualunque CDN senza funzioni server, senza segreti e senza costi.

## Il motore che si vede qui non è il motore

`lib/decision.ts` è una riproduzione ridotta e fedele di `DecisionEngine` (`services/decision-service`), che resta
l'unica autorità: stessi vincoli, stessi codici motivo (`CONSENT_MISSING`, `QUIET_HOURS`, `NO_CHANNEL`,
`UNITS_BUDGET`, `RISK_BLOCK`, `OUTRANKED`), nessun effetto. Serve a far vedere *come* si decide senza avviare
quattordici servizi. Quando il motore vero cambia una regola, questa copia va aggiornata o tolta: una copia che mente
è peggio di nessuna copia.

**EN** — `lib/decision.ts` is a faithful but reduced mirror of the real `DecisionEngine`, which remains the only
authority: same constraints, same reason codes, no effects. If the real engine changes a rule, this copy must follow
or go: a copy that lies is worse than no copy.

## Pubblicazione · Publishing

Il playground è pensato per Vercel come sito statico. Il progetto esiste già:
`loyalty-hub-playground` (`prj_LGKp9uCKS1rebq7INC8qMFVEWVQN`), con root directory `web/playground`, file esterni alla
root abilitati (il playground usa il design system del monorepo) e output `out/`.

**Per pubblicare serve un passo che solo il proprietario dell'account può fare**, perché tocca le credenziali:

| Strada · Path | Che cosa fare · What to do |
| --- | --- |
| **Consigliata** — pubblicazione dalla CI | Creare un token in Vercel (*Account Settings → Tokens*) e aggiungerlo al repository come segreto `VERCEL_TOKEN`. Da quel momento `.github/workflows/playground.yml` costruisce e pubblica a ogni push su `main` che tocca il playground o il design system |
| Alternativa — integrazione Git di Vercel | Collegare l'account GitHub a Vercel (*Account Settings → Login Connections*) e importare il repository: Vercel costruisce da sé a ogni push |
| Manuale | `make playground` e poi `npx vercel deploy --prebuilt --prod` da `web/playground`, oppure caricare `out/` su qualunque hosting statico |

Senza il token il workflow si salta da solo: chi lavora sul repository non vede rosso per una credenziale che non ha.

**EN** — The Vercel project already exists; publishing needs one step only the account owner can take: add a
`VERCEL_TOKEN` repository secret (recommended — CI then builds and publishes on every relevant push to `main`), or
connect GitHub to Vercel and import the repository. Without the token the workflow skips itself instead of failing.
