"use client";

import { cn } from "@/lib/cn";
import { formatPoints } from "@/lib/format/points";

// Primitive condivise del backoffice (docs/08 §3.6). Piccole, componibili, coerenti coi token del tema.

const STATUS_TONE: Record<string, string> = {
  ACTIVE: "bg-emerald-100 text-emerald-800",
  LIVE: "bg-emerald-100 text-emerald-800",
  ACCEPTED: "bg-emerald-100 text-emerald-800",
  DRAFT: "bg-slate-100 text-slate-700",
  PLANNED: "bg-slate-100 text-slate-700",
  IN_REVIEW: "bg-amber-100 text-amber-800",
  APPROVED: "bg-sky-100 text-sky-800",
  OPEN: "bg-sky-100 text-sky-800",
  CLAIMED: "bg-emerald-100 text-emerald-800",
  DELIVERED: "bg-emerald-100 text-emerald-800",
  PAUSED: "bg-amber-100 text-amber-800",
  DUPLICATE: "bg-amber-100 text-amber-800",
  UNMATCHED: "bg-amber-100 text-amber-800",
  BLOCKED: "bg-red-100 text-red-800",
  REJECTED: "bg-red-100 text-red-800",
  ENDED: "bg-slate-200 text-slate-600",
  ARCHIVED: "bg-slate-200 text-slate-600",
  ANONYMIZED: "bg-slate-100 italic text-slate-500",
  // richieste premio (BO-13)
  PENDING: "bg-amber-100 text-amber-800",
  CONFIRMED: "bg-sky-100 text-sky-800",
  FULFILLED: "bg-emerald-100 text-emerald-800",
  CANCELLED: "bg-slate-200 text-slate-600",
  // coupon (BO-12)
  AVAILABLE: "bg-slate-100 text-slate-700",
  ISSUED: "bg-sky-100 text-sky-800",
  USED: "bg-emerald-100 text-emerald-800",
  EXPIRED: "bg-amber-100 text-amber-800",
  VOID: "bg-red-100 text-red-800",
};

export function StatusPill({ status }: { status: string }) {
  const tone = STATUS_TONE[status] ?? "bg-slate-100 text-slate-700";
  return (
    <span className={cn("inline-flex rounded-full px-2 py-0.5 text-xs font-medium", tone)}>{status}</span>
  );
}

const TIER_COLOR: Record<string, string> = {
  BASE: "bg-slate-100 text-slate-700",
  SILVER: "bg-slate-200 text-slate-700",
  GOLD: "bg-amber-100 text-amber-800",
  PLATINUM: "bg-indigo-100 text-indigo-800",
};

export function TierBadge({ tier }: { tier: string }) {
  return (
    <span className={cn("inline-flex rounded px-1.5 py-0.5 text-xs font-semibold", TIER_COLOR[tier] ?? "bg-slate-100 text-slate-700")}>
      {tier}
    </span>
  );
}

export function PointsAmount({ value, currency }: { value: number; currency?: string }) {
  const sign = value > 0 ? "+" : "";
  const tone = value > 0 ? "text-emerald-700" : value < 0 ? "text-red-700" : "text-slate-600";
  return (
    <span className={cn("font-medium tabular-nums", tone)}>
      {sign}
      {formatPoints(value)}
      {currency ? ` ${currency}` : ""}
    </span>
  );
}

export function CodeText({ children }: { children: React.ReactNode }) {
  return <span className="rounded bg-slate-100 px-1 py-0.5 font-mono text-xs text-slate-700">{children}</span>;
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-8 text-center">
      <p className="text-sm font-medium text-[var(--color-bo-ink)]">{title}</p>
      {hint ? <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{hint}</p> : null}
    </div>
  );
}

export function DegradedBox({ service, onRetry }: { service: string; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800">
      <p className="font-medium">Servizio «{service}» non raggiungibile</p>
      <p className="mt-1 text-xs">
        Probabilmente è addormentato (piano gratuito). Accendi la demo dal Demo Hub, poi riprova.
      </p>
      {onRetry ? (
        <button onClick={onRetry} className="mt-2 rounded border border-amber-300 px-2 py-1 text-xs hover:bg-amber-100">
          Riprova
        </button>
      ) : null}
    </div>
  );
}

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="mb-4 flex items-start justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold text-[var(--color-bo-ink)]">{title}</h1>
        {subtitle ? <p className="mt-0.5 text-sm text-[var(--color-bo-ink-2)]">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}
