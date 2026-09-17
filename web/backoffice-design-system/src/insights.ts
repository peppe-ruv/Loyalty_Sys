/**
 * Pattern 10 e 11 — TemplateGallery (LG-31) e KpiTabsChart (LG-40, LG-41).
 *
 * I KPI sono tab di un solo grafico, sempre con il confronto al periodo precedente; le
 * definizioni nei tooltip devono coincidere con le viste KPI di ClickHouse/Superset (RF-121, LG-42).
 */

import type { IsoDate, IsoDateTime, Unit } from './common.js';
import type { FilterBuilderProps } from './filters.js';
import type { ValueFormat } from './format.js';

// ------------------------------------------------------------------ TemplateGallery

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
  templates: Array<TemplateCard<T>>;
  onStartBlank: () => void;
  onUseTemplate: (template: TemplateCard<T>) => void;
}

// ------------------------------------------------------------------ KpiTabsChart

export interface KpiDefinition {
  key: string;
  label: string;
  /** Definizione nel tooltip «?» (LG-08). Deve coincidere con la vista KPI di ClickHouse (RF-121). */
  definition: string;
  unit?: Unit;
  format?: ValueFormat;
}

export interface KpiPoint {
  t: IsoDateTime;
  v: number;
}

export interface KpiSeries {
  key: string;
  current: KpiPoint[];
  /** Periodo precedente, disegnato tratteggiato (LG-41). */
  previous: KpiPoint[];
  currentTotal: number;
  previousTotal: number;
}

export type Granularity = 'day' | 'week' | 'month';

export interface KpiPeriod {
  from: IsoDate;
  to: IsoDate;
  granularity: Granularity;
}

export interface KpiTabsChartProps {
  kpis: KpiDefinition[];
  series: Record<string, KpiSeries>;
  selected: string;
  onSelect: (key: string) => void;
  period: KpiPeriod;
  onPeriodChange: (period: KpiPeriod) => void;
  filters?: FilterBuilderProps;
  onDownload?: () => void;
}
