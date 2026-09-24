"use client";

import { useState } from "react";
import Link from "next/link";
import { Crown } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { PortalLeaderboard } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/bo/QueryState";
import { myPositionLine, periodKeyLabel, scoreUnit } from "@/lib/gamification/leaderboards";
import { formatPoints } from "@/lib/format/points";
import { cn } from "@/lib/cn";

// PT-10 Classifica (docs/09 §PT-10): selettore (mese / edizione), podio dei primi 3, top N con nickname e punteggio,
// riga del membro sempre visibile (fissata in basso se è fuori dalla top N). Solo nickname, mai nomi reali.
export default function LeaderboardPage() {
  const memberId = useActiveMember();
  const list = useLhQuery<PortalLeaderboard[]>("gamification", "/v1/portal/leaderboards", { memberId }, { refetchInterval: 15_000 });
  const [code, setCode] = useState<string | null>(null);
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Classifica</h1>
        <Link href="/portal/play" className="text-xs font-medium text-[var(--color-pt-primary)]">
          ← Gioca
        </Link>
      </div>
      <QueryState query={list} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna classifica attiva" emptyHint="Torna presto: le classifiche ripartono ogni mese.">
        {(d) => {
          const board = d.find((l) => l.code === code) ?? d[0];
          return (
            <>
              <div className="flex gap-2 overflow-x-auto" role="tablist">
                {d.map((l) => (
                  <button
                    key={l.code}
                    role="tab"
                    aria-selected={l.code === board.code}
                    onClick={() => setCode(l.code)}
                    className={cn(
                      "shrink-0 rounded-full px-3 py-1.5 text-sm font-medium",
                      l.code === board.code ? "bg-[var(--color-pt-night)] text-white" : "bg-white text-[var(--color-pt-night)] ring-1 ring-black/5",
                    )}
                  >
                    {l.name}
                  </button>
                ))}
              </div>
              <Board board={board} />
            </>
          );
        }}
      </QueryState>
    </div>
  );
}

function Board({ board: b }: { board: PortalLeaderboard }) {
  const unit = scoreUnit(b.metric);
  const podium = b.top.slice(0, 3);
  const rest = b.top.slice(3);
  const meOutside = b.me != null && b.me.rank > b.top.length;
  if (b.top.length === 0) {
    return <p className="rounded-2xl bg-white p-5 text-center text-sm text-[var(--color-pt-night)]/70 shadow-sm ring-1 ring-black/5">Ancora nessun punteggio: il primo posto è libero!</p>;
  }
  // Podio: 2° a sinistra, 1° al centro più alto, 3° a destra.
  const order = [podium[1], podium[0], podium[2]].filter(Boolean);
  return (
    <div className="space-y-4 pb-16">
      <p className="text-xs text-[var(--color-pt-night)]/60">
        {periodKeyLabel(b.periodKey)} · {b.participants} {b.participants === 1 ? "partecipante" : "partecipanti"}
      </p>
      <ol className="flex items-end justify-center gap-3" aria-label="Podio">
        {order.map((e) => {
          const height = e.rank === 1 ? "h-28" : e.rank === 2 ? "h-20" : "h-16";
          return (
            <li key={e.rank} className="flex w-24 flex-col items-center gap-1 text-center">
              {e.rank === 1 ? <Crown className="size-5 text-[var(--color-pt-coin)]" aria-hidden /> : null}
              <span className={cn("max-w-full truncate text-xs font-semibold", e.isMe ? "text-[var(--color-pt-primary)]" : "text-[var(--color-pt-night)]")}>
                {e.nickname}
                {e.isMe ? " (tu)" : ""}
              </span>
              <span className="text-[11px] tabular-nums text-[var(--color-pt-night)]/60">{formatPoints(e.score)}</span>
              <span
                className={cn(
                  "flex w-full items-start justify-center rounded-t-xl pt-2 text-lg font-bold text-white",
                  height,
                  e.rank === 1 ? "bg-[var(--color-pt-coin)]" : e.rank === 2 ? "bg-[var(--color-pt-secondary)]" : "bg-[var(--color-pt-primary)]",
                )}
              >
                {e.rank}
              </span>
            </li>
          );
        })}
      </ol>
      {rest.length ? (
        <ol className="divide-y divide-black/5 rounded-2xl bg-white shadow-sm ring-1 ring-black/5" start={4}>
          {rest.map((e) => (
            <Row key={e.rank} rank={e.rank} nickname={e.nickname} score={e.score} unit={unit} me={e.isMe} />
          ))}
        </ol>
      ) : null}
      <div className={cn(meOutside && "fixed inset-x-0 bottom-16 z-10 mx-auto max-w-md px-4")}>
        <div className="rounded-2xl bg-[var(--color-pt-night)] px-4 py-3 text-sm text-white shadow-md">
          <span className="font-semibold">{myPositionLine(b.me)}</span>
          {b.me ? (
            <span className="text-white/70">
              {" "}
              · {formatPoints(b.me.score)} {unit}
            </span>
          ) : (
            <span className="text-white/70"> · guadagna {unit} per entrarci</span>
          )}
        </div>
      </div>
    </div>
  );
}

function Row({ rank, nickname, score, unit, me }: { rank: number; nickname: string; score: number; unit: string; me: boolean }) {
  return (
    <li className={cn("flex items-center justify-between gap-3 px-4 py-2.5 text-sm", me && "bg-[var(--color-pt-primary)]/10")}>
      <span className="flex items-center gap-3">
        <span className="w-6 text-right tabular-nums text-[var(--color-pt-night)]/50">{rank}</span>
        <span className={cn("font-medium", me ? "text-[var(--color-pt-primary)]" : "text-[var(--color-pt-night)]")}>
          {nickname}
          {me ? " (tu)" : ""}
        </span>
      </span>
      <span className="tabular-nums text-[var(--color-pt-night)]/70">
        {formatPoints(score)} <span className="text-xs">{unit}</span>
      </span>
    </li>
  );
}
