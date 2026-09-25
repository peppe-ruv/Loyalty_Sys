"use client";

import { formatPoints } from "@/lib/format/points";
import { formatRelative } from "@/lib/format/dates";
import { usePending } from "./PendingContext";

// Parti condivise del portale (docs/09 §2): linguaggio "punti"/"punti status", riga "in arrivo…", movimento.

export function PendingBanner() {
  const { pending, labels } = usePending();
  if (!pending) return null;
  return (
    <div className="mb-3 flex items-center gap-2 rounded-xl bg-[var(--color-pt-coin)]/20 px-3 py-2 text-sm text-[var(--color-pt-night)]">
      <span className="h-2 w-2 animate-pulse rounded-full bg-[var(--color-pt-coin)]" />
      {labels[0] ?? "In arrivo…"}
    </div>
  );
}

export function currencyName(currency: string): string {
  return currency === "STS" ? "punti status" : "punti";
}

export function PortalAmount({ value, currency }: { value: number; currency: string }) {
  const sign = value > 0 ? "+" : "";
  const tone = value > 0 ? "text-emerald-600" : value < 0 ? "text-amber-600" : "text-slate-500";
  return (
    <span className={`font-semibold tabular-nums ${tone}`}>
      {sign}
      {formatPoints(value)} {currencyName(currency)}
    </span>
  );
}

export interface ActivityItem {
  id: string;
  occurredAt: string;
  title: string;
  subtitle: string | null;
  amount: number;
  currency: string;
  pending: boolean;
  breakdown: string | null;
  direction?: "+" | "-";
  icon?: string;
  expiresAt?: string | null;
}

export function ActivityRow({ item }: { item: ActivityItem }) {
  return (
    <li className="flex items-start justify-between gap-3 border-b border-[var(--color-bo-border)] py-2.5 last:border-0">
      <div className="min-w-0">
        <p className="truncate text-sm font-medium text-[var(--color-pt-night)]">{item.title}</p>
        {item.subtitle ? <p className="truncate text-xs text-[var(--color-pt-night)]/60">{item.subtitle}</p> : null}
        {item.breakdown ? <p className="mt-0.5 text-xs text-[var(--color-pt-night)]/50">{item.breakdown}</p> : null}
      </div>
      <div className="shrink-0 text-right">
        <PortalAmount value={item.amount} currency={item.currency} />
        <p className="text-xs text-[var(--color-pt-night)]/50">{formatRelative(item.occurredAt)}</p>
      </div>
    </li>
  );
}
