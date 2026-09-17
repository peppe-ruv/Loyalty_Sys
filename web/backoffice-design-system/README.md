# Design system del backoffice Loyalty Hub

Implementa il «Catalogo dei pattern UI» di `docs/LINEE-GUIDA-UX-BACKOFFICE.md` (ADR-026).

| File | Contenuto |
| --- | --- |
| `patterns.ts` | Contratti TypeScript dei 15 pattern (DataTable, FilterBuilder, ChoiceCards, SectionForm, ConditionRow, ConditionPicker, RuleCard, FormulaInput, InheritedSetting, TemplateGallery, KpiTabsChart, EntityProfile, Timeline, ImportFlow, EmptyState) più i tipi comuni `EntityRef`, `WorkflowInfo`, `Formula` |
| `tokens.css` | Token di colore, tipografia e tema chiaro/scuro (dal mockup «Backoffice Loyalty Hub») |

## Regole d'uso

1. I moduli del backoffice (custom components Payload in `cms/loyalty-plugin/ui/`) importano i contratti da qui e non definiscono liste, form o costruttori di condizioni propri.
2. Ogni componente cita nel JSDoc le LG che implementa; una PR che tocca una schermata elenca le LG applicate.
3. Le entità referenziate viaggiano sempre come `EntityRef` e la UI mostra solo `label` (RF-139).
4. Lo stato degli oggetti è `WorkflowInfo` (D14, RF-137): nessun toggle Attivo/Inattivo isolato.
5. Le condizioni sono in AND dentro una regola e in OR tra regole, e l'operatore è sempre scritto tra le righe (RF-138).

## Ordine di costruzione consigliato

1. Token + EmptyState + Toast (mezza settimana)
2. DataTable + FilterBuilder (una settimana): sbloccano tutte le liste
3. SectionForm + ChoiceCards + InheritedSetting (una settimana)
4. ConditionRow + ConditionPicker + RuleCard + FormulaInput (una settimana): editor campagna, prima schermata completa
5. EntityProfile + Timeline (mezza settimana)
6. KpiTabsChart, TemplateGallery, ImportFlow (una settimana)

## Sviluppo

Le implementazioni React vanno in `src/<Pattern>/` con uno story per stato (vuoto, pieno, in modifica, errore). Il rendering dei KPI usa le stesse definizioni pubblicate dalle viste ClickHouse (`bi/views/`), così tooltip e cruscotto Superset restano allineati (LG-42).
