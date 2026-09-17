/**
 * Punto di ingresso del design system del backoffice Loyalty Hub.
 *
 * Esporta i contratti dei 15 pattern (vedi `patterns.ts`) e le poche regole eseguibili che
 * i moduli non devono reimplementare: workflow D14 (RF-137), ordine delle sezioni del form
 * (LG-06), frasi di condizioni e chip senza identificativi tecnici (RF-138, RF-139) e
 * formattazione dei valori mostrati in liste e KPI (LG-04, LG-40).
 *
 * I token di tema si importano a parte: `@loyalty-hub/backoffice-design-system/tokens.css`.
 */

export * from './patterns.js';

export { ENTITY_KINDS, entityLabel, isEntityRef, localized } from './common.js';

export {
  WORKFLOW_ROLES,
  WORKFLOW_STATES,
  WORKFLOW_STATE_HELP,
  WORKFLOW_STATE_LABELS,
  WORKFLOW_TRANSITIONS,
  availableTransitions,
  buildWorkflowInfo,
  canTransition,
  isWorkflowState,
  transitionRequiresComment,
} from './workflow.js';

export {
  FILTER_OPERATORS,
  OPERATOR_LABELS,
  UNARY_OPERATORS,
  buildFilterChip,
  chipEntityRefs,
  describeFilterChip,
  describeValue,
} from './filters.js';

export {
  SECTION_LABELS,
  SECTION_ORDER,
  effectiveValue,
  isDeletionBlocked,
  sortSections,
} from './forms.js';

export {
  CONDITION_JOIN,
  EFFECT_KINDS,
  EFFECT_LABELS,
  RULE_JOIN,
  UnknownConditionTypeError,
  buildCondition,
  buildDisplayChips,
  describeCondition,
  findConditionType,
} from './rules.js';

export { TIMELINE_EVENT_LABELS, TIMELINE_EVENT_TYPES } from './member.js';

export { IMPORT_TYPES, needsReview } from './imports.js';

export {
  DEFAULT_CURRENCY,
  DEFAULT_LOCALE,
  formatCount,
  formatDelta,
  formatRelativeDays,
  formatValue,
} from './format.js';
