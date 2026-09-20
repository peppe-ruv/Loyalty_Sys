"use client";

import { useId, useState } from "react";
import { formatPoints } from "@/lib/format/points";
import { areaPath, linearScale, niceMax, ticks } from "@/lib/charts/scale";

// Area impilata punti emessi / spesi / scaduti (docs/08 §BO-01, riga 2). Serie in ordine fisso, colori
// semantici (--color-earn/spend/expire). Il periodo sintetico (storico dimostrativo) è ombreggiato con
// nota. Hover con crosshair + tooltip; tabella alternativa (RNF-08). Un solo asse dei valori.

export interface AreaRow {
  day: string; // ISO date
  emitted: number;
  spent: number;
  expired: number;
  synthetic: boolean;
}

const SERIES = [
  { key: "emitted", label: "Emessi", colorVar: "var(--color-earn)" },
  { key: "spent", label: "Spesi", colorVar: "var(--color-spend)" },
  { key: "expired", label: "Scaduti", colorVar: "var(--color-expire)" },
] as const;

const W = 720;
const H = 260;
const PAD = { top: 16, right: 16, bottom: 28, left: 52 };

export function PointsAreaChart({ rows }: { rows: AreaRow[] }) {
  const clipId = useId();
  const [hover, setHover] = useState<number | null>(null);

  if (rows.length === 0) {
    return <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun dato nel periodo.</p>;
  }

  const totals = rows.map((r) => r.emitted + r.spent + r.expired);
  const max = niceMax(Math.max(...totals, 1));
  const s = linearScale(rows.length, max, W, H, PAD);

  // Bande cumulate per lo stack (dal basso: emessi, poi spesi, poi scaduti).
  const baseline: Array<[number, number]> = rows.map((_, i) => [s.x(i), s.y(0)]);
  let running = rows.map(() => 0);
  const bands = SERIES.map((serie) => {
    const lower = running.map((v, i) => [s.x(i), s.y(v)] as [number, number]);
    running = running.map((v, i) => v + (rows[i][serie.key] as number));
    const upper = running.map((v, i) => [s.x(i), s.y(v)] as [number, number]);
    return { serie, path: areaPath(upper, lower) };
  });
  void baseline;

  // Confine dello storico sintetico (giorni synthetic=true consecutivi in testa).
  let lastSynthetic = -1;
  rows.forEach((r, i) => {
    if (r.synthetic) lastSynthetic = i;
  });
  const hasSynthetic = lastSynthetic >= 0;
  const synthX = hasSynthetic ? s.x(lastSynthetic) : PAD.left;

  const yTicks = ticks(max, 4);
  const xLabelIdx = pickXLabels(rows.length);

  return (
    <figure className="m-0">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <Legend />
        {hasSynthetic ? (
          <span className="inline-flex items-center gap-1.5 text-[11px] text-[var(--color-bo-ink-2)]">
            <span className="inline-block h-2.5 w-4 rounded-sm bg-[repeating-linear-gradient(45deg,#0000000d,#0000000d_3px,transparent_3px,transparent_6px)] ring-1 ring-[var(--color-bo-border)]" />
            storico dimostrativo
          </span>
        ) : null}
      </div>

      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full"
        role="img"
        aria-label="Punti emessi, spesi e scaduti per giorno"
        onMouseLeave={() => setHover(null)}
      >
        <defs>
          <clipPath id={clipId}>
            <rect x={PAD.left} y={PAD.top} width={W - PAD.left - PAD.right} height={H - PAD.top - PAD.bottom} />
          </clipPath>
          <pattern id={`${clipId}-hatch`} width={6} height={6} patternTransform="rotate(45)" patternUnits="userSpaceOnUse">
            <rect width={6} height={6} fill="transparent" />
            <line x1={0} y1={0} x2={0} y2={6} stroke="#0f1b2d" strokeWidth={1} strokeOpacity={0.05} />
          </pattern>
        </defs>

        {/* Griglia + tacche dell'unico asse (valori). */}
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} y1={s.y(t)} x2={W - PAD.right} y2={s.y(t)} stroke="var(--color-bo-border)" strokeWidth={1} />
            <text x={PAD.left - 6} y={s.y(t) + 3} textAnchor="end" className="fill-[var(--color-bo-ink-2)]" fontSize={10}>
              {formatPoints(t)}
            </text>
          </g>
        ))}

        {/* Ombra dello storico sintetico. */}
        {hasSynthetic ? (
          <g clipPath={`url(#${clipId})`}>
            <rect x={PAD.left} y={PAD.top} width={Math.max(0, synthX - PAD.left)} height={H - PAD.top - PAD.bottom} fill={`url(#${clipId}-hatch)`} />
            <line x1={synthX} y1={PAD.top} x2={synthX} y2={H - PAD.bottom} stroke="var(--color-bo-ink-2)" strokeWidth={1} strokeDasharray="3 3" strokeOpacity={0.5} />
          </g>
        ) : null}

        {/* Aree impilate. */}
        <g clipPath={`url(#${clipId})`}>
          {bands.map(({ serie, path }) => (
            <path key={serie.key} d={path} fill={serie.colorVar} fillOpacity={0.85} stroke={serie.colorVar} strokeWidth={1} />
          ))}
        </g>

        {/* Etichette x sparse. */}
        {xLabelIdx.map((i) => (
          <text key={i} x={s.x(i)} y={H - PAD.bottom + 16} textAnchor="middle" className="fill-[var(--color-bo-ink-2)]" fontSize={10}>
            {shortDay(rows[i].day)}
          </text>
        ))}

        {/* Layer di hover: crosshair + colonne trasparenti come hit target. */}
        {hover != null ? (
          <line x1={s.x(hover)} y1={PAD.top} x2={s.x(hover)} y2={H - PAD.bottom} stroke="var(--color-bo-ink)" strokeWidth={1} strokeOpacity={0.35} />
        ) : null}
        {rows.map((_, i) => (
          <rect
            key={i}
            x={i === 0 ? PAD.left : (s.x(i - 1) + s.x(i)) / 2}
            y={PAD.top}
            width={hitWidth(s, i, rows.length)}
            height={H - PAD.top - PAD.bottom}
            fill="transparent"
            onMouseEnter={() => setHover(i)}
          />
        ))}
      </svg>

      {hover != null ? <Tooltip row={rows[hover]} /> : null}
      <TableFallback rows={rows} />
    </figure>
  );
}

function Legend() {
  return (
    <ul className="flex flex-wrap gap-3">
      {SERIES.map((serie) => (
        <li key={serie.key} className="inline-flex items-center gap-1.5 text-xs text-[var(--color-bo-ink)]">
          <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: serie.colorVar }} />
          {serie.label}
        </li>
      ))}
    </ul>
  );
}

function Tooltip({ row }: { row: AreaRow }) {
  return (
    <div className="mt-2 inline-flex flex-wrap items-center gap-x-4 gap-y-1 rounded-md border border-[var(--color-bo-border)] bg-white px-3 py-1.5 text-xs">
      <span className="font-medium text-[var(--color-bo-ink)]">{fullDay(row.day)}</span>
      {SERIES.map((serie) => (
        <span key={serie.key} className="inline-flex items-center gap-1 tabular-nums text-[var(--color-bo-ink-2)]">
          <span className="inline-block h-2 w-2 rounded-sm" style={{ background: serie.colorVar }} />
          {serie.label} {formatPoints(row[serie.key] as number)}
        </span>
      ))}
      {row.synthetic ? <span className="text-[11px] italic text-[var(--color-bo-ink-2)]">storico</span> : null}
    </div>
  );
}

function TableFallback({ rows }: { rows: AreaRow[] }) {
  return (
    <details className="mt-2">
      <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">
        Mostra tabella
      </summary>
      <div className="mt-2 max-h-56 overflow-auto rounded-md border border-[var(--color-bo-border)]">
        <table className="w-full text-xs">
          <thead className="sticky top-0 bg-[var(--color-bo-bg)] text-left text-[var(--color-bo-ink-2)]">
            <tr>
              <th className="px-3 py-1.5">Giorno</th>
              <th className="px-3 py-1.5 text-right">Emessi</th>
              <th className="px-3 py-1.5 text-right">Spesi</th>
              <th className="px-3 py-1.5 text-right">Scaduti</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--color-bo-border)]">
            {rows.map((r) => (
              <tr key={r.day}>
                <td className="px-3 py-1 whitespace-nowrap">
                  {fullDay(r.day)} {r.synthetic ? <span className="italic text-[var(--color-bo-ink-2)]">(storico)</span> : null}
                </td>
                <td className="px-3 py-1 text-right tabular-nums">{formatPoints(r.emitted)}</td>
                <td className="px-3 py-1 text-right tabular-nums">{formatPoints(r.spent)}</td>
                <td className="px-3 py-1 text-right tabular-nums">{formatPoints(r.expired)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

function hitWidth(s: { x: (i: number) => number }, i: number, n: number): number {
  if (n <= 1) return W - PAD.left - PAD.right;
  const step = (s.x(1) - s.x(0)) || (W - PAD.left - PAD.right);
  return i === 0 || i === n - 1 ? step / 2 + 1 : step;
}

function pickXLabels(n: number): number[] {
  if (n <= 1) return [0];
  const target = 6;
  const stride = Math.max(1, Math.round(n / target));
  const out: number[] = [];
  for (let i = 0; i < n; i += stride) out.push(i);
  if (out[out.length - 1] !== n - 1) out.push(n - 1);
  return out;
}

function shortDay(iso: string): string {
  const d = new Date(iso);
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", timeZone: "Europe/Rome" }).format(d);
}

function fullDay(iso: string): string {
  const d = new Date(iso);
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", year: "numeric", timeZone: "Europe/Rome" }).format(d);
}
