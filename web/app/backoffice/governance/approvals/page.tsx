"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import { PageHeader, EmptyState } from "@/components/bo/primitives";
import { Tabs } from "@/components/bo/Tabs";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { SideSheet } from "@/components/bo/SideSheet";
import { QueryState } from "@/components/bo/QueryState";
import { useBoPersona } from "@/components/bo/PersonaContext";
import { ApprovalSheet } from "@/components/bo/approvals/ApprovalSheet";
import { formatRelative } from "@/lib/format/dates";
import { formatActor, mergeQueues, outcomeOf, sentByMe, toApproveBy, type SourceResult } from "@/lib/approvals/queue";
import { useApprovalPolicy } from "@/lib/approvals/usePolicy";
import { ENTITY_TYPES, SOURCES, type ApprovalItem, type EntityType } from "@/lib/approvals/types";

// BO-21 Approvazioni (docs/08 §BO-21, F-APR-01/02/03): coda aggregata lato web dalle tre fonti (campaign, reward,
// gamification); una fonte addormentata non blocca le altre (riga degraded). Schede: Da approvare (per il ruolo
// corrente), Inviate da me, policy in sola lettura.
const TABS = [
  { key: "todo", label: "Da approvare" },
  { key: "mine", label: "Inviate da me" },
  { key: "policy", label: "Policy" },
];

function useQueues(submittedBy?: string): { results: SourceResult[]; loading: boolean; refetch: () => void } {
  const params = submittedBy ? { submittedBy } : undefined;
  const campaign = useLhQuery<ApprovalItem[]>("campaign", "/v1/approvals", params);
  const reward = useLhQuery<ApprovalItem[]>("reward", "/v1/approvals", params);
  const contest = useLhQuery<ApprovalItem[]>("gamification", "/v1/approvals", params);
  const byType: Record<EntityType, typeof campaign> = { CAMPAIGN: campaign, REWARD: reward, CONTEST: contest };
  return {
    results: ENTITY_TYPES.map((t) => ({ entityType: t, items: byType[t].data, failed: byType[t].isError })),
    loading: ENTITY_TYPES.some((t) => byType[t].isLoading),
    refetch: () => ENTITY_TYPES.forEach((t) => void byType[t].refetch()),
  };
}

export default function ApprovalsPage() {
  const tab = useSearchParams().get("tab") ?? "todo";
  return (
    <div>
      <PageHeader title="Approvazioni" subtitle="Campagne, premi e concorsi in attesa di revisione" />
      <Tabs tabs={TABS} current={tab} />
      {tab === "policy" ? <PolicyTab /> : <QueueTab mine={tab === "mine"} />}
    </div>
  );
}

function QueueTab({ mine }: { mine: boolean }) {
  const persona = useBoPersona();
  const actor = `${persona.role}:${persona.username}`;
  const { results, loading, refetch } = useQueues(mine ? actor : undefined);
  const [open, setOpen] = useState<ApprovalItem | null>(null);
  const merged = mergeQueues(results);
  const rows = mine ? sentByMe(merged) : toApproveBy(merged, persona.role);
  const failed = results.filter((r) => r.failed);

  const columns: Column<ApprovalItem>[] = [
    { key: "type", header: "Tipo", render: (i) => <span className="text-xs">{SOURCES[i.entityType].label}</span> },
    {
      key: "name",
      header: "Nome",
      render: (i) => (
        <div>
          <p className="font-medium">{i.name}</p>
          <p className="font-mono text-[11px] text-[var(--color-bo-ink-2)]">{i.code}</p>
        </div>
      ),
    },
    mine
      ? {
          key: "outcome",
          header: "Esito",
          render: (i) => {
            const o = outcomeOf(i);
            const tone = o.tone === "ok" ? "bg-emerald-100 text-emerald-800" : o.tone === "ko" ? "bg-red-100 text-red-800" : "bg-amber-100 text-amber-800";
            return (
              <div>
                <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}>{o.label}</span>
                {i.comment ? <p className="mt-1 max-w-xs truncate text-xs text-[var(--color-bo-ink-2)]">«{i.comment}»</p> : null}
              </div>
            );
          },
        }
      : { key: "by", header: "Inviato da", render: (i) => <span className="text-xs">{formatActor(i.submittedBy)}</span> },
    { key: "when", header: "Quando", render: (i) => <span className="text-xs">{i.submittedAt ? formatRelative(i.submittedAt) : "—"}</span> },
    { key: "role", header: "Ruolo richiesto", render: (i) => <span className="text-xs">{i.requiredRole ?? "—"}</span> },
    { key: "summary", header: "Sintesi", className: "max-w-[280px]", render: (i) => <span className="line-clamp-2 text-xs text-[var(--color-bo-ink-2)]">{i.summary}</span> },
  ];

  return (
    <div className="space-y-3">
      {failed.map((f) => (
        <p key={f.entityType} role="status" className="rounded bg-amber-50 p-2 text-xs text-amber-800">
          {SOURCES[f.entityType].label}: il servizio {SOURCES[f.entityType].service} non risponde, la sua parte di coda manca.{" "}
          <button onClick={refetch} className="underline">Riprova</button>
        </p>
      ))}
      {loading && rows.length === 0 ? (
        <p className="text-sm text-[var(--color-bo-ink-2)]">Caricamento…</p>
      ) : rows.length === 0 ? (
        <EmptyState
          title={mine ? "Non hai inviato nulla in revisione" : "Niente da approvare"}
          hint={mine ? "Da una campagna, un premio o un concorso in bozza: «Invia in revisione»." : "Gli oggetti inviati in revisione per il tuo ruolo compaiono qui."}
        />
      ) : (
        <DataTable columns={columns} rows={rows} rowKey={(i) => `${i.entityType}-${i.id}`} onRowClick={setOpen} />
      )}
      <SideSheet open={open != null} title={open?.name ?? ""} onClose={() => setOpen(null)}>
        {open ? <ApprovalSheet key={`${open.entityType}-${open.id}`} item={open} onDone={() => setOpen(null)} /> : null}
      </SideSheet>
    </div>
  );
}

function PolicyTab() {
  const policy = useApprovalPolicy();
  const LABEL: Record<string, string> = { CAMPAIGN: "Campagna", REWARD: "Premio", CONTEST: "Concorso", CONTENT: "Contenuto" };
  return (
    <QueryState query={policy} service="campaign">
      {(p) => (
        <div className="space-y-3">
          {!p.enabled ? (
            <p className="rounded bg-amber-50 p-2 text-sm text-amber-800">
              Approvazione spenta (<span className="font-mono">LH_APPROVAL_ENABLED=false</span>): tutto si pubblica direttamente.
            </p>
          ) : null}
          <table className="w-full max-w-2xl text-sm">
            <thead>
              <tr className="text-left text-xs text-[var(--color-bo-ink-2)]">
                <th className="py-1 font-medium">Tipo oggetto</th>
                <th className="py-1 font-medium">Approvazione richiesta</th>
                <th className="py-1 font-medium">Ruolo</th>
              </tr>
            </thead>
            <tbody>
              {p.rows.map((r) => (
                <tr key={r.entityType} className="border-t border-[var(--color-bo-border)]">
                  <td className="py-1.5">{LABEL[r.entityType] ?? r.entityType}</td>
                  <td className="py-1.5">{r.when}</td>
                  <td className="py-1.5">{r.approverRole ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            ADMIN può sempre decidere al posto del ruolo richiesto: la decisione è marcata «override» nell&apos;audit.
          </p>
        </div>
      )}
    </QueryState>
  );
}
