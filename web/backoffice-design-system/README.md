# Design system del backoffice Loyalty Hub

Pacchetto `@loyalty-hub/backoffice-design-system`. Implementa il «Catalogo dei pattern UI» di
[`docs/LINEE-GUIDA-UX-BACKOFFICE.md`](../../docs/LINEE-GUIDA-UX-BACKOFFICE.md) (ADR-026): contratti
dei 15 pattern, token di tema e le regole che i moduli non devono reimplementare.

## Contenuto

| File | Contenuto |
| --- | --- |
| `src/patterns.ts` | Punto d'ingresso dei **soli tipi**: i contratti dei 15 pattern e i tipi comuni (`EntityRef`, `WorkflowInfo`, `Formula`) |
| `src/index.ts` | Punto d'ingresso pubblico: i tipi più le funzioni elencate sotto |
| `src/common.ts` | `EntityRef`, unità, testi localizzati, guardie |
| `src/workflow.ts` | Stati ADR-014, transizioni per ruolo, `buildWorkflowInfo` (RF-137, RF-43) |
| `src/filters.ts` | FilterBuilder, vocabolario degli operatori, frasi delle chip (LG-05) |
| `src/data-table.ts` | DataTable (LG-04) |
| `src/forms.ts` | SectionForm, ChoiceCards, InheritedSetting, ordine delle sezioni (LG-06, LG-07, LG-17) |
| `src/rules.ts` | ConditionRow, ConditionPicker, RuleCard, FormulaInput, effetti (LG-12..LG-16) |
| `src/insights.ts` | KpiTabsChart e TemplateGallery (LG-31, LG-40, LG-41) |
| `src/member.ts` | EntityProfile e Timeline (LG-26, LG-35, LG-37) |
| `src/imports.ts` | ImportFlow (LG-44, LG-45) |
| `src/feedback.ts` | EmptyState, toast, modale delle operazioni lunghe (LG-09, LG-45) |
| `src/format.ts` | Formattazione italiana di valori, contatori e variazioni (LG-04, LG-29, LG-40) |
| `tokens.css` | Token di colore, tipografia, spaziature e tema chiaro/scuro, tutti con prefisso `--lh-` |

## Uso

```ts
import {
  buildCondition,
  buildWorkflowInfo,
  describeCondition,
  formatCount,
  type SectionFormProps,
} from '@loyalty-hub/backoffice-design-system';
import '@loyalty-hub/backoffice-design-system/tokens.css';

// Sezione «Stato» del form: transizioni già filtrate per ruolo e verifica Legal (RF-137).
const workflow = buildWorkflowInfo('approved', { roles: ['publisher'], legalRequired: true });

// Riga di condizione: la frase usa solo le label, mai gli id (RF-139).
const condizione = buildCondition({ id: 'c1', type: 'member.tier', operator: 'notIn', value: [elite] }, tipi);
describeCondition(condizione, tipi); // «Tier non è uno di Elite»

// Contatore ad anello in cima alla lista (LG-04).
formatCount(618, 740); // «618 (83,5%) di 740»
```

Chi consuma il pacchetto deve avere `@types/react` (peer dependency): le prop `render` e
`content` dei pattern sono nodi React.

## Regole d'uso

1. I moduli del backoffice (custom components Payload in `cms/loyalty-plugin/ui/`) importano i contratti da qui e non definiscono liste, form o costruttori di condizioni propri.
2. Ogni componente cita nel JSDoc le LG che implementa; una PR che tocca una schermata elenca le LG applicate.
3. Le entità referenziate viaggiano sempre come `EntityRef` e la UI mostra solo `label` (RF-139): le frasi si costruiscono con `describeCondition`/`buildFilterChip`, mai concatenando stringhe a mano.
4. Lo stato degli oggetti è `WorkflowInfo` (ADR-014, RF-137): nessun toggle Attivo/Inattivo isolato, nessuna transizione decisa dal singolo modulo.
5. Le condizioni sono in AND dentro una regola e in OR tra regole, e l'operatore è sempre scritto tra le righe (RF-138): usare le costanti `CONDITION_JOIN` e `RULE_JOIN`.
6. I componenti usano solo i token `--lh-*` di `tokens.css`, mai colori o spaziature letterali.

## Sviluppo

```bash
npm run build        # compila in dist/ con dichiarazioni e source map
npm run typecheck    # tsc in modalità stretta su sorgenti e test
npm test             # Vitest
npm run test:watch
```

I test coprono ciò che le linee guida rendono verificabile: raggiungibilità e filtro per ruolo
degli stati di workflow, assenza di identificativi tecnici nelle frasi, formati italiani dei KPI,
ordine delle sezioni del form e parità dei token fra tema chiaro e scuro.

## Ordine di costruzione consigliato

1. Token + EmptyState + Toast (mezza settimana)
2. DataTable + FilterBuilder (una settimana): sbloccano tutte le liste
3. SectionForm + ChoiceCards + InheritedSetting (una settimana)
4. ConditionRow + ConditionPicker + RuleCard + FormulaInput (una settimana): editor campagna, prima schermata completa
5. EntityProfile + Timeline (mezza settimana)
6. KpiTabsChart, TemplateGallery, ImportFlow (una settimana)

Le implementazioni React vanno in `src/<Pattern>/` con uno story per stato (vuoto, pieno, in
modifica, errore). Il rendering dei KPI usa le stesse definizioni pubblicate dalle viste
ClickHouse (`bi/views/`), così tooltip e cruscotto Superset restano allineati (LG-42).
