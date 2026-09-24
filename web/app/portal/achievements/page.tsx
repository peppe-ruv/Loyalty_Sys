"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Check, Lock } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { PortalAchievement, PortalBadge } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/shared/QueryState";
import { gameIcon, periodPhrase, streakDots } from "@/lib/gamification/achievements";
import { formatDate } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";
import { cn } from "@/lib/cn";

// PT-09 Obiettivi e badge (docs/09 §PT-09): schede obiettivo con barra valore/traguardo, periodo, badge collegato,
// serie a pallini; griglia badge (ottenuti a colori, da ottenere in grigio con come sbloccarli). Un completamento che
// arriva mentre la pagina è aperta fa pulsare la scheda e mostra "Badge sbloccato".
export default function AchievementsPage() {
  const memberId = useActiveMember();
  const achievements = useLhQuery<PortalAchievement[]>("gamification", "/v1/portal/achievements", { memberId }, { refetchInterval: 8_000 });
  const badges = useLhQuery<PortalBadge[]>("gamification", "/v1/portal/badges", { memberId }, { refetchInterval: 8_000 });
  const fresh = useFreshCompletions(achievements.data, memberId);

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Obiettivi e badge</h1>
        <Link href="/portal/profile" className="text-xs font-medium text-[var(--color-pt-primary)]">
          Profilo →
        </Link>
      </div>
      {fresh ? (
        <div role="status" className="rounded-2xl bg-[var(--color-pt-night)] px-4 py-3 text-sm text-white shadow-md">
          🎉 Obiettivo raggiunto: <strong>{fresh.name}</strong>
          {fresh.badge ? ` · Badge sbloccato: ${fresh.badge.name}` : ""}
        </div>
      ) : null}

      <section className="space-y-3">
        <h2 className="text-sm font-semibold text-[var(--color-pt-night)]">Obiettivi</h2>
        <QueryState query={achievements} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessun obiettivo attivo" emptyHint="Presto ne arriveranno di nuovi.">
          {(d) => (
            <ul className="space-y-3">
              {d.map((a) => (
                <AchievementCard key={a.code} a={a} pulse={fresh?.code === a.code} />
              ))}
            </ul>
          )}
        </QueryState>
      </section>

      <section className="space-y-3">
        <h2 className="text-sm font-semibold text-[var(--color-pt-night)]">Badge</h2>
        <QueryState query={badges} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessun badge" emptyHint="I badge arrivano con gli obiettivi.">
          {(d) => (
            <ul className="grid grid-cols-3 gap-3">
              {d.map((b) => (
                <BadgeTile key={b.code} b={b} />
              ))}
            </ul>
          )}
        </QueryState>
      </section>
    </div>
  );
}

/** Il primo obiettivo che risulta completato adesso ma non lo era al caricamento precedente (stesso membro). */
function useFreshCompletions(list: PortalAchievement[] | undefined, memberId: string): PortalAchievement | null {
  const seen = useRef<{ member: string; done: Set<string> } | null>(null);
  const [fresh, setFresh] = useState<PortalAchievement | null>(null);
  useEffect(() => {
    if (!list) return;
    const done = new Set(list.filter((a) => a.completedAt).map((a) => a.code));
    const prev = seen.current;
    if (prev && prev.member === memberId) {
      const next = list.find((a) => a.completedAt && !prev.done.has(a.code));
      if (next) setFresh(next);
    } else {
      setFresh(null);
    }
    seen.current = { member: memberId, done };
  }, [list, memberId]);
  return fresh;
}

function AchievementCard({ a, pulse }: { a: PortalAchievement; pulse: boolean }) {
  const Icon = gameIcon(a.icon);
  const done = a.completedAt != null;
  const phrase = periodPhrase(a.period);
  return (
    <li
      className={cn(
        "rounded-2xl bg-white p-4 shadow-sm ring-1 ring-black/5",
        pulse && "animate-pulse ring-2 ring-[var(--color-pt-primary)]",
      )}
    >
      <div className="flex items-start gap-3">
        <span
          className={cn(
            "flex size-11 shrink-0 items-center justify-center rounded-xl",
            done ? "bg-[var(--color-pt-primary)] text-white" : "bg-[var(--color-pt-bg)] text-[var(--color-pt-night)]",
          )}
        >
          {done ? <Check className="size-5" aria-hidden /> : <Icon className="size-5" aria-hidden />}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <p className="font-medium text-[var(--color-pt-night)]">{a.name}</p>
            {phrase ? <span className="shrink-0 rounded-full bg-[var(--color-pt-bg)] px-2 py-0.5 text-[11px] text-[var(--color-pt-night)]/70">{phrase}</span> : null}
          </div>
          {a.description ? <p className="text-xs text-[var(--color-pt-night)]/60">{a.description}</p> : null}
          {a.metric === "STREAK" ? (
            <div className="mt-2 flex gap-1.5" role="img" aria-label={`${a.value} ${a.streakUnit === "WEEK" ? "settimane" : "giorni"} di fila su ${a.target}`}>
              {streakDots(a.value, a.target).map((on, i) => (
                <span key={i} className={cn("size-3 rounded-full", on ? "bg-[var(--color-pt-coin)]" : "bg-slate-200")} />
              ))}
            </div>
          ) : (
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100" role="img" aria-label={`${a.value} su ${a.target}`}>
              <div className="h-full rounded-full bg-[var(--color-pt-primary)]" style={{ width: `${a.pct}%` }} />
            </div>
          )}
          <div className="mt-1.5 flex items-center justify-between gap-2 text-xs text-[var(--color-pt-night)]/70">
            <span className="tabular-nums">
              {done ? `Completato il ${formatDate(a.completedAt)}` : `${formatPoints(a.value)} / ${formatPoints(a.target)}${a.metric === "SUM" ? " €" : ""}`}
            </span>
            {a.badge ? <span className="font-medium">🏅 {a.badge.name}</span> : null}
          </div>
        </div>
      </div>
    </li>
  );
}

function BadgeTile({ b }: { b: PortalBadge }) {
  const Icon = gameIcon(b.icon);
  const owned = b.awardedAt != null;
  return (
    <li className="flex flex-col items-center gap-1.5 rounded-2xl bg-white p-3 text-center shadow-sm ring-1 ring-black/5">
      <span
        className={cn("relative flex size-14 items-center justify-center rounded-full", !owned && "grayscale")}
        style={{ background: owned ? (b.color ?? "#1fb98f") : "#cbd5e1" }}
      >
        <Icon className="size-7 text-white" aria-hidden />
        {!owned ? (
          <span className="absolute -bottom-1 -right-1 flex size-5 items-center justify-center rounded-full bg-white ring-1 ring-black/10">
            <Lock className="size-3 text-slate-500" aria-hidden />
          </span>
        ) : null}
      </span>
      <p className={cn("text-xs font-medium", owned ? "text-[var(--color-pt-night)]" : "text-[var(--color-pt-night)]/50")}>{b.name}</p>
      <p className="text-[10px] leading-tight text-[var(--color-pt-night)]/50">{owned ? formatDate(b.awardedAt) : b.unlockHint}</p>
    </li>
  );
}
