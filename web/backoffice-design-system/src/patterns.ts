/**
 * Loyalty Hub — contratti dei 15 pattern del catalogo UI del backoffice
 * (docs/LINEE-GUIDA-UX-BACKOFFICE.md, sezione «Catalogo dei pattern UI»; ADR-026).
 *
 * Questo file è il punto di ingresso dei soli tipi: è il contratto tra i moduli (campagne,
 * segmenti, achievement, concorsi, decisioni) e le implementazioni React usate come custom
 * components di Payload. Ogni prop rimanda alla LG che la impone.
 *
 * | # | Pattern          | Modulo            | Linee guida      |
 * | - | ---------------- | ----------------- | ---------------- |
 * | 1 | DataTable        | `data-table.ts`   | LG-04, LG-05     |
 * | 2 | FilterBuilder    | `filters.ts`      | LG-05            |
 * | 3 | ChoiceCards      | `forms.ts`        | LG-07, LG-11     |
 * | 4 | SectionForm      | `forms.ts`        | LG-06            |
 * | 5 | ConditionRow     | `rules.ts`        | LG-13, LG-28     |
 * | 6 | ConditionPicker  | `rules.ts`        | LG-14            |
 * | 7 | RuleCard         | `rules.ts`        | LG-12            |
 * | 8 | FormulaInput     | `rules.ts`        | LG-16            |
 * | 9 | InheritedSetting | `forms.ts`        | LG-17            |
 * |10 | TemplateGallery  | `insights.ts`     | LG-31            |
 * |11 | KpiTabsChart     | `insights.ts`     | LG-40, LG-41     |
 * |12 | EntityProfile    | `member.ts`       | LG-35, LG-26     |
 * |13 | Timeline         | `member.ts`       | LG-37            |
 * |14 | ImportFlow       | `imports.ts`      | LG-44, LG-45     |
 * |15 | EmptyState       | `feedback.ts`     | LG-45, LG-27     |
 */

export type {
  EntityKind,
  EntityRef,
  IsoDate,
  IsoDateTime,
  Locale,
  LocalizedText,
  OpenString,
  OptionSource,
  SelectOption,
  Unit,
} from './common.js';

export type {
  WorkflowContext,
  WorkflowInfo,
  WorkflowRole,
  WorkflowState,
  WorkflowTransition,
} from './workflow.js';

export type { Column, DataTableProps, RowAction, SortDirection, TableRow } from './data-table.js';

export type { FilterAttribute, FilterBuilderProps, FilterChip, FilterOperator } from './filters.js';

export type {
  ChoiceCardOption,
  ChoiceCardsProps,
  DependenciesPanelProps,
  FormSection,
  InheritedSettingProps,
  InheritedSettingValue,
  SectionFormProps,
  SectionKind,
} from './forms.js';

export type {
  ActivationSetting,
  Condition,
  ConditionPickerProps,
  ConditionRowProps,
  ConditionType,
  DisplayChip,
  Effect,
  EffectKind,
  ExpirationMethod,
  ExpirationSetting,
  Formula,
  FormulaInputProps,
  FormulaVariable,
  LogicalOperator,
  Rule,
  RuleCardProps,
} from './rules.js';

export type {
  Granularity,
  KpiDefinition,
  KpiPeriod,
  KpiPoint,
  KpiSeries,
  KpiTabsChartProps,
  TemplateCard,
  TemplateGalleryProps,
} from './insights.js';

export type {
  EntityProfileProps,
  IdentityField,
  ProfileAction,
  TierPanel,
  TimelineEvent,
  TimelineEventType,
  TimelineProps,
} from './member.js';

export type {
  ImportFlowProps,
  ImportJob,
  ImportReviewItem,
  ImportStatus,
  ImportType,
} from './imports.js';

export type { EmptyStateProps, LongOperationModal, Toast } from './feedback.js';

export type { FormatOptions, ValueFormat } from './format.js';
