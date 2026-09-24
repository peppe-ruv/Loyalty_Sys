"use client";

import { useState } from "react";
import type { InstantDay } from "@/lib/api/types";
import { linearScale, niceMax, ticks } from "@/lib/charts/scale";
import { fillInstantDays } from "@/lib/gamification/contests";

// Istogramma degli istanti per giorno (docs/08 §BO-14): barre impilate per stato, visibile anche a chi non vede la
// tabella degli istanti. Colori categoriali in ordine fisso (palette di riferimento dataviz, validata: CVD ≥ 8),
// 2 px di stacco tra i segmenti, estremità arrotondata solo in cima; legenda, hover per giorno e tabella.

const SERIES = [
  { key: "claimed", label: "Assegnati", color: "#2a78d6" },
  { key: "open", label: "Aperti", color: "#eb6834" },
  { key: "voided", label: "Annullati", color: "#1baf7a" },
] as const;

const W = 640;
const H = 190;
const PAD = { top: 12, right: 12, bottom: 26, left: 36 };
const GAP = 2;

export function InstantHistogram({ days }: { days: InstantDay[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const data = fillInstantDays(days);
  if (data.length === 0) {
    return <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun istante generato.</p>;
  }
  const present = SERIES.filter((s) => data.some((d) => d[s.key] > 0));
  const max = niceMax(Math.max(...data.map((d) => d.total), 1));
  const sc = linearScale(data.length, max, W, H, PAD);
  const slot = (W - PAD.left - PAD.right) / data.length;
  const barW = Math.max(2, Math.min(slot - 2, 16));
  const yTicks = ticks(max, max % 4 === 0 ? 4 : max % 5 === 0 ? 5 : 2);
  const labelIdx = pickLabels(data.length);

  return (
    <figure className="m-0">
      <div className="mb-2 flex flex-wrap gap-3 text-xs text-[var(--color-bo-ink-2)]" aria-hidden>
        {present.map((s) => (
          <span key={s.key} className="inline-flex items-center gap-1.5">
            <span className="size-2.5 rounded-sm" style={{ background: s.color }} />
            {s.label}
          </span>
        ))}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label="Istanti vincenti per giorno" onMouseLeave={() => setHover(null)}>
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} y1={sc.y(t)} x2={W - PAD.right} y2={sc.y(t)} stroke="var(--color-bo-border)" strokeWidth={1} />
            <text x={PAD.left - 6} y={sc.y(t) + 3} textAnchor="end" fontSize={10} className="fill-[var(--color-bo-ink-2)]">
              {t}
            </text>
          </g>
        ))}
        {data.map((d, i) => {
          const cx = PAD.left + slot * i + slot / 2;
          let acc = 0;
          const segments = present.filter((s) => d[s.key] > 0);
          return (
            <g key={d.day} onMouseEnter={() => setHover(i)} opacity={hover === null || hover === i ? 1 : 0.55}>
              <rect x={cx - slot / 2} y={PAD.top} width={slot} height={H - PAD.top - PAD.bottom} fill="transparent" />
              {segments.map((s, k) => {
                const bottom = sc.y(acc) - (k > 0 ? GAP : 0);
                acc += d[s.key];
                const y = sc.y(acc);
                const height = Math.max(0, bottom - y);
                return k === segments.length - 1 ? (
                  <path key={s.key} d={topRounded(cx - barW / 2, y, barW, height, Math.min(4, barW / 2))} fill={s.color} />
                ) : (
                  <rect key={s.key} x={cx - barW / 2} y={y} width={barW} height={height} fill={s.color} />
                );
              })}
            </g>
          );
        })}
        {labelIdx.map((i) => (
          <text key={i} x={PAD.left + slot * i + slot / 2} y={H - PAD.bottom + 15} textAnchor="middle" fontSize={10} className="fill-[var(--color-bo-ink-2)]">
            {shortDay(data[i].day)}
          </text>
        ))}
      </svg>

      <div className="mt-1 min-h-7">
        {hover != null ? (
          <div className="inline-flex flex-wrap items-center gap-3 rounded-md border border-[var(--color-bo-border)] bg-white px-2.5 py-1 text-xs">
            <span className="font-medium text-[var(--color-bo-ink)]">{fullDay(data[hover].day)}</span>
            <span className="tabular-nums text-[var(--color-bo-ink-2)]">{data[hover].total} istanti</span>
            {present.map((s) => (
              <span key={s.key} className="inline-flex items-center gap-1 tabular-nums text-[var(--color-bo-ink-2)]">
                <span className="size-2 rounded-sm" style={{ background: s.color }} aria-hidden />
                {s.label} {data[hover][s.key]}
              </span>
            ))}
          </div>
        ) : null}
      </div>

      <details className="mt-1">
        <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">Mostra tabella</summary>
        <div className="mt-2 max-h-56 overflow-auto rounded-md border border-[var(--color-bo-border)]">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-[var(--color-bo-bg)] text-left text-[var(--color-bo-ink-2)]">
              <tr>
                <th className="px-3 py-1">Giorno</th>
                <th className="px-3 py-1 text-right">Totale</th>
                {SERIES.map((s) => (
                  <th key={s.key} className="px-3 py-1 text-right">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--color-bo-border)]">
              {data.map((d) => (
                <tr key={d.day}>
                  <td className="whitespace-nowrap px-3 py-1">{fullDay(d.day)}</td>
                  <td className="px-3 py-1 text-right tabular-nums">{d.total}</td>
                  {SERIES.map((s) => (
                    <td key={s.key} className="px-3 py-1 text-right tabular-nums">
                      {d[s.key]}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}

/** Rettangolo con i soli angoli superiori arrotondati (estremità del dato), base piatta sull'asse. */
function topRounded(x: number, y: number, w: number, h: number, r: number): string {
  if (h <= 0) return "";
  const rr = Math.max(0, Math.min(r, h));
  return `M${x},${y + h} V${y + rr} Q${x},${y} ${x + rr},${y} H${x + w - rr} Q${x + w},${y} ${x + w},${y + rr} V${y + h} Z`;
}

function pickLabels(n: number): number[] {
  if (n <= 1) return [0];
  const stride = Math.max(1, Math.round(n / 6));
  const out: number[] = [];
  for (let i = 0; i < n; i += stride) out.push(i);
  if (n - 1 - out[out.length - 1] < stride / 2) out.pop();
  out.push(n - 1);
  return out;
}

function shortDay(iso: string): string {
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(`${iso}T00:00:00Z`));
}

function fullDay(iso: string): string {
  return new Intl.DateTimeFormat("it-IT", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" }).format(
    new Date(`${iso}T00:00:00Z`),
  );
}
