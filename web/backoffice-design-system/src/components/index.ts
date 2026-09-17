/**
 * Componenti React che implementano i pattern del design system.
 *
 * I contratti stanno in `patterns.ts` e restano il riferimento; qui c'è la loro unica
 * implementazione. Un modulo che costruisce una lista, un form o un costruttore di condizioni
 * fuori da questi componenti sta creando un secondo design system (ADR-026).
 *
 * Il foglio di stile va importato una volta dall'applicazione, dopo i token:
 * ```ts
 * import '@loyalty-hub/backoffice-design-system/tokens.css';
 * import '@loyalty-hub/backoffice-design-system/components.css';
 * ```
 */

export { Button, Chip, Delta, EntityLink, RingCounter, Value, type ButtonProps, type ChipProps, type Tone } from './primitives.js';
export { DataTable, FilterChips } from './DataTable.js';
export { EmptyState, LongOperationNotice, ToastView } from './feedback.js';
export { DependenciesPanel, SectionForm, WorkflowSection } from './SectionForm.js';
export { ConditionRow, EffectRow, RuleCard, RuleList, type ConditionViewProps } from './RuleCard.js';
export { KpiTabsChart } from './KpiTabsChart.js';
export { EntityProfile, Timeline } from './EntityProfile.js';
