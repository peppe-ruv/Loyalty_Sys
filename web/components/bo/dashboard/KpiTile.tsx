"use client";

import { formatPoints } from "@/lib/format/points";
import type { KpiDelta } from "@/lib/api/insight";
import { linePath } from "@/lib/charts/scale";

// KpiTile (docs/08 §BO-01, riga 1): etichetta, valore, delta sul periodo precedente e sparkline.
// Il numero è il segnale; il delta è secondario; la sparkline dà la forma. Testo sempre in ink,
// mai nel colore del tratto (regola dataviz).

export function KpiTile({
  label,
  value,
  unit,
  delta,
  spark,
  hint,
}: {
  label: string;
  value: number;
  unit?: string;
  delta?: KpiDelta;
  spark?: number[];
  hint?: string;
}) {
  return (
    <div className="rounded-lg border border-[var(--color-bo-border)] bg-white p-4">
      <p className="text-xs font-medium text-[var(--color-bo-ink-2)]">{label}</p>
      <div className="mt-1 flex items-end justify-between gap-2">
        <p className="text-2xl font-semibold tabular-nums text-[var(--color-bo-ink)]">
          {formatPoints(value)}
          {unit ? <span className="ml-1 text-sm font-normal text-[var(--color-bo-ink-2)]">{unit}</span> : null}
        </p>
        {spark && spark.length > 1 ? <Sparkline data={spark} /> : null}
      </div>
      <div className="mt-1 flex items-center gap-2">
        {delta ? <DeltaChip delta={delta} /> : <span className="text-xs text-[var(--color-bo-ink-2)]">—</span>}
        {hint ? <span className="text-[11px] text-[var(--color-bo-ink-2)]">{hint}</span> : null}
      </div>
    </div>
  );
}

function DeltaChip({ delta }: { delta: KpiDelta }) {
  if (delta.pct == null) {
    return <span className="text-xs text-[var(--color-bo-ink-2)]">nuovo</span>;
  }
  const up = delta.abs >= 0;
  const tone = up ? "text-emerald-700" : "text-red-700";
  const pct = Math.abs(delta.pct);
  const pctLabel = pct >= 100 ? Math.round(pct) : pct.toFixed(pct < 10 ? 1 : 0);
  return (
    <span className={`inline-flex items-center gap-0.5 text-xs font-medium tabular-nums ${tone}`}>
      <span aria-hidden>{up ? "▲" : "▼"}</span>
      {pctLabel}%<span className="sr-only">{up ? "in aumento" : "in calo"} sul periodo precedente</span>
    </span>
  );
}

function Sparkline({ data }: { data: number[] }) {
  const w = 72;
  const h = 24;
  const max = Math.max(...data, 1);
  const min = Math.min(...data, 0);
  const span = max - min || 1;
  const pts = data.map((v, i): [number, number] => [
    (w * i) / (data.length - 1),
    h - 2 - (h - 4) * ((v - min) / span),
  ]);
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="shrink-0" aria-hidden>
      <path d={linePath(pts)} fill="none" stroke="var(--color-bo-accent)" strokeWidth={1.5} />
    </svg>
  );
}
