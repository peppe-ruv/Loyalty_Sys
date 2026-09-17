/**
 * Pattern 11 — KpiTabsChart (LG-40, LG-41).
 *
 * Un solo grafico con i KPI come tab, e **sempre** il confronto con il periodo precedente
 * (tratteggiato): un numero senza il suo periodo precedente non dice se le cose vanno meglio.
 * Il grafico è un SVG disegnato a mano: nessuna libreria, nessun bundle da 300 kB per una spezzata.
 */

import type { KpiPoint, KpiSeries, KpiTabsChartProps } from '../insights.js';
import { formatValue } from '../format.js';
import { Delta } from './primitives.js';

const WIDTH = 720;
const HEIGHT = 220;
const PADDING = 24;

function path(points: KpiPoint[], min: number, max: number): string {
  if (points.length === 0) return '';
  const span = max - min || 1;
  const step = (WIDTH - PADDING * 2) / Math.max(1, points.length - 1);
  return points
    .map((point, index) => {
      const x = PADDING + step * index;
      const y = HEIGHT - PADDING - ((point.v - min) / span) * (HEIGHT - PADDING * 2);
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(' ');
}

function bounds(series: KpiSeries): { min: number; max: number } {
  const values = [...series.current, ...series.previous].map((point) => point.v);
  if (values.length === 0) return { min: 0, max: 1 };
  return { min: Math.min(...values, 0), max: Math.max(...values) };
}

export function KpiTabsChart({ kpis, series, selected, onSelect, period, onPeriodChange, onDownload }: KpiTabsChartProps) {
  const kpi = kpis.find((k) => k.key === selected) ?? kpis[0];
  const data = kpi ? series[kpi.key] : undefined;
  if (!kpi || !data) return <p className="lh-muted">Nessun KPI configurato.</p>;

  const { min, max } = bounds(data);
  const format = { format: kpi.format ?? 'integer', ...(kpi.unit === undefined ? {} : { unit: kpi.unit }) };

  return (
    <section className="lh-card" aria-label="Andamenti">
      <div className="lh-tabs" role="tablist">
        {kpis.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            className="lh-tab"
            aria-selected={item.key === kpi.key}
            title={item.definition}
            onClick={() => { onSelect(item.key); }}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="lh-table-head">
        <div>
          <p className="lh-kpi-value">{formatValue(data.currentTotal, format)}</p>
          <p className="lh-muted">
            <Delta current={data.currentTotal} previous={data.previousTotal} /> rispetto al periodo precedente
            ({formatValue(data.previousTotal, format)})
          </p>
        </div>
        <div className="lh-row">
          <label>
            <span className="lh-visually-hidden">Granularità</span>
            <select
              value={period.granularity}
              onChange={(event) => { onPeriodChange({ ...period, granularity: event.target.value as typeof period.granularity }); }}
            >
              <option value="day">Giorno</option>
              <option value="week">Settimana</option>
              <option value="month">Mese</option>
            </select>
          </label>
          {onDownload ? (
            <button type="button" className="lh-button lh-button--quiet" onClick={onDownload}>
              Scarica
            </button>
          ) : null}
        </div>
      </div>

      <svg viewBox={`0 0 ${String(WIDTH)} ${String(HEIGHT)}`} role="img" aria-label={`${kpi.label}: ${kpi.definition}`} style={{ width: '100%', height: 'auto' }}>
        <path d={path(data.previous, min, max)} fill="none" stroke="var(--lh-muted)" strokeWidth="2" strokeDasharray="6 4" />
        <path d={path(data.current, min, max)} fill="none" stroke="var(--lh-accent)" strokeWidth="2.5" />
      </svg>
      <p className="lh-muted">
        {kpi.definition} — periodo {period.from} → {period.to}.
      </p>
    </section>
  );
}
