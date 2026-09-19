"use client";

import { formatPoints } from "@/lib/format/points";

// Tessera membro (docs/07 §5.3, docs/09 §3): materiale del tier, nome, numero, saldo punti in evidenza.
const TIER_STYLE: Record<string, { bg: string; label: string }> = {
  BASE: { bg: "from-slate-500 to-slate-700", label: "Base" },
  SILVER: { bg: "from-slate-400 to-slate-600", label: "Silver" },
  GOLD: { bg: "from-amber-400 to-amber-600", label: "Gold" },
  PLATINUM: { bg: "from-indigo-400 to-indigo-700", label: "Platinum" },
};

export function MemberCard({
  programName,
  memberName,
  memberId,
  pts,
  tier,
}: {
  programName: string;
  memberName: string;
  memberId: string;
  pts: number;
  tier: string;
}) {
  const style = TIER_STYLE[tier] ?? TIER_STYLE.BASE;
  return (
    <div className={`relative overflow-hidden rounded-2xl bg-gradient-to-br ${style.bg} p-5 text-white shadow-lg`}>
      <div className="flex items-start justify-between">
        <span className="text-sm font-medium opacity-90">{programName}</span>
        <span className="rounded-full bg-white/20 px-2 py-0.5 text-xs font-semibold">{style.label}</span>
      </div>
      <div className="mt-6">
        <p className="text-xs uppercase tracking-wide opacity-80">Saldo punti</p>
        <p className="text-3xl font-bold tabular-nums">{formatPoints(pts)}</p>
      </div>
      <div className="mt-4 flex items-end justify-between">
        <span className="text-sm">{memberName}</span>
        <span className="font-mono text-xs opacity-80">{memberId}</span>
      </div>
    </div>
  );
}
