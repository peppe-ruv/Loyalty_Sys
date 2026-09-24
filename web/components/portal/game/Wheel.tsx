"use client";

import type { WheelSegment } from "@/lib/gamification/play";

// Ruota della fortuna (docs/09 §PT-06, meccanica WHEEL): uno spicchio per premio alternato a "Ritenta". La rotazione
// finale la calcola `targetRotation` dall'esito del server; qui solo la resa, 4 s con decelerazione morbida.
const R = 150;
const C = 160;

export function Wheel({
  segments,
  rotation,
  spinning,
  onStopped,
}: {
  segments: WheelSegment[];
  rotation: number;
  spinning: boolean;
  onStopped: () => void;
}) {
  const n = Math.max(1, segments.length);
  const slice = 360 / n;
  return (
    <div className="relative mx-auto aspect-square w-full max-w-[320px]">
      <svg viewBox="0 0 320 320" className="h-full w-full drop-shadow-md" aria-hidden>
        <circle cx={C} cy={C} r={R + 8} fill="var(--color-pt-night)" />
        <g
          style={{
            transform: `rotate(${rotation}deg)`,
            transformOrigin: `${C}px ${C}px`,
            transition: spinning ? "transform 4s cubic-bezier(0.12, 0.7, 0.1, 1)" : "none",
          }}
          onTransitionEnd={() => spinning && onStopped()}
        >
          {segments.map((s, i) => {
            const a0 = (i * slice - 90) * (Math.PI / 180);
            const a1 = ((i + 1) * slice - 90) * (Math.PI / 180);
            const mid = i * slice + slice / 2;
            const large = slice > 180 ? 1 : 0;
            const d = `M${C},${C} L${C + R * Math.cos(a0)},${C + R * Math.sin(a0)} A${R},${R} 0 ${large} 1 ${C + R * Math.cos(a1)},${C + R * Math.sin(a1)} Z`;
            const dark = s.prizeCode != null;
            return (
              <g key={s.key}>
                <path d={d} fill={s.color} stroke="#fff" strokeWidth={2} />
                <text
                  x={C}
                  y={C - R * 0.62}
                  transform={`rotate(${mid} ${C} ${C})`}
                  textAnchor="middle"
                  dominantBaseline="middle"
                  fontSize={s.label.length > 12 ? 10 : 12}
                  fontWeight={600}
                  fill={dark ? "#ffffff" : "var(--color-pt-night)"}
                >
                  {s.label.length > 16 ? s.label.slice(0, 15) + "…" : s.label}
                </text>
              </g>
            );
          })}
        </g>
        <circle cx={C} cy={C} r={22} fill="#fff" stroke="var(--color-pt-night)" strokeWidth={4} />
        <circle cx={C} cy={C} r={6} fill="var(--color-pt-coin)" />
        {/* indicatore in alto */}
        <path d={`M${C - 14},4 L${C + 14},4 L${C},34 Z`} fill="var(--color-pt-coin)" stroke="var(--color-pt-night)" strokeWidth={3} />
      </svg>
    </div>
  );
}
