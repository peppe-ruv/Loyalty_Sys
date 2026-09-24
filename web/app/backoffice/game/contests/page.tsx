"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { Contest } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { MECHANIC_LABEL, formatRate, remainingPct } from "@/lib/gamification/contests";
import { formatDate } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";

// BO-14 Concorsi instant win — elenco (docs/08 §BO-14): nome, meccanica, periodo, stato, giocate, vincite, premi residui.
const STATUSES = ["", "LIVE", "DRAFT", "IN_REVIEW", "APPROVED", "PAUSED", "ENDED", "ARCHIVED"];

export default function ContestsPage() {
  const router = useRouter();
  const [status, setStatus] = useState("");
  const query = useLhQuery<Contest[]>("gamification", "/v1/contests", { status }, { refetchInterval: 15_000 });

  const columns: Column<Contest>[] = [
    {
      key: "name",
      header: "Concorso",
      render: (c) => (
        <span className="flex flex-col">
          <span className="font-medium">{c.name}</span>
          <CodeText>{c.code}</CodeText>
        </span>
      ),
    },
    { key: "mechanic", header: "Meccanica", render: (c) => <span className="text-xs">{MECHANIC_LABEL[c.mechanic] ?? c.mechanic}</span> },
    {
      key: "period",
      header: "Periodo",
      render: (c) => (
        <span className="whitespace-nowrap text-xs tabular-nums">
          {formatDate(c.startAt)} → {formatDate(c.endAt)}
        </span>
      ),
    },
    { key: "status", header: "Stato", render: (c) => <StatusPill status={c.status} /> },
    { key: "plays", header: "Giocate", className: "text-right", render: (c) => <span className="tabular-nums">{formatPoints(c.plays)}</span> },
    {
      key: "wins",
      header: "Vincite",
      className: "text-right",
      render: (c) => (
        <span className="tabular-nums">
          {formatPoints(c.wins)} <span className="text-xs text-[var(--color-bo-ink-2)]">({formatRate(c.wins, c.plays)})</span>
        </span>
      ),
    },
    {
      key: "remaining",
      header: "Premi residui",
      render: (c) => {
        const pct = remainingPct(c);
        return (
          <div className="min-w-32">
            <div className="mb-0.5 text-[11px] tabular-nums text-[var(--color-bo-ink-2)]">
              {formatPoints(c.prizesRemaining)} / {formatPoints(c.prizesTotal)}
            </div>
            <div className="h-1.5 overflow-hidden rounded-sm bg-[var(--color-bo-bg)]" role="img" aria-label={`Premi residui ${pct}%`}>
              {pct > 0 ? <div className="h-full rounded-sm bg-[var(--color-bo-accent)]" style={{ width: `${Math.max(2, pct)}%` }} /> : null}
            </div>
          </div>
        );
      },
    },
  ];

  const live = (query.data ?? []).filter((c) => c.status === "LIVE").length;

  return (
    <div>
      <PageHeader
        title="Concorsi instant win"
        subtitle={query.data ? `${live} concorsi LIVE · istanti vincenti estratti prima dell'avvio con un seme riproducibile` : "Istanti vincenti estratti prima dell'avvio"}
        actions={
          <Can capability="object.edit" mode="disable">
            <button
              onClick={() => router.push("/backoffice/game/contests/new")}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              Nuovo concorso
            </button>
          </Can>
        }
      />
      <div className="mb-3">
        <label className="inline-flex items-center gap-2 text-sm">
          <span className="text-xs text-[var(--color-bo-ink-2)]">Stato</span>
          <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm">
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s || "Tutti gli stati"}
              </option>
            ))}
          </select>
        </label>
      </div>
      <QueryState
        query={query}
        service="gamification"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessun concorso"
        emptyHint="Nessun concorso con questo stato. Cambia filtro o crea un concorso."
      >
        {(d) => <DataTable columns={columns} rows={d} rowKey={(c) => c.id} onRowClick={(c) => router.push(`/backoffice/game/contests/${c.id}`)} />}
      </QueryState>
    </div>
  );
}
