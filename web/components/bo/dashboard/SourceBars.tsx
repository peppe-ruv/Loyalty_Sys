"use client";

import { useState } from "react";
import { formatPoints } from "@/lib/format/points";
import type { MetricSlice } from "@/lib/api/insight";

// Barre "azioni per fonte" (docs/08 §BO-01, riga 2). Categoriale: colori in ordine fisso per identità di
// fonte, ogni barra è etichettata con il nome (identità mai solo-colore). Palette validata (dataviz).
// Un solo asse; barre orizzontali con data-end arrotondato; hover con conteggio e quota; tabella alternativa.

const SOURCE_ORDER = ["ecommerce", "app", "billing", "partner"] as const;
const SOURCE_COLOR: Record<string, string> = {
  ecommerce: "#2563eb",
  app: "#0d9488",
  billing: "#ea580c",
  partner: "#7c3aed",
};
const SOURCE_LABEL: Record<string, string> = {
  ecommerce: "E-commerce",
  app: "App",
  billing: "Fatturazione",
  partner: "Partner",
};

export function SourceBars({ slices, total }: { slices: MetricSlice[]; total: number }) {
  const [hover, setHover] = useState<string | null>(null);

  if (slices.length === 0 || total === 0) {
    return <p className="text-sm text-[var(--color-bo-ink-2)]">Nessuna azione nel periodo.</p>;
  }

  const ordered = [...slices].sort(
    (a, b) => rank(a.dimValue) - rank(b.dimValue) || b.value - a.value,
  );
  const max = Math.max(...ordered.map((s) => s.value), 1);

  return (
    <figure className="m-0">
      <ul className="space-y-2.5">
        {ordered.map((s) => {
          const pct = total > 0 ? (s.value / total) * 100 : 0;
          const color = SOURCE_COLOR[s.dimValue] ?? "var(--color-bo-ink-2)";
          return (
            <li
              key={s.dimValue}
              onMouseEnter={() => setHover(s.dimValue)}
              onMouseLeave={() => setHover(null)}
            >
              <div className="mb-1 flex items-baseline justify-between text-xs">
                <span className="font-medium text-[var(--color-bo-ink)]">{label(s.dimValue)}</span>
                <span className="tabular-nums text-[var(--color-bo-ink-2)]">
                  {formatPoints(s.value)} · {pct.toFixed(0)}%
                </span>
              </div>
              <div className="h-3 w-full overflow-hidden rounded-sm bg-[var(--color-bo-bg)]">
                <div
                  className="h-full rounded-sm transition-[width]"
                  style={{
                    width: `${Math.max(2, (s.value / max) * 100)}%`,
                    background: color,
                    opacity: hover && hover !== s.dimValue ? 0.5 : 1,
                  }}
                />
              </div>
            </li>
          );
        })}
      </ul>
      <TableFallback slices={ordered} total={total} />
    </figure>
  );
}

function TableFallback({ slices, total }: { slices: MetricSlice[]; total: number }) {
  return (
    <details className="mt-3">
      <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">
        Mostra tabella
      </summary>
      <table className="mt-2 w-full text-xs">
        <thead className="text-left text-[var(--color-bo-ink-2)]">
          <tr>
            <th className="py-1">Fonte</th>
            <th className="py-1 text-right">Azioni</th>
            <th className="py-1 text-right">Quota</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[var(--color-bo-border)]">
          {slices.map((s) => (
            <tr key={s.dimValue}>
              <td className="py-1">{label(s.dimValue)}</td>
              <td className="py-1 text-right tabular-nums">{formatPoints(s.value)}</td>
              <td className="py-1 text-right tabular-nums">{total > 0 ? ((s.value / total) * 100).toFixed(0) : 0}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}

function rank(source: string): number {
  const i = SOURCE_ORDER.indexOf(source as (typeof SOURCE_ORDER)[number]);
  return i === -1 ? SOURCE_ORDER.length : i;
}

function label(source: string): string {
  return SOURCE_LABEL[source] ?? source;
}
