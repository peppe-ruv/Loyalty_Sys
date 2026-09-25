"use client";

import { useState } from "react";
import Link from "next/link";
import { Copy } from "lucide-react";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { PortalCoupon, Redemption } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/shared/QueryState";
import { COUPON_WORDS, redemptionWords, sortCoupons } from "@/lib/reward/portal";
import { formatDate } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";
import { cn } from "@/lib/cn";

// PT-13 I miei premi e coupon (docs/09 §PT-13): scheda Richieste (stato in parole, avanzamento per i fisici,
// Annulla solo se ancora in conferma) e scheda Coupon (biglietti con codice, copia, scadenza, origine; non attivi in
// fondo, attenuati).
type Tab = "requests" | "coupons";

export default function MyRewardsPage() {
  const memberId = useActiveMember();
  const [tab, setTab] = useState<Tab>("requests");

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">I miei premi</h1>
        <Link href="/portal/rewards" className="text-xs font-medium text-[var(--color-pt-primary)]">
          Catalogo →
        </Link>
      </div>
      <div className="grid grid-cols-2 rounded-full bg-white p-1 text-sm ring-1 ring-black/5" role="tablist">
        {(["requests", "coupons"] as Tab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn("rounded-full py-1.5 font-medium", tab === t ? "bg-[var(--color-pt-night)] text-white" : "text-[var(--color-pt-night)]/70")}
          >
            {t === "requests" ? "Richieste" : "Coupon"}
          </button>
        ))}
      </div>
      {tab === "requests" ? <Requests memberId={memberId} /> : <Coupons memberId={memberId} />}
    </div>
  );
}

function Requests({ memberId }: { memberId: string }) {
  const list = useLhQuery<Redemption[]>("reward", "/v1/portal/redemptions", { memberId }, { refetchInterval: 10_000 });
  return (
    <QueryState
      query={list}
      service="reward"
      isEmpty={(d) => d.length === 0}
      emptyTitle="Nessuna richiesta ancora"
      emptyHint="Scegli un premio dal catalogo: lo trovi qui con il suo stato."
    >
      {(d) => (
        <ul className="space-y-3">
          {d.map((r) => (
            <RequestCard key={r.id} redemption={r} memberId={memberId} />
          ))}
        </ul>
      )}
    </QueryState>
  );
}

const STEPS = ["Richiesta", "Confermata", "Spedita"];

function RequestCard({ redemption: r, memberId }: { redemption: Redemption; memberId: string }) {
  const [error, setError] = useState<LhError | null>(null);
  const cancel = useLhMutation<Redemption, undefined>("reward", "POST", () => `/v1/portal/redemptions/${r.id}/cancel?memberId=${memberId}`);
  const physical = !!r.shipping;
  const step = r.status === "FULFILLED" ? 2 : r.status === "CONFIRMED" ? 1 : 0;
  const closedBadly = r.status === "REJECTED" || r.status === "CANCELLED";
  return (
    <li className={cn("rounded-2xl bg-white p-4 shadow-sm ring-1 ring-black/5", closedBadly && "opacity-70")}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium text-[var(--color-pt-night)]">{r.rewardName}</p>
          <p className="text-xs text-[var(--color-pt-night)]/60">
            {formatDate(r.requestedAt)} · {formatPoints(r.pointsCost)} punti
          </p>
        </div>
        <span
          className={cn(
            "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium",
            r.status === "FULFILLED" && "bg-[var(--color-pt-primary)]/15 text-[var(--color-pt-night)]",
            (r.status === "PENDING" || r.status === "CONFIRMED") && "bg-[var(--color-pt-coin)]/25 text-[var(--color-pt-night)]",
            closedBadly && "bg-slate-100 text-slate-600",
          )}
        >
          {redemptionWords(r)}
        </span>
      </div>
      {physical && !closedBadly ? (
        <ol className="mt-3 flex items-center gap-1" aria-label="Avanzamento della spedizione">
          {STEPS.map((s, i) => (
            <li key={s} className="flex flex-1 flex-col gap-1">
              <span className={cn("h-1.5 rounded-full", i <= step ? "bg-[var(--color-pt-primary)]" : "bg-slate-200")} aria-hidden />
              <span className={cn("text-[11px]", i <= step ? "text-[var(--color-pt-night)]" : "text-[var(--color-pt-night)]/40")}>{s}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {r.couponCode ? <p className="mt-2 font-mono text-sm text-[var(--color-pt-night)]">{r.couponCode}</p> : null}
      {r.status === "PENDING" ? (
        <button
          onClick={() => {
            setError(null);
            cancel.mutate(undefined, { onError: setError });
          }}
          disabled={cancel.isPending}
          className="mt-3 text-sm font-medium text-red-700 disabled:opacity-50"
        >
          Annulla richiesta
        </button>
      ) : null}
      {error ? <p role="alert" className="mt-1 text-xs text-red-700">{error.detail || error.code}</p> : null}
    </li>
  );
}

function Coupons({ memberId }: { memberId: string }) {
  const list = useLhQuery<PortalCoupon[]>("reward", "/v1/portal/coupons", { memberId });
  return (
    <QueryState
      query={list}
      service="reward"
      isEmpty={(d) => d.length === 0}
      emptyTitle="Nessun coupon"
      emptyHint="I buoni che richiedi o vinci compaiono qui, pronti da usare."
    >
      {(d) => (
        <ul className="space-y-3">
          {sortCoupons(d).map((c) => (
            <CouponTicket key={c.code} coupon={c} />
          ))}
        </ul>
      )}
    </QueryState>
  );
}

function CouponTicket({ coupon: c }: { coupon: PortalCoupon }) {
  const [copied, setCopied] = useState(false);
  const active = c.status === "ISSUED";
  const soon = active && c.expiresAt != null && new Date(c.expiresAt).getTime() - Date.now() < 14 * 864e5;
  return (
    <li
      className={cn(
        "relative overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-black/5",
        !active && "opacity-60",
      )}
    >
      <div className="flex">
        <div className={cn("w-2 shrink-0", active ? "bg-[var(--color-pt-primary)]" : "bg-slate-300")} aria-hidden />
        <div className="min-w-0 flex-1 p-4">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="font-medium text-[var(--color-pt-night)]">{c.rewardName ?? "Coupon"}</p>
              <p className="text-xs text-[var(--color-pt-night)]/60">{c.origin === "CAMPAIGN" ? "Vinto con una promozione" : "Da premio"}</p>
            </div>
            <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", active ? "bg-[var(--color-pt-primary)]/15" : "bg-slate-100 text-slate-600")}>
              {COUPON_WORDS[c.status]}
            </span>
          </div>
          <div className="mt-3 flex items-center justify-between gap-3 border-t border-dashed border-black/10 pt-3">
            <p className="break-all font-mono text-lg font-semibold tracking-wider text-[var(--color-pt-night)]">{c.code}</p>
            {active ? (
              <button
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(c.code);
                    setCopied(true);
                  } catch {
                    setCopied(false);
                  }
                }}
                aria-label={`Copia il codice ${c.code}`}
                className="inline-flex shrink-0 items-center gap-1 rounded-full bg-[var(--color-pt-primary)]/10 px-2.5 py-1 text-xs font-medium text-[var(--color-pt-primary)]"
              >
                <Copy className="size-3.5" aria-hidden /> {copied ? "Copiato" : "Copia"}
              </button>
            ) : null}
          </div>
          {c.expiresAt ? (
            <p className={cn("mt-1 text-xs", soon ? "font-medium text-amber-700" : "text-[var(--color-pt-night)]/60")}>
              {active ? `Valido fino al ${formatDate(c.expiresAt)}` : c.status === "EXPIRED" ? `Scaduto il ${formatDate(c.expiresAt)}` : `Scadenza ${formatDate(c.expiresAt)}`}
            </p>
          ) : null}
        </div>
      </div>
    </li>
  );
}
