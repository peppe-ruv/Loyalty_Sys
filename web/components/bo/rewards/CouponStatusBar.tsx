"use client";

import { useState } from "react";
import type { CouponStatus } from "@/lib/api/types";
import { COUPON_STATUS_COLOR, COUPON_STATUS_LABEL, couponSegments } from "@/lib/reward/coupons";
import { formatPoints } from "@/lib/format/points";

// Codici del pool per stato (BO-12): barra segmentata con 2px di superficie tra i segmenti, estremi arrotondati,
// tooltip al passaggio e legenda sempre visibile coi conteggi (i colori a basso contrasto hanno così un'etichetta).
export function CouponStatusBar({ counts, compact = false }: { counts: Partial<Record<CouponStatus, number>>; compact?: boolean }) {
  const [hover, setHover] = useState<CouponStatus | null>(null);
  const segments = couponSegments(counts);
  const total = segments.reduce((s, x) => s + x.count, 0);

  if (total === 0) {
    return <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun codice: generane o importane.</p>;
  }

  return (
    <div>
      <div
        className="relative flex h-2.5 w-full gap-[2px] overflow-hidden rounded-full bg-[var(--color-bo-surface)]"
        role="img"
        aria-label={segments.map((s) => `${COUPON_STATUS_LABEL[s.status]} ${s.count}`).join(", ")}
      >
        {segments.map((s) => (
          <div
            key={s.status}
            onMouseEnter={() => setHover(s.status)}
            onMouseLeave={() => setHover(null)}
            className="h-full first:rounded-l-full last:rounded-r-full"
            style={{
              width: `${s.pct}%`,
              minWidth: 3,
              background: COUPON_STATUS_COLOR[s.status],
              opacity: hover && hover !== s.status ? 0.45 : 1,
            }}
          />
        ))}
      </div>
      <ul className={`mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs text-[var(--color-bo-ink-2)] ${compact ? "" : "sm:gap-x-4"}`}>
        {segments.map((s) => (
          <li
            key={s.status}
            className={`inline-flex items-center gap-1 ${hover === s.status ? "text-[var(--color-bo-ink)]" : ""}`}
            onMouseEnter={() => setHover(s.status)}
            onMouseLeave={() => setHover(null)}
          >
            <span className="size-2 rounded-full" style={{ background: COUPON_STATUS_COLOR[s.status] }} aria-hidden />
            {COUPON_STATUS_LABEL[s.status]}
            <span className="tabular-nums text-[var(--color-bo-ink)]">{formatPoints(s.count)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
