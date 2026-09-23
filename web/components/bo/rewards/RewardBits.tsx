"use client";

import { Bike, Gift, HeartHandshake, Home, Lock, Sparkles, Ticket, type LucideIcon } from "lucide-react";
import type { Reward, RewardBand } from "@/lib/api/types";
import { formatPoints } from "@/lib/format/points";
import { stockPercent, stockState } from "@/lib/reward/stock";

// Pezzi condivisi di BO-10 (docs/08 §BO-10): barra stock con pill ambra/rossa, lucchetto tier, segnaposto immagine.

const CATEGORY_ICON: Record<string, LucideIcon> = {
  home: Home,
  sparkles: Sparkles,
  bike: Bike,
  "heart-handshake": HeartHandshake,
  ticket: Ticket,
};

export function categoryIcon(icon: string | null | undefined): LucideIcon {
  return (icon && CATEGORY_ICON[icon]) || Gift;
}

export function StockPill({ reward }: { reward: Pick<Reward, "stockTotal" | "stockRemaining"> }) {
  const state = stockState(reward.stockTotal, reward.stockRemaining);
  if (state === "SOLD_OUT") {
    return <span className="rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">esaurito</span>;
  }
  if (state === "LOW") {
    return <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">in esaurimento</span>;
  }
  return null;
}

export function StockBar({ reward }: { reward: Pick<Reward, "stockTotal" | "stockRemaining"> }) {
  if (reward.stockTotal == null) {
    return <span className="text-xs text-[var(--color-bo-ink-2)]">Illimitato</span>;
  }
  const state = stockState(reward.stockTotal, reward.stockRemaining);
  const pct = stockPercent(reward.stockTotal, reward.stockRemaining);
  const tone = state === "SOLD_OUT" ? "var(--color-expire)" : state === "LOW" ? "var(--color-spend)" : "var(--color-bo-accent)";
  return (
    <div className="min-w-24">
      <div className="mb-0.5 flex items-center justify-between gap-2 text-[11px] tabular-nums text-[var(--color-bo-ink-2)]">
        <span>
          {reward.stockRemaining ?? 0} / {reward.stockTotal}
        </span>
        <StockPill reward={reward} />
      </div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-sm bg-[var(--color-bo-bg)]"
        role="img"
        aria-label={`Stock residuo ${pct.toFixed(0)}%`}
      >
        {pct > 0 ? <div className="h-full rounded-sm" style={{ width: `${Math.max(2, pct)}%`, background: tone }} /> : null}
      </div>
    </div>
  );
}

export function TierLock({ tiers }: { tiers: string[] }) {
  if (tiers.length === 0) return null;
  return (
    <span className="inline-flex items-center gap-1 text-xs text-[var(--color-bo-ink-2)]" title={`Solo per ${tiers.join(", ")}`}>
      <Lock className="size-3" aria-hidden />
      {tiers.join(" · ")}
    </span>
  );
}

export function RewardImage({ reward, icon }: { reward: Pick<Reward, "imageUrl" | "name">; icon?: string | null }) {
  if (reward.imageUrl) {
    // eslint-disable-next-line @next/next/no-img-element -- immagini da public/demo o URL arbitrari del backoffice
    return <img src={reward.imageUrl} alt="" className="h-28 w-full rounded-t-md object-cover" />;
  }
  const Icon = categoryIcon(icon);
  return (
    <div className="flex h-28 w-full items-center justify-center rounded-t-md bg-[var(--color-bo-bg)]">
      <Icon className="size-8 text-[var(--color-bo-ink-2)]" aria-hidden />
    </div>
  );
}

export const TYPE_LABEL: Record<string, string> = {
  PHYSICAL: "Fisico",
  COUPON: "Coupon",
  DIGITAL: "Digitale",
  DONATION: "Donazione",
  EXPERIENCE: "Esperienza",
};

export const FULFILMENT_LABEL: Record<string, string> = {
  AUTO_COUPON: "Automatica con coupon",
  MANUAL: "Manuale",
  INSTANT: "Immediata",
};

export function BandChip({ band, code }: { band: RewardBand | undefined; code: string }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs font-medium text-[var(--color-bo-ink)]">
      <span className="size-2 rounded-full" style={{ background: band?.color ?? "var(--color-bo-ink-2)" }} aria-hidden />
      {code}
    </span>
  );
}

/** Costo in punti: neutro (non è un movimento, niente segno né colore). */
export function CostCell({ value }: { value: number | undefined }) {
  return value == null ? (
    <span className="text-xs">—</span>
  ) : (
    <span className="tabular-nums text-[var(--color-bo-ink)]">{formatPoints(value)} PTS</span>
  );
}
