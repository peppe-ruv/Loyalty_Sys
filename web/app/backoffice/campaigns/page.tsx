"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { CampaignSummary } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { PageHeader, StatusPill, CodeText, PointsAmount } from "@/components/bo/primitives";

// BO-05 Campagne (docs/08 §BO-05): elenco con totali; riga → editor. Le campagne di sistema hanno il lucchetto.
const STATUSES = ["", "LIVE", "DRAFT", "IN_REVIEW", "PAUSED", "ENDED", "ARCHIVED"];

export default function CampaignsPage() {
  const router = useRouter();
  const [status, setStatus] = useState("");
  const query = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns", { status });

  const columns: Column<CampaignSummary>[] = [
    {
      key: "name",
      header: "Campagna",
      render: (c) => (
        <span className="flex items-center gap-1.5">
          {c.system ? <span title="Campagna di sistema">🔒</span> : null}
          <span className="font-medium">{c.name}</span>
          <CodeText>{c.code}</CodeText>
        </span>
      ),
    },
    { key: "status", header: "Stato", render: (c) => <StatusPill status={c.status} /> },
    { key: "trigger", header: "Trigger", render: (c) => <span className="text-xs">{c.triggerActionTypes.join(", ")}</span> },
    { key: "matches", header: "Attivazioni", className: "text-right", render: (c) => <span className="tabular-nums">{c.totals.matches}</span> },
    { key: "members", header: "Membri unici", className: "text-right", render: (c) => <span className="tabular-nums">{c.totals.uniqueMembers}</span> },
    { key: "points", header: "Punti erogati", className: "text-right", render: (c) => <PointsAmount value={c.totals.pointsDecided} /> },
    { key: "budget", header: "Budget", render: (c) => <BudgetCell campaign={c} /> },
    { key: "prio", header: "Priorità", className: "text-right", render: (c) => <span className="tabular-nums">{c.priority}</span> },
    { key: "portal", header: "Portale", render: (c) => (c.visibleInPortal ? "✓" : "—") },
  ];

  return (
    <div>
      <PageHeader
        title="Campagne"
        subtitle={query.data ? `${query.data.length} campagne` : "Motore regole"}
        actions={
          <button
            onClick={() => router.push("/backoffice/campaigns/new")}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
          >
            Nuova campagna
          </button>
        }
      />
      <div className="mb-3">
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm">
          {STATUSES.map((s) => (
            <option key={s} value={s}>{s === "" ? "Tutti gli stati" : s}</option>
          ))}
        </select>
      </div>
      <QueryState query={query} service="campaign" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna campagna">
        {(d) => (
          <DataTable columns={columns} rows={d} rowKey={(c) => c.id} onRowClick={(c) => router.push(`/backoffice/campaigns/${c.id}`)} />
        )}
      </QueryState>
    </div>
  );
}

// Barra budget in elenco (F-CMP-10): quota di punti decisi sul tetto globale, se presente.
function BudgetCell({ campaign }: { campaign: CampaignSummary }) {
  const b = campaign.budget;
  if (!b || b.maxPoints == null) {
    return <span className="text-xs text-[var(--color-bo-ink-2)]">—</span>;
  }
  const pct = b.maxPoints > 0 ? Math.min(100, (campaign.totals.pointsDecided / b.maxPoints) * 100) : 0;
  const tone = pct >= 90 ? "var(--color-expire)" : pct >= 70 ? "var(--color-spend)" : "var(--color-bo-accent)";
  return (
    <div className="w-28">
      <div className="mb-0.5 text-[10px] tabular-nums text-[var(--color-bo-ink-2)]">{pct.toFixed(0)}%</div>
      <div className="h-1.5 w-full overflow-hidden rounded-sm bg-[var(--color-bo-bg)]">
        <div className="h-full rounded-sm" style={{ width: `${Math.max(2, pct)}%`, background: tone }} />
      </div>
    </div>
  );
}
