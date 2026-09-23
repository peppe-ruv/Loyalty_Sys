"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Lock } from "lucide-react";
import { lhFetch, useLhQuery, type LhError } from "@/lib/api/client";
import type { PortalCoupon, PortalRewardDetail, Redemption, RedemptionAccepted, RewardCategory, WalletView } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/bo/QueryState";
import { RewardArt } from "@/components/portal/RewardArt";
import { CountUp } from "@/components/portal/CountUp";
import { blockReason, isSettled } from "@/lib/reward/portal";
import { formatDate } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";

// PT-04 Dettaglio premio e richiesta (docs/09 §PT-04; F-RWD-05): conferma col saldo prima → dopo (e spedizione per i
// fisici) → 202 → attesa mentre la saga passa dal wallet → esito (codice coupon grande, "ti avviseremo alla
// spedizione", oppure il motivo del rifiuto). Oltre 20 s: rimando a "I miei premi".
const WAIT_MS = 20_000;

type Phase =
  | { kind: "view" }
  | { kind: "confirm" }
  | { kind: "waiting"; redemptionId: string; since: number }
  | { kind: "done"; redemption: Redemption }
  | { kind: "slow" };

export default function PortalRewardPage() {
  const code = String(useParams().code);
  const memberId = useActiveMember();
  const detail = useLhQuery<PortalRewardDetail>("reward", `/v1/portal/rewards/${code}`, { memberId });
  const wallet = useLhQuery<WalletView>("wallet", `/v1/portal/wallets/${memberId}`);
  const categories = useLhQuery<RewardCategory[]>("reward", "/v1/reward-categories");
  const [phase, setPhase] = useState<Phase>({ kind: "view" });
  const balance = wallet.data?.balances.PTS?.active ?? 0;

  return (
    <div className="pb-24">
      <Link href="/portal/rewards" className="mb-2 inline-block text-xs text-[var(--color-pt-night)]/60">
        ← Premi
      </Link>
      <QueryState query={detail} service="reward">
        {(r) => {
          const category = categories.data?.find((c) => c.code === r.category);
          const blocked = blockReason(r, balance);
          return (
            <>
              {phase.kind === "waiting" ? (
                <Waiting phase={phase} isCoupon={r.type === "COUPON"} memberId={memberId} onDone={setPhase} />
              ) : phase.kind === "done" ? (
                <Outcome redemption={phase.redemption} memberId={memberId} balance={balance} />
              ) : phase.kind === "slow" ? (
                <Slow />
              ) : (
                <article className="space-y-4">
                  <RewardArt imageUrl={r.imageUrl} icon={category?.icon} className="h-44 rounded-2xl" muted={r.stockState === "SOLD_OUT"} />
                  <div>
                    <p className="text-xs text-[var(--color-pt-night)]/60">{category?.name ?? "Premio"}</p>
                    <h1 className="text-xl font-semibold leading-tight text-[var(--color-pt-night)]">{r.name}</h1>
                    <p className="mt-1 text-lg font-semibold tabular-nums text-[var(--color-pt-primary)]">{formatPoints(r.pointsCost)} punti</p>
                  </div>
                  {r.description ? <p className="text-sm text-[var(--color-pt-night)]/80">{r.description}</p> : null}
                  <Availability detail={r} />
                  {r.terms ? (
                    <details className="rounded-xl bg-white p-3 text-sm">
                      <summary className="cursor-pointer font-medium text-[var(--color-pt-night)]">Termini e condizioni</summary>
                      <p className="mt-2 text-[var(--color-pt-night)]/70">{r.terms}</p>
                    </details>
                  ) : null}
                </article>
              )}

              {phase.kind === "view" || phase.kind === "confirm" ? (
                <div className="fixed inset-x-0 bottom-14 z-20 mx-auto max-w-md px-4 pb-2">
                  {blocked ? (
                    <div className="flex items-center justify-center gap-2 rounded-2xl bg-white px-4 py-3 text-sm font-medium text-[var(--color-pt-night)]/70 shadow-lg ring-1 ring-black/5">
                      {r.lockedByTier?.requiredTiers.length ? <Lock className="size-4" aria-hidden /> : null}
                      {blocked}
                    </div>
                  ) : (
                    <button
                      onClick={() => setPhase({ kind: "confirm" })}
                      className="w-full rounded-2xl bg-[var(--color-pt-primary)] px-4 py-3 text-base font-semibold text-white shadow-lg"
                    >
                      Richiedi per {formatPoints(r.pointsCost)} punti
                    </button>
                  )}
                </div>
              ) : null}

              {phase.kind === "confirm" ? (
                <ConfirmSheet
                  reward={r}
                  memberId={memberId}
                  balance={balance}
                  onClose={() => setPhase({ kind: "view" })}
                  onAccepted={(a) => setPhase({ kind: "waiting", redemptionId: a.redemptionId, since: Date.now() })}
                />
              ) : null}
            </>
          );
        }}
      </QueryState>
    </div>
  );
}

function Availability({ detail }: { detail: PortalRewardDetail }) {
  const parts: string[] = [];
  if (detail.stockState === "SOLD_OUT") parts.push("Esaurito");
  else if (detail.stockState === "LOW") parts.push(`Ultimi pezzi${detail.stockRemaining != null ? ` (${detail.stockRemaining})` : ""}`);
  else parts.push("Disponibile");
  if (detail.perMemberLimit != null) parts.push(`massimo ${detail.perMemberLimit} per persona`);
  return <p className="text-xs text-[var(--color-pt-night)]/60">{parts.join(" · ")}</p>;
}

function ConfirmSheet({
  reward,
  memberId,
  balance,
  onClose,
  onAccepted,
}: {
  reward: PortalRewardDetail;
  memberId: string;
  balance: number;
  onClose: () => void;
  onAccepted: (a: RedemptionAccepted) => void;
}) {
  const physical = reward.type === "PHYSICAL";
  const [shipping, setShipping] = useState({ name: "", street: "", zip: "", city: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const shippingOk = !physical || Object.values(shipping).every((v) => v.trim() !== "");

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const accepted = await lhFetch<RedemptionAccepted>("reward", "/v1/portal/redemptions", {
        method: "POST",
        body: JSON.stringify({ memberId, rewardCode: reward.code, shipping: physical ? shipping : undefined }),
      });
      onAccepted(accepted);
    } catch (e) {
      setError(messageFor(e as LhError));
    } finally {
      setBusy(false);
    }
  }

  const field = "w-full rounded-xl border border-black/10 bg-white px-3 py-2 text-sm";
  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center">
      <button aria-label="Chiudi" onClick={onClose} className="absolute inset-0 bg-[var(--color-pt-night)]/40" />
      <div role="dialog" aria-modal="true" aria-labelledby="confirm-title" className="relative w-full max-w-md space-y-4 rounded-t-3xl bg-[var(--color-pt-bg)] p-5">
        <h2 id="confirm-title" className="text-lg font-semibold text-[var(--color-pt-night)]">
          Confermi la richiesta?
        </h2>
        <p className="text-sm text-[var(--color-pt-night)]/80">{reward.name}</p>
        <div className="flex items-center justify-between rounded-2xl bg-white p-4 text-sm">
          <div>
            <p className="text-xs text-[var(--color-pt-night)]/60">Saldo ora</p>
            <p className="font-semibold tabular-nums">{formatPoints(balance)}</p>
          </div>
          <span className="text-[var(--color-pt-night)]/40">→</span>
          <div className="text-right">
            <p className="text-xs text-[var(--color-pt-night)]/60">Dopo la richiesta</p>
            <p className="font-semibold tabular-nums text-[var(--color-pt-primary)]">{formatPoints(balance - reward.pointsCost)}</p>
          </div>
        </div>
        {physical ? (
          <fieldset className="space-y-2">
            <legend className="mb-1 text-sm font-medium text-[var(--color-pt-night)]">Dove lo spediamo?</legend>
            <input aria-label="Nome e cognome" placeholder="Nome e cognome" value={shipping.name} onChange={(e) => setShipping({ ...shipping, name: e.target.value })} className={field} />
            <input aria-label="Indirizzo" placeholder="Via e numero civico" value={shipping.street} onChange={(e) => setShipping({ ...shipping, street: e.target.value })} className={field} />
            <div className="grid grid-cols-[110px_1fr] gap-2">
              <input aria-label="CAP" placeholder="CAP" inputMode="numeric" value={shipping.zip} onChange={(e) => setShipping({ ...shipping, zip: e.target.value })} className={field} />
              <input aria-label="Città" placeholder="Città" value={shipping.city} onChange={(e) => setShipping({ ...shipping, city: e.target.value })} className={field} />
            </div>
          </fieldset>
        ) : null}
        {error ? (
          <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">
            {error}
          </p>
        ) : null}
        <div className="flex gap-2">
          <button onClick={onClose} className="flex-1 rounded-2xl bg-white px-4 py-3 text-sm font-medium text-[var(--color-pt-night)] ring-1 ring-black/10">
            Annulla
          </button>
          <button
            onClick={submit}
            disabled={busy || !shippingOk}
            className="flex-[2] rounded-2xl bg-[var(--color-pt-primary)] px-4 py-3 text-sm font-semibold text-white disabled:opacity-50"
          >
            {busy ? "Invio…" : `Conferma · ${formatPoints(reward.pointsCost)} punti`}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Errori immediati (422) in parole da membro. */
function messageFor(e: LhError): string {
  switch (e.code) {
    case "REWARD_SOLD_OUT":
      return "Il premio è appena andato esaurito.";
    case "MEMBER_LIMIT_REACHED":
      return "Hai già richiesto questo premio il numero massimo di volte.";
    case "TIER_NOT_ELIGIBLE":
      return "Questo premio è riservato a un livello più alto.";
    case "SHIPPING_REQUIRED":
      return "Serve l'indirizzo di spedizione.";
    case "MEMBER_NOT_ACTIVE":
      return "Il tuo profilo non è attivo: contatta l'assistenza.";
    case "REWARD_NOT_AVAILABLE":
      return "Il premio non è più disponibile.";
    default:
      return e.detail || "Qualcosa è andato storto: riprova tra poco.";
  }
}

function Waiting({
  phase,
  isCoupon,
  memberId,
  onDone,
}: {
  phase: { kind: "waiting"; redemptionId: string; since: number };
  isCoupon: boolean;
  memberId: string;
  onDone: (p: Phase) => void;
}) {
  const qc = useQueryClient();
  const r = useLhQuery<Redemption>("reward", `/v1/portal/redemptions/${phase.redemptionId}`, { memberId }, { refetchInterval: 1000 });

  useEffect(() => {
    if (r.data && isSettled(r.data, isCoupon)) {
      qc.invalidateQueries({ queryKey: ["wallet"] });
      qc.invalidateQueries({ queryKey: ["reward"] });
      onDone({ kind: "done", redemption: r.data });
    }
  }, [r.data, isCoupon, onDone, qc]);

  useEffect(() => {
    const t = setTimeout(() => onDone({ kind: "slow" }), Math.max(0, phase.since + WAIT_MS - Date.now()));
    return () => clearTimeout(t);
  }, [phase.since, onDone]);

  return (
    <div className="flex flex-col items-center gap-4 py-16 text-center" role="status" aria-live="polite">
      <span className="size-10 animate-spin rounded-full border-4 border-[var(--color-pt-primary)]/20 border-t-[var(--color-pt-primary)]" aria-hidden />
      <p className="text-base font-medium text-[var(--color-pt-night)]">Stiamo confermando la tua richiesta…</p>
      <p className="text-sm text-[var(--color-pt-night)]/60">Il tuo saldo viene aggiornato in tempo reale.</p>
    </div>
  );
}

function Outcome({ redemption: r, memberId, balance }: { redemption: Redemption; memberId: string; balance: number }) {
  const coupons = useLhQuery<PortalCoupon[]>("reward", "/v1/portal/coupons", { memberId }, { enabled: !!r.couponCode });
  const coupon = coupons.data?.find((c) => c.code === r.couponCode);
  const [copied, setCopied] = useState(false);

  if (r.status === "REJECTED" || r.status === "CANCELLED") {
    return (
      <div className="space-y-3 py-10 text-center">
        <p className="text-lg font-semibold text-[var(--color-pt-night)]">
          {r.rejectReason === "INSUFFICIENT_BALANCE" ? "Punti non sufficienti" : "Richiesta non andata a buon fine"}
        </p>
        <p className="text-sm text-[var(--color-pt-night)]/70">Nessun punto è stato scalato dal tuo saldo.</p>
        <Link href="/portal/rewards" className="inline-block rounded-2xl bg-white px-4 py-2 text-sm font-medium ring-1 ring-black/10">
          Torna ai premi
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-5 py-6 text-center">
      <div className="mx-auto flex size-14 items-center justify-center rounded-full bg-[var(--color-pt-primary)] text-white">
        <Check className="size-7" aria-hidden />
      </div>
      <div>
        <p className="text-lg font-semibold text-[var(--color-pt-night)]">Fatto! {r.rewardName} è tuo</p>
        <p className="mt-1 text-sm text-[var(--color-pt-night)]/70">
          Saldo: <strong className="text-[var(--color-pt-night)]"><CountUp value={balance} /></strong> punti
        </p>
      </div>
      {r.couponCode ? (
        <div className="rounded-2xl border-2 border-dashed border-[var(--color-pt-primary)]/40 bg-white p-5">
          <p className="text-xs text-[var(--color-pt-night)]/60">Il tuo codice</p>
          <p className="my-2 break-all font-mono text-2xl font-semibold tracking-wider text-[var(--color-pt-night)]">{r.couponCode}</p>
          <button
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(r.couponCode ?? "");
                setCopied(true);
              } catch {
                setCopied(false);
              }
            }}
            className="inline-flex items-center gap-1.5 rounded-full bg-[var(--color-pt-primary)]/10 px-3 py-1.5 text-sm font-medium text-[var(--color-pt-primary)]"
          >
            <Copy className="size-4" aria-hidden /> {copied ? "Copiato" : "Copia"}
          </button>
          {coupon?.expiresAt ? <p className="mt-3 text-xs text-[var(--color-pt-night)]/60">Valido fino al {formatDate(coupon.expiresAt)}</p> : null}
        </div>
      ) : r.status === "CONFIRMED" && r.needsAttention ? (
        <p className="text-sm text-[var(--color-pt-night)]/80">Richiesta confermata: il codice arriverà a breve in «I miei premi».</p>
      ) : r.status === "CONFIRMED" ? (
        <p className="text-sm text-[var(--color-pt-night)]/80">Richiesta confermata: ti avviseremo alla spedizione.</p>
      ) : null}
      <Link href="/portal/my-rewards" className="inline-block rounded-2xl bg-white px-4 py-2 text-sm font-medium text-[var(--color-pt-primary)] ring-1 ring-black/10">
        Vai a I miei premi
      </Link>
    </div>
  );
}

function Slow() {
  return (
    <div className="space-y-3 py-12 text-center">
      <p className="text-base font-medium text-[var(--color-pt-night)]">Ci stiamo mettendo più del solito</p>
      <p className="text-sm text-[var(--color-pt-night)]/70">Troverai l&apos;esito in «I miei premi»: non serve richiederlo di nuovo.</p>
      <Link href="/portal/my-rewards" className="inline-block rounded-2xl bg-[var(--color-pt-primary)] px-4 py-2 text-sm font-semibold text-white">
        I miei premi
      </Link>
    </div>
  );
}
