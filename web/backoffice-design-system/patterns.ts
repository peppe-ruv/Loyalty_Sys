/**
 * Loyalty Hub — design system del backoffice
 * Contratti dei 15 pattern del catalogo (docs/LINEE-GUIDA-UX-BACKOFFICE.md, sezione «Catalogo dei pattern UI»).
 *
 * Questi tipi sono il contratto tra i moduli (campagne, segmenti, achievement, concorsi, decisioni)
 * e le implementazioni React usate come custom components di Payload. Ogni prop rimanda alla LG che la impone.
 * Regola generale (RF-139): nessun tipo espone id tecnici da mostrare in frasi o chip; le entità referenziate
 * viaggiano sempre come `EntityRef { id, label }` e la UI mostra solo `label`.
 */

// ---------------------------------------------------------------- Tipi comuni

/** Riferimento a un oggetto configurabile: l'id serve al salvataggio, la label è l'unica cosa mostrata (LG-01, LG-13, RF-139). */
export interface EntityRef {
  id: string;
  label: string;
  kind: 'segment' | 'collection' | 'achievement' | 'eventSchema' | 'reward' | 'campaign' | 'wallet' | 'tier' | 'tierSet' | 'member';
  /** true se l'oggetto è in stato diverso da Pubblicato: la chip lo mostra in grigio (LG-04). */
  inactive?: boolean;
}

/** Stato di workflow D14 (RF-137). Sostituisce il toggle Attivo/Inattivo di Open Loyalty. */
export type WorkflowState =
  | 'draft'        // Bozza
  | 'inReview'     // In revisione
  | 'approved'     // Approvato (responsabile)
  | 'legalReview'  // Verifica Legal (solo regole, tier, concorsi, programma, decisioni)
  | 'scheduled'    // Programmato
  | 'published'    // Pubblicato
  | 'locked';      // Bloccato (concorso avviato)

export interface WorkflowInfo {
  state: WorkflowState;
  /** Chi ha portato l'oggetto nello stato corrente e quando (RF-41). */
  by?: { user: string; at: string };
  /** Passaggi disponibili per l'utente corrente, già filtrati per ruolo (RF-43). */
  transitions: Array<{ to: WorkflowState; label: string; requiresComment?: boolean }>;
  /** Testo che spiega l'effetto dello stato (LG-06): «La campagna partirà solo se pubblicata». */
  helpText: string;
}

/** Unità di misura da mostrare come suffisso nel campo (LG-29) o nell'intestazione di colonna (LG-04). */
export type Unit = 'EUR' | 'punti' | 'giorni' | 'volte' | 'mesi' | '%' | string;

/** Testo localizzato per campo (LG-10, RF-79). */
export type LocalizedText = Record<'it' | 'en' | string, string>;

// ---------------------------------------------------------------- 1. DataTable (LG-04, LG-05)

export interface Column<Row> {
  key: string;
  header: string;
  /** Unità mostrata nell'intestazione: «Spesa media (EUR)». */
  unit?: Unit;
  align?: 'start' | 'end';
  sortable?: boolean;
  /** Colonne nascoste per default; la preferenza per utente è salvata dal componente. */
  hidden?: boolean;
  render?: (row: Row) => React.ReactNode;
}

export interface DataTableProps<Row extends { id: string; inactive?: boolean }> {
  columns: Column<Row>[];
  rows: Row[];
  /** Totale della lista senza filtri, per il contatore ad anello «618 (83,5%) di 740». */
  total: number;
  /** Righe che soddisfano i filtri correnti. */
  filtered: number;
  filters: FilterBuilderProps;
  search?: { columns: string[]; placeholder?: string; onChange: (query: string, column?: string) => void };
  sort?: { key: string; direction: 'asc' | 'desc'; onChange: (key: string, direction: 'asc' | 'desc') => void };
  pagination: { page: number; pageSize: number; onChange: (page: number, pageSize: number) => void };
  /** Menu ⋮ per riga. */
  rowActions?: (row: Row) => Array<{ label: string; onSelect: () => void; destructive?: boolean }>;
  /** Modalità selezione con barra azioni e contatore («Esporta selezionati (3)»). */
  selection?: { enabled: boolean; actions: Array<{ label: string; onSelect: (ids: string[]) => void }> };
  /** Chiave con cui persistere colonne visibili e ordine per utente. */
  preferencesKey: string;
  emptyState: EmptyStateProps;
}

// ---------------------------------------------------------------- 2. FilterBuilder (LG-05)

export type FilterOperator =
  | 'eq' | 'neq' | 'in' | 'notIn' | 'gt' | 'gte' | 'lt' | 'lte' | 'between'
  | 'contains' | 'startsWith' | 'exists' | 'notExists' | 'daysAgoBetween';

export interface FilterAttribute {
  key: string;
  label: string;
  type: 'text' | 'number' | 'date' | 'enum' | 'entity';
  unit?: Unit;
  operators: FilterOperator[];
  /** Per type enum/entity: opzioni con ricerca. */
  options?: EntityRef[] | Array<{ value: string; label: string }>;
}

export interface FilterChip {
  attribute: string;
  operator: FilterOperator;
  value: unknown;
  /** Frase mostrata nella chip: «Wallet: uguale a Premio». */
  display: string;
}

export interface FilterBuilderProps {
  attributes: FilterAttribute[];
  chips: FilterChip[];
  /** Nessun bottone «Applica»: ogni modifica ricalcola subito. */
  onChange: (chips: FilterChip[]) => void;
}

// ---------------------------------------------------------------- 3. ChoiceCards (LG-07, LG-11)

export interface ChoiceCardOption<V extends string = string> {
  value: V;
  title: string;
  description: string;
  /** Esempi concreti mostrati nella card (achievement, LG-31/LG-32). */
  examples?: string[];
  disabled?: { reason: string };
}

export interface ChoiceCardsProps<V extends string = string> {
  legend: string;
  options: ChoiceCardOption<V>[];
  value?: V;
  onChange: (value: V) => void;
  /** Cambiare la scelta ripulisce i campi dipendenti: il componente chiede conferma se `dirtyDependents` è true. */
  dirtyDependents?: boolean;
}

// ---------------------------------------------------------------- 4. SectionForm (LG-06)

export type SectionKind = 'type' | 'basics' | 'logic' | 'customAttributes' | 'limits' | 'visibility' | 'state';

export interface FormSection {
  kind: SectionKind;
  title: string;
  /** Aiuto sotto il titolo che spiega la conseguenza, non la definizione (LG-08). */
  help?: string;
  learnMoreHref?: string;
  content: React.ReactNode;
}

export interface SectionFormProps {
  breadcrumb: Array<{ label: string; href?: string }>;
  /** Le sezioni sono renderizzate nell'ordine fisso di `SectionKind`, non in quello dell'array. */
  sections: FormSection[];
  workflow: WorkflowInfo;
  /** Riquadro «Usato da / Usa» (RF-141): presente su segmenti, campagne, achievement, collection, premi. */
  dependencies?: DependenciesPanelProps;
  primaryAction: { label: string; onClick: () => void; disabled?: boolean };
  secondaryAction?: { label: string; onClick: () => void };
  /** Avviso rosso fisso sopra la logica quando il salvataggio distrugge dati (LG-09, LG-33). */
  destructiveWarning?: { message: string; affectedCount?: number };
}

export interface DependenciesPanelProps {
  usedBy: EntityRef[];
  uses: EntityRef[];
  /** L'eliminazione è bloccata se `usedBy` non è vuoto (RF-141). */
  onNavigate: (ref: EntityRef) => void;
}

// ---------------------------------------------------------------- 5. ConditionRow + 6. ConditionPicker (LG-13, LG-14, LG-28)

export type LogicalOperator = 'AND' | 'OR';

export interface ConditionType {
  key: string;
  /** Nome con contesto tra parentesi: «Punti attivi (Wallet premio)». */
  label: string;
  category: 'popular' | 'member' | 'trigger' | 'expression';
  operators: FilterOperator[];
  valueType: 'text' | 'number' | 'date' | 'enum' | 'entity' | 'range' | 'expression';
  unit?: Unit;
  options?: EntityRef[] | Array<{ value: string; label: string }>;
  help?: string;
}

export interface Condition {
  id: string;
  type: string;
  operator: FilterOperator;
  value: unknown;
  /** Chip risolte: la frase «[Tier] non è uno di [Elite]» usa queste label, mai gli id (RF-139). */
  displayChips: Array<{ text: string; ref?: EntityRef }>;
}

export interface ConditionRowProps {
  index: number;
  condition: Condition;
  /** Operatore mostrato tra questa riga e la successiva: sempre scritto (LG-28, RF-138). */
  joinWithNext?: LogicalOperator;
  editing: boolean;
  types: ConditionType[];
  onEdit: () => void;
  onSave: (c: Condition) => void;
  onCancel: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
}

export interface ConditionPickerProps {
  open: boolean;
  types: ConditionType[];
  /** Le «Popolari» sono calcolate dal team: le più usate negli ultimi 90 giorni. */
  onPick: (type: ConditionType) => void;
  onClose: () => void;
}

// ---------------------------------------------------------------- 7. RuleCard (LG-12)

export type EffectKind =
  | 'addUnits' | 'removeUnits' | 'grantReward' | 'setAttribute' | 'unsetAttribute'
  | 'grantBadge' | 'setTier' | 'emitEvent';

export interface Effect {
  id: string;
  kind: EffectKind;
  wallet?: EntityRef;
  reward?: EntityRef;
  attribute?: string;
  /** Formula SpEL o costante, resa da FormulaInput (LG-16). */
  formula?: Formula;
  /** Eccezione alle regole del wallet (LG-17). */
  expiration?: InheritedSettingValue<{ method: 'none' | 'afterDays' | 'annualDate'; formula?: Formula }>;
  activation?: InheritedSettingValue<{ delayDaysFormula?: Formula }>;
}

export interface Rule {
  id: string;
  name: string;
  description?: string;
  collapsed?: boolean;
  /** Dentro la regola le condizioni sono in AND (RF-138). */
  conditions: Condition[];
  effects: Effect[];
}

export interface RuleCardProps {
  rule: Rule;
  /** Operatore tra questa regola e la successiva (OR, RF-138). */
  joinWithNext?: LogicalOperator;
  conditionTypes: ConditionType[];
  effectKinds: EffectKind[];
  onChange: (rule: Rule) => void;
  onDuplicate: () => void;
  onDelete: () => void;
  dragHandleProps?: Record<string, unknown>;
}

// ---------------------------------------------------------------- 8. FormulaInput (LG-16)

export interface Formula {
  /** Espressione SpEL valida per il rules-engine (RF-84). */
  expression: string;
  /** Chip leggibile: «200» oppure «add_days_to_date(transaction.purchasedAt, 7)». */
  display: string;
}

export interface FormulaVariable {
  path: string;
  label: string;
  type: 'number' | 'string' | 'date' | 'boolean';
  example?: string;
}

export interface FormulaInputProps {
  value?: Formula;
  variables: FormulaVariable[];
  /** Validazione server-side prima del salvataggio: ritorna errore o null. */
  validate: (expression: string) => Promise<string | null>;
  onChange: (f: Formula) => void;
  examplesHref?: string;
}

// ---------------------------------------------------------------- 9. InheritedSetting (LG-17)

export interface InheritedSettingValue<T> {
  override: boolean;
  inherited: T;
  /** Frase del valore ereditato: «Scadenza: dopo 365 giorni (dal wallet Premio)». */
  inheritedDisplay: string;
  value?: T;
}

export interface InheritedSettingProps<T> {
  label: string;
  value: InheritedSettingValue<T>;
  /** Avviso mostrato quando override è acceso: «vale solo per questo effetto». */
  scopeWarning: string;
  renderFields: (value: T, onChange: (v: T) => void) => React.ReactNode;
  onChange: (v: InheritedSettingValue<T>) => void;
}

// ---------------------------------------------------------------- 10. TemplateGallery (LG-31)

export interface TemplateCard<T> {
  id: string;
  title: string;
  category: string;
  businessValue: string;
  /** Dati che precompilano il form; restano modificabili. */
  prefill: Partial<T>;
}

export interface TemplateGalleryProps<T> {
  categories: string[];
  templates: TemplateCard<T>[];
  onStartBlank: () => void;
  onUseTemplate: (t: TemplateCard<T>) => void;
}

// ---------------------------------------------------------------- 11. KpiTabsChart (LG-40, LG-41)

export interface KpiDefinition {
  key: string;
  label: string;
  /** Definizione nel tooltip «?» (LG-08). Deve coincidere con la vista KPI di ClickHouse/Superset (RF-121). */
  definition: string;
  unit?: Unit;
  format?: 'integer' | 'decimal' | 'currency' | 'percent';
}

export interface KpiSeries {
  key: string;
  current: Array<{ t: string; v: number }>;
  /** Periodo precedente, disegnato tratteggiato. */
  previous: Array<{ t: string; v: number }>;
  currentTotal: number;
  previousTotal: number;
}

export interface KpiTabsChartProps {
  kpis: KpiDefinition[];
  series: Record<string, KpiSeries>;
  selected: string;
  onSelect: (key: string) => void;
  period: { from: string; to: string; granularity: 'day' | 'week' | 'month' };
  onPeriodChange: (p: KpiTabsChartProps['period']) => void;
  filters?: FilterBuilderProps;
  onDownload?: () => void;
}

// ---------------------------------------------------------------- 12. EntityProfile (LG-35, LG-26)

export interface IdentityField {
  label: string;
  value: string;
  copyable?: boolean;
  /** «tra N giorni» per date future (compleanno). */
  relative?: string;
}

export interface EntityProfileProps {
  initials: string;
  status: { label: string; tone: 'ok' | 'warn' | 'bad' | 'muted' };
  identity: IdentityField[];
  chips: Array<{ label: string; kind: 'tier' | 'segment' | 'risk' }>;
  tabs: Array<{ key: string; label: string; content: React.ReactNode; badge?: number }>;
  actions: Array<{ label: string; onSelect: () => void; requiresComment?: boolean; fourEyesAboveThreshold?: boolean }>;
  /** Riquadro tier con lucchetto e progresso (LG-26). */
  tierPanel?: {
    current: EntityRef;
    lockedUntil?: string;
    lastPromotion?: string;
    lastDemotion?: string;
    nextRecalc?: string;
    progressToNext: { value: number; threshold: number; unit: Unit };
  };
}

// ---------------------------------------------------------------- 13. Timeline (LG-37)

export type TimelineEventType =
  | 'MOVIMENTO' | 'PREMIO' | 'TRANSAZIONE' | 'CAMBIO_TIER' | 'GIOCATA' | 'ACHIEVEMENT' | 'DECISIONE' | 'CONSENSO' | 'NOTA';

export interface TimelineEvent {
  id: string;
  at: string;
  type: TimelineEventType;
  /** Frase, non codice: «Guadagnati 984 punti premio: bolletta pagata puntuale». */
  title: string;
  meta: Array<{ label: string; value: string }>;
  /** Saldo attivo dopo il movimento, per wallet. */
  balanceAfter?: Array<{ wallet: EntityRef; value: number }>;
  /** Autore e motivo per le operazioni manuali (RF-140). */
  audit?: { user: string; reason: string };
}

export interface TimelineProps {
  events: TimelineEvent[];
  filters: FilterBuilderProps;
  onLoadMore?: () => void;
}

// ---------------------------------------------------------------- 14. ImportFlow (LG-44, LG-45)

export type ImportType =
  | 'members' | 'segmentMembers' | 'unitsAdd' | 'unitsRemove' | 'collectionValues'
  | 'campaignsJson' | 'achievementsJson' | 'eventSchemasJson' | 'configBundle';

export interface ImportJob {
  id: string;
  type: ImportType;
  fileName: string;
  createdAt: string;
  status: 'queued' | 'running' | 'done' | 'failed';
  records?: number;
  rejected?: number;
}

export interface ImportReviewItem {
  ref: EntityRef;
  status: 'imported' | 'actionRequired';
  /** Riferimenti non risolti nella destinazione, da rimappare (LG-44). */
  missing?: Array<{ field: string; expected: EntityRef; candidates: EntityRef[] }>;
}

export interface ImportFlowProps {
  type: ImportType;
  accept: string[];
  maxSizeMb: number;
  guideHref: string;
  sampleFileHref: string;
  history: ImportJob[];
  onUpload: (file: File) => Promise<ImportJob>;
  review?: { items: ImportReviewItem[]; onRemap: (item: ImportReviewItem, field: string, to: EntityRef) => void };
}

// ---------------------------------------------------------------- 15. EmptyState (LG-45, LG-27)

export interface EmptyStateProps {
  icon?: React.ReactNode;
  /** Titolo d'azione: «Aggiungi la prima condizione». */
  title: string;
  /** Una frase su cosa succede dopo. */
  description: string;
  primaryAction?: { label: string; onClick: () => void };
  guideHref?: string;
}

// ---------------------------------------------------------------- Feedback (LG-09)

export interface Toast { tone: 'success' | 'error' | 'info'; message: string; href?: string }
export interface LongOperationModal { title: string; message: string; followHref: string }

// Tipi React minimi per non dipendere da @types/react in questo file di contratti.
declare namespace React { type ReactNode = unknown }
