"use client";

import { useState } from "react";
import { formatPoints } from "@/lib/format/points";
import { linearScale, niceMax, ticks } from "@/lib/charts/scale";

// Barre giornaliere (docs/08 §BO-06 Statistiche): una serie, un asse dei valori, hover per barra,
// tabella alternativa (RNF-08). Colore singolo (magnitudine), testo sempre in ink (regola dataviz).

export interface DayValue {
  day: string; // ISO date
  value: number;
}

const W = 640;
const H = 180;
const PAD = { top: 12, right: 12, bottom: 26, left: 48 };

export function DailyBars({ data, colorVar, unit }: { data: DayValue[]; colorVar: string; unit?: string }) {
  const [hover, setHover] = useState<number | null>(null);

  if (data.length === 0) {
    return <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun dato nel periodo.</p>;
  }

  const max = niceMax(Math.max(...data.map((d) => d.value), 1));
  const s = linearScale(data.length, max, W, H, PAD);
  const slot = (W - PAD.left - PAD.right) / data.length;
  const barW = Math.max(2, Math.min(slot - 2, 18));
  const yTicks = ticks(max, 3);
  const xLabelIdx = pickXLabels(data.length);

  return (
    <figure className="m-0">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label="Serie giornaliera" onMouseLeave={() => setHover(null)}>
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} y1={s.y(t)} x2={W - PAD.right} y2={s.y(t)} stroke="var(--color-bo-border)" strokeWidth={1} />
            <text x={PAD.left - 6} y={s.y(t) + 3} textAnchor="end" fontSize={10} className="fill-[var(--color-bo-ink-2)]">
              {formatPoints(t)}
            </text>
          </g>
        ))}
        {data.map((d, i) => {
          const cx = PAD.left + slot * i + slot / 2;
          const y = s.y(d.value);
          const h = s.y(0) - y;
          return (
            <g key={d.day} onMouseEnter={() => setHover(i)}>
              <rect x={cx - slot / 2} y={PAD.top} width={slot} height={H - PAD.top - PAD.bottom} fill="transparent" />
              <rect
                x={cx - barW / 2}
                y={y}
                width={barW}
                height={Math.max(0, h)}
                rx={2}
                fill={colorVar}
                fillOpacity={hover === null || hover === i ? 1 : 0.55}
              />
            </g>
          );
        })}
        {xLabelIdx.map((i) => (
          <text key={i} x={PAD.left + slot * i + slot / 2} y={H - PAD.bottom + 15} textAnchor="middle" fontSize={10} className="fill-[var(--color-bo-ink-2)]">
            {shortDay(data[i].day)}
          </text>
        ))}
      </svg>

      {hover != null ? (
        <div className="mt-1 inline-flex items-center gap-2 rounded-md border border-[var(--color-bo-border)] bg-white px-2.5 py-1 text-xs">
          <span className="font-medium text-[var(--color-bo-ink)]">{fullDay(data[hover].day)}</span>
          <span className="tabular-nums text-[var(--color-bo-ink-2)]">
            {formatPoints(data[hover].value)}
            {unit ? ` ${unit}` : ""}
          </span>
        </div>
      ) : null}

      <details className="mt-1">
        <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">Mostra tabella</summary>
        <div className="mt-2 max-h-48 overflow-auto rounded-md border border-[var(--color-bo-border)]">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-[var(--color-bo-bg)] text-left text-[var(--color-bo-ink-2)]">
              <tr>
                <th className="px-3 py-1">Giorno</th>
                <th className="px-3 py-1 text-right">Valore</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--color-bo-border)]">
              {data.map((d) => (
                <tr key={d.day}>
                  <td className="px-3 py-1 whitespace-nowrap">{fullDay(d.day)}</td>
                  <td className="px-3 py-1 text-right tabular-nums">{formatPoints(d.value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}

function pickXLabels(n: number): number[] {
  if (n <= 1) return [0];
  const stride = Math.max(1, Math.round(n / 6));
  const out: number[] = [];
  for (let i = 0; i < n; i += stride) out.push(i);
  if (out[out.length - 1] !== n - 1) out.push(n - 1);
  return out;
}

function shortDay(iso: string): string {
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", timeZone: "Europe/Rome" }).format(new Date(iso));
}

function fullDay(iso: string): string {
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", year: "numeric", timeZone: "Europe/Rome" }).format(new Date(iso));
}
