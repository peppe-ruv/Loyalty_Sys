"use client";

import { useState } from "react";
import type { LiabilityMonth } from "@/lib/api/types";
import { liabilityBuckets } from "@/lib/charts/liability";
import { niceMax, ticks } from "@/lib/charts/scale";
import { formatPoints } from "@/lib/format/points";

// Passività per mese di scadenza (F-WAL-09; BO-01 riga 3, BO-08 scheda liability). Grandezza su 12 mesi →
// colonne, una sola serie e una sola tinta (teal validato con dataviz: banda di luminosità, croma, contrasto),
// nessuna legenda (il titolo nomina la serie). Colonne sottili con data-end arrotondato ancorate alla base,
// 2px di spazio tra colonne, un solo asse, griglia recessiva, etichetta diretta solo sul massimo,
// tooltip al passaggio e tabella alternativa.
const BAR = "#0d9488";
const W = 560;
const H = 180;
const PAD = { top: 16, right: 8, bottom: 28, left: 44 };

export function LiabilityColumns({
  rows,
  unit,
  from = new Date(),
  neverNote,
}: {
  rows: LiabilityMonth[];
  unit: string;
  from?: Date;
  /** Spiegazione mostrata quando nessun punto scade a lotto (es. STS: azzerati dalla chiusura edizione). */
  neverNote?: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const buckets = liabilityBuckets(rows, from);
  const total = buckets.reduce((s, b) => s + b.amount, 0);

  if (total === 0) {
    return <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun punto in circolazione.</p>;
  }
  const never = buckets.find((b) => b.key === "never")?.amount ?? 0;
  if (never === total) {
    // Nulla scade a lotto: un grafico di 12 mesi vuoti non dice niente, basta una frase.
    return (
      <p className="text-sm text-[var(--color-bo-ink-2)]">
        {formatPoints(never)} {unit} senza scadenza a lotto.{neverNote ? ` ${neverNote}` : ""}
      </p>
    );
  }

  const max = niceMax(Math.max(...buckets.map((b) => b.amount), 1));
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const slot = innerW / buckets.length;
  const barW = Math.max(4, slot - 2); // 2px di spazio tra colonne adiacenti
  const y = (v: number) => PAD.top + innerH * (1 - v / max);
  const peak = buckets.reduce((best, b, i) => (b.amount > buckets[best].amount ? i : best), 0);

  return (
    <figure className="m-0">
      <div className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" role="img" aria-label={`Passività ${unit} per mese di scadenza`}>
          {ticks(max).map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="var(--color-bo-border)" strokeWidth={1} />
              <text x={PAD.left - 6} y={y(t) + 3} textAnchor="end" fontSize={10} fill="var(--color-bo-ink-2)">
                {formatPoints(t)}
              </text>
            </g>
          ))}
          {buckets.map((b, i) => {
            const x = PAD.left + i * slot + (slot - barW) / 2;
            const h = Math.max(0, H - PAD.bottom - y(b.amount));
            const r = Math.min(4, barW / 2, h);
            const top = H - PAD.bottom - h;
            return (
              <g key={b.key} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
                {/* area di hover più grande della colonna */}
                <rect x={PAD.left + i * slot} y={PAD.top} width={slot} height={innerH} fill="transparent" />
                {h > 0 ? (
                  <path
                    d={`M${x},${H - PAD.bottom} V${top + r} Q${x},${top} ${x + r},${top} H${x + barW - r} Q${x + barW},${top} ${x + barW},${top + r} V${H - PAD.bottom} Z`}
                    fill={BAR}
                    opacity={hover !== null && hover !== i ? 0.45 : b.key === "later" || b.key === "never" ? 0.6 : 1}
                  />
                ) : null}
                <text x={x + barW / 2} y={H - PAD.bottom + 14} textAnchor="middle" fontSize={10} fill="var(--color-bo-ink-2)">
                  {b.label}
                </text>
                {i === peak && b.amount > 0 && hover === null ? (
                  <text x={x + barW / 2} y={top - 4} textAnchor="middle" fontSize={10} fontWeight={600} fill="var(--color-bo-ink)">
                    {formatPoints(b.amount)}
                  </text>
                ) : null}
              </g>
            );
          })}
          <line x1={PAD.left} x2={W - PAD.right} y1={H - PAD.bottom} y2={H - PAD.bottom} stroke="var(--color-bo-ink-2)" strokeWidth={1} />
        </svg>
        {hover !== null ? (
          <div
            className="pointer-events-none absolute top-0 rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-xs shadow"
            style={{ left: `${((PAD.left + (hover + 0.5) * slot) / W) * 100}%`, transform: "translateX(-50%)" }}
          >
            <span className="font-medium text-[var(--color-bo-ink)]">{bucketTitle(buckets[hover].key, buckets[hover].label)}</span>
            <span className="ml-2 tabular-nums text-[var(--color-bo-ink)]">
              {formatPoints(buckets[hover].amount)} {unit}
            </span>
          </div>
        ) : null}
      </div>
      <details className="mt-2">
        <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">Mostra tabella</summary>
        <table className="mt-2 w-full text-xs">
          <thead className="text-left text-[var(--color-bo-ink-2)]">
            <tr>
              <th className="py-1">Scadenza</th>
              <th className="py-1 text-right">{unit}</th>
              <th className="py-1 text-right">Quota</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--color-bo-border)]">
            {buckets.map((b) => (
              <tr key={b.key}>
                <td className="py-1">{bucketTitle(b.key, b.label)}</td>
                <td className="py-1 text-right tabular-nums">{formatPoints(b.amount)}</td>
                <td className="py-1 text-right tabular-nums">{((b.amount / total) * 100).toFixed(0)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}

function bucketTitle(key: string, label: string): string {
  if (key === "later") return "Oltre 12 mesi";
  if (key === "never") return "Senza scadenza";
  return label;
}
