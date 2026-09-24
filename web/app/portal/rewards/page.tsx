"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { Check, Lock } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { PortalCatalog, PortalReward, RewardCategory, WalletView } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/bo/QueryState";
import { RewardArt } from "@/components/portal/RewardArt";
import { ContentSlot } from "@/components/portal/ContentSlot";
import { bandProgress, blockReason, rewardBadge } from "@/lib/reward/portal";
import { formatPoints } from "@/lib/format/points";
import { cn } from "@/lib/cn";

// PT-03 Catalogo premi (docs/09 §PT-03; F-RWD-01…04): fasce come sezioni in ordine di soglia, con lo stato rispetto
// al saldo (raggiunta ✓ oppure "ti mancano N punti"); griglia 2 colonne; filtro per categoria; "solo richiedibili".
// In testa il banner CATALOG_TOP (engagement, M6.1).
export default function PortalRewardsPage() {
  const memberId = useActiveMember();
  const catalog = useLhQuery<PortalCatalog>("reward", "/v1/portal/catalog", { memberId });
  const wallet = useLhQuery<WalletView>("wallet", `/v1/portal/wallets/${memberId}`);
  const categories = useLhQuery<RewardCategory[]>("reward", "/v1/reward-categories");
  const [category, setCategory] = useState("");
  const [onlyRedeemable, setOnlyRedeemable] = useState(false);

  const balance = wallet.data?.balances.PTS?.active ?? 0;
  const icons = useMemo(() => new Map((categories.data ?? []).map((c) => [c.code, c.icon])), [categories.data]);
  const keep = (r: PortalReward) =>
    (!category || r.category === category) && (!onlyRedeemable || blockReason(r, balance) === null);

  return (
    <div className="space-y-4">
      <div className="sticky top-0 z-10 -mx-4 flex items-center justify-between bg-[var(--color-pt-bg)]/95 px-4 py-2 backdrop-blur">
        <div>
          <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Premi</h1>
          <p className="text-xs text-[var(--color-pt-night)]/60">
            Hai <strong className="tabular-nums text-[var(--color-pt-night)]">{formatPoints(balance)}</strong> punti
          </p>
        </div>
        <Link href="/portal/my-rewards" className="rounded-full bg-white px-3 py-1.5 text-xs font-medium text-[var(--color-pt-primary)] shadow-sm">
          I miei premi
        </Link>
      </div>

      <ContentSlot placement="CATALOG_TOP" />

      <div className="flex flex-wrap items-center gap-2">
        <Chip active={category === ""} onClick={() => setCategory("")}>
          Tutti
        </Chip>
        {(categories.data ?? []).map((c) => (
          <Chip key={c.code} active={category === c.code} onClick={() => setCategory(c.code)}>
            {c.name}
          </Chip>
        ))}
      </div>
      <label className="flex items-center gap-2 text-sm text-[var(--color-pt-night)]">
        <input type="checkbox" checked={onlyRedeemable} onChange={(e) => setOnlyRedeemable(e.target.checked)} className="size-4 accent-[var(--color-pt-primary)]" />
        Solo quelli che posso richiedere
      </label>

      <QueryState
        query={catalog}
        service="reward"
        isEmpty={(d) => d.bands.every((b) => b.rewards.length === 0)}
        emptyTitle="Il catalogo è vuoto"
        emptyHint="Nuovi premi in arrivo: torna a trovarci."
      >
        {(d) => {
          const bands = d.bands.map((b) => ({ ...b, rewards: b.rewards.filter(keep) })).filter((b) => b.rewards.length > 0);
          if (bands.length === 0) {
            return (
              <p className="rounded-xl bg-white p-4 text-center text-sm text-[var(--color-pt-night)]/70">
                Nessun premio con questi filtri. {onlyRedeemable ? "Accumula altri punti o togli il filtro." : ""}
              </p>
            );
          }
          return (
            <div className="space-y-6">
              {bands.map((b) => {
                const p = bandProgress(balance, b.pointsThreshold);
                return (
                  <section key={b.code} aria-labelledby={`band-${b.code}`} className={cn(!p.reached && "opacity-80")}>
                    <div className="mb-2">
                      <div className="flex items-baseline justify-between gap-2">
                        <h2 id={`band-${b.code}`} className="font-semibold text-[var(--color-pt-night)]">
                          Fascia {formatPoints(b.pointsThreshold)} punti
                        </h2>
                        {p.reached ? (
                          <span className="inline-flex items-center gap-1 text-xs font-medium text-[var(--color-pt-primary)]">
                            <Check className="size-3.5" aria-hidden /> raggiunta
                          </span>
                        ) : (
                          <span className="text-xs text-[var(--color-pt-night)]/70">ti mancano {formatPoints(p.missing)} punti</span>
                        )}
                      </div>
                      {!p.reached ? (
                        <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-white" aria-hidden>
                          <div className="h-full rounded-full bg-[var(--color-pt-coin)]" style={{ width: `${p.pct}%` }} />
                        </div>
                      ) : null}
                    </div>
                    <ul className="grid grid-cols-2 gap-3">
                      {b.rewards.map((r) => (
                        <li key={r.code}>
                          <RewardTile reward={r} icon={icons.get(r.category ?? "")} />
                        </li>
                      ))}
                    </ul>
                  </section>
                );
              })}
            </div>
          );
        }}
      </QueryState>
    </div>
  );
}

function RewardTile({ reward, icon }: { reward: PortalReward; icon: string | null | undefined }) {
  const badge = rewardBadge(reward);
  const soldOut = reward.stockState === "SOLD_OUT";
  const locked = !!reward.lockedByTier?.requiredTiers.length;
  return (
    <Link
      href={`/portal/rewards/${reward.code}`}
      className="block overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-black/5 transition hover:ring-[var(--color-pt-primary)]/40"
    >
      <div className="relative">
        <RewardArt imageUrl={reward.imageUrl} icon={icon} className="h-24" muted={soldOut} />
        {badge ? (
          <span
            className={cn(
              "absolute left-2 top-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium",
              soldOut ? "bg-slate-700 text-white" : locked ? "bg-[var(--color-pt-night)] text-white" : "bg-[var(--color-pt-coin)] text-[var(--color-pt-night)]",
            )}
          >
            {locked ? <Lock className="size-3" aria-hidden /> : null}
            {badge}
          </span>
        ) : null}
      </div>
      <div className="p-3">
        <p className={cn("line-clamp-2 text-sm font-medium leading-tight text-[var(--color-pt-night)]", soldOut && "text-[var(--color-pt-night)]/50")}>
          {reward.name}
        </p>
        <p className="mt-1 text-xs font-semibold tabular-nums text-[var(--color-pt-primary)]">{formatPoints(reward.pointsCost)} punti</p>
      </div>
    </Link>
  );
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        "rounded-full px-3 py-1 text-xs font-medium",
        active ? "bg-[var(--color-pt-night)] text-white" : "bg-white text-[var(--color-pt-night)] ring-1 ring-black/10",
      )}
    >
      {children}
    </button>
  );
}
