"use client";

import { useParams, useSearchParams } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { MemberView, WalletView, LedgerEntry, EvaluationRow } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader, StatusPill, TierBadge, CodeText, PointsAmount } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";

// BO-03 Scheda 360° (docs/08 §BO-03). M1: schede overview / ledger / actions; ogni pannello degrada da solo.
const TABS = [
  { key: "overview", label: "Panoramica" },
  { key: "ledger", label: "Movimenti" },
  { key: "actions", label: "Azioni" },
];

export default function MemberDetailPage() {
  const id = String(useParams().id);
  const tab = useSearchParams().get("tab") ?? "overview";
  const member = useLhQuery<MemberView>("member", `/v1/members/${id}`);

  return (
    <div>
      <QueryState query={member} service="member">
        {(m) => (
          <PageHeader
            title={m.firstName ? `${m.firstName} ${m.lastName ?? ""}` : (m.nickname ?? "Membro")}
            subtitle={undefined}
            actions={
              <div className="flex items-center gap-2">
                <StatusPill status={m.status} />
                <TierBadge tier={m.tier} />
                <CodeText>{m.id}</CodeText>
              </div>
            }
          />
        )}
      </QueryState>

      <Tabs tabs={TABS} current={tab} />

      {tab === "overview" && <OverviewTab id={id} />}
      {tab === "ledger" && <LedgerTab id={id} />}
      {tab === "actions" && <ActionsTab id={id} />}
    </div>
  );
}

function OverviewTab({ id }: { id: string }) {
  const member = useLhQuery<MemberView>("member", `/v1/members/${id}`);
  const wallet = useLhQuery<WalletView>("wallet", `/v1/wallets/${id}`);
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card>
        <CardBody className="pt-4">
          <h2 className="mb-2 text-sm font-semibold">Profilo</h2>
          <QueryState query={member} service="member">
            {(m) => (
              <dl className="space-y-1 text-sm">
                <Row label="E-mail" value={m.email ?? "—"} />
                <Row label="External ID" value={m.externalId ?? "—"} />
                <Row label="Codice amico" value={m.email ? undefined : "—"} />
                <Row label="Iscritto il" value={m.registeredAt ? formatDateTime(m.registeredAt) : "—"} />
                <Row label="Versione" value={String(m.version)} />
              </dl>
            )}
          </QueryState>
        </CardBody>
      </Card>
      <Card>
        <CardBody className="pt-4">
          <h2 className="mb-2 text-sm font-semibold">Saldo e livello</h2>
          <QueryState query={wallet} service="wallet">
            {(w) => (
              <div className="space-y-2 text-sm">
                <div className="flex items-baseline justify-between">
                  <span className="text-[var(--color-bo-ink-2)]">Punti (PTS)</span>
                  <span className="text-lg font-semibold tabular-nums">{formatPoints(w.balances.PTS?.active ?? 0)}</span>
                </div>
                <div className="flex items-baseline justify-between">
                  <span className="text-[var(--color-bo-ink-2)]">Status (STS)</span>
                  <span className="tabular-nums">{formatPoints(w.balances.STS?.active ?? 0)}</span>
                </div>
                <div className="pt-2">
                  <div className="mb-1 flex items-center justify-between text-xs">
                    <TierBadge tier={w.tier.code} />
                    {w.tier.next ? (
                      <span className="text-[var(--color-bo-ink-2)]">
                        mancano {formatPoints(w.tier.next.missing)} STS a {w.tier.next.code}
                      </span>
                    ) : (
                      <span className="text-[var(--color-bo-ink-2)]">livello massimo</span>
                    )}
                  </div>
                  <div className="h-2 overflow-hidden rounded bg-slate-100">
                    <div className="h-full bg-[var(--color-bo-accent)]" style={{ width: `${w.tier.progressPct}%` }} />
                  </div>
                </div>
              </div>
            )}
          </QueryState>
        </CardBody>
      </Card>
    </div>
  );
}

function LedgerTab({ id }: { id: string }) {
  const query = useLhQuery<LedgerEntry[]>("wallet", `/v1/wallets/${id}/ledger`, { limit: 100 });
  const columns: Column<LedgerEntry>[] = [
    { key: "when", header: "Quando", render: (e) => formatDateTime(e.occurredAt) },
    { key: "type", header: "Tipo", render: (e) => <StatusPill status={e.type} /> },
    { key: "campaign", header: "Campagna", render: (e) => (e.campaignCode ? <CodeText>{e.campaignCode}</CodeText> : "—") },
    { key: "cur", header: "Valuta", render: (e) => e.currency },
    {
      key: "amount",
      header: "Importo",
      className: "text-right",
      render: (e) => <PointsAmount value={e.direction === "-" ? -e.amount : e.amount} />,
    },
    { key: "bal", header: "Saldo dopo", className: "text-right", render: (e) => <span className="tabular-nums">{formatPoints(e.balanceAfter)}</span> },
  ];
  return (
    <QueryState query={query} service="wallet" isEmpty={(d) => d.length === 0} emptyTitle="Nessun movimento">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.id} />}
    </QueryState>
  );
}

function ActionsTab({ id }: { id: string }) {
  const query = useLhQuery<EvaluationRow[]>("campaign", "/v1/evaluations", { memberId: id, limit: 100 });
  const columns: Column<EvaluationRow>[] = [
    { key: "when", header: "Quando", render: (e) => formatDateTime(e.actionTime) },
    { key: "type", header: "Azione", render: (e) => <CodeText>{e.actionType}</CodeText> },
    { key: "outcome", header: "Esito", render: (e) => <StatusPill status={e.outcome} /> },
    { key: "detail", header: "Dettaglio", render: (e) => <ResultSummary json={e.resultsJson} /> },
  ];
  return (
    <QueryState query={query} service="campaign" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna azione valutata">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.actionId} />}
    </QueryState>
  );
}

function ResultSummary({ json }: { json: string }) {
  let results: { campaignCode: string; matched: boolean; reason: string | null }[] = [];
  try {
    results = JSON.parse(json);
  } catch {
    return <span className="text-xs text-slate-400">—</span>;
  }
  const matched = results.filter((r) => r.matched).map((r) => r.campaignCode);
  const skipped = results.filter((r) => !r.matched);
  return (
    <span className="text-xs">
      {matched.length > 0 ? <span className="text-emerald-700">✓ {matched.join(", ")}</span> : null}
      {matched.length > 0 && skipped.length > 0 ? " · " : ""}
      {skipped.length > 0 ? (
        <span className="text-slate-500">
          {skipped.map((r) => `${r.campaignCode} (${r.reason})`).slice(0, 3).join(", ")}
        </span>
      ) : null}
    </span>
  );
}

function Row({ label, value }: { label: string; value?: string }) {
  if (value === undefined) return null;
  return (
    <div className="flex justify-between">
      <dt className="text-[var(--color-bo-ink-2)]">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
