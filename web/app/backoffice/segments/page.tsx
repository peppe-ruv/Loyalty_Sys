"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { Reward } from "@/lib/api/types";
import type { ContentItem } from "@/lib/content/types";
import type { Segment } from "@/lib/segments/types";
import { describeCriteria } from "@/lib/segments/criteria";
import { segmentUsage, usageLabel, type CampaignLike } from "@/lib/segments/usage";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { INPUT } from "@/components/bo/FormBits";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { TypePill } from "@/components/bo/segments/TypePill";
import { formatRelative } from "@/lib/format/dates";
import { cn } from "@/lib/cn";

const FILTER = INPUT.replace("w-full ", "");

// BO-04 Segmenti (docs/08 §BO-04, F-SEG-01/02/03): elenco con tipo, membri, ultimo ricalcolo e "usato da" (campagne,
// premi, contenuti che citano il codice). "Usato da" legge tre servizi: se uno dorme, quella parte resta vuota.
export default function SegmentsPage() {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const query = useLhQuery<Page<Segment>>("member", "/v1/segments", { q, type, status, size: 100 });
  const campaigns = useLhQuery<CampaignLike[]>("campaign", "/v1/campaigns");
  const rewards = useLhQuery<Reward[]>("reward", "/v1/rewards");
  const contents = useLhQuery<ContentItem[]>("engagement", "/v1/contents");
  const usageDegraded = campaigns.isError || rewards.isError || contents.isError;

  const columns: Column<Segment>[] = [
    {
      key: "name",
      header: "Segmento",
      render: (s) => (
        <div>
          <p className="font-medium">{s.name}</p>
          <CodeText>{s.code}</CodeText>
        </div>
      ),
    },
    { key: "type", header: "Tipo", render: (s) => <TypePill type={s.type} /> },
    {
      key: "rule",
      header: "Criterio",
      className: "max-w-[320px]",
      render: (s) => (
        <span className="line-clamp-2 text-xs text-[var(--color-bo-ink-2)]">
          {s.type === "STATIC" ? "elenco manuale" : describeCriteria(s.criteria)}
        </span>
      ),
    },
    { key: "count", header: "Membri", className: "text-right", render: (s) => <span className="tabular-nums">{s.memberCount}</span> },
    {
      key: "refreshed",
      header: "Ultimo ricalcolo",
      render: (s) => (s.refreshedAt ? <span title={s.refreshedAt}>{formatRelative(s.refreshedAt)}</span> : "—"),
    },
    {
      key: "usage",
      header: "Usato da",
      render: (s) =>
        campaigns.isLoading || rewards.isLoading || contents.isLoading ? (
          <span className="text-xs text-slate-400">…</span>
        ) : (
          <span className="text-xs">
            {usageLabel(segmentUsage(s.code, { campaigns: campaigns.data, rewards: rewards.data, contents: contents.data }))}
          </span>
        ),
    },
    { key: "status", header: "Stato", render: (s) => <StatusPill status={s.status} /> },
  ];

  return (
    <div>
      <PageHeader
        title="Segmenti"
        subtitle="Gruppi di membri, statici o dinamici: pubblico di campagne, premi e contenuti."
        actions={
          <Can capability="segment.write" mode="disable">
            <Link
              href="/backoffice/segments/new"
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              Nuovo segmento
            </Link>
          </Can>
        }
      />
      <div className="mb-3 flex flex-wrap gap-2">
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Cerca per nome o codice" className={cn(FILTER, "w-56")} />
        <select aria-label="Tipo" value={type} onChange={(e) => setType(e.target.value)} className={cn(FILTER, "w-40")}>
          <option value="">Tutti i tipi</option>
          <option value="DYNAMIC">Dinamici</option>
          <option value="STATIC">Statici</option>
        </select>
        <select aria-label="Stato" value={status} onChange={(e) => setStatus(e.target.value)} className={cn(FILTER, "w-40")}>
          <option value="ACTIVE">Attivi</option>
          <option value="ARCHIVED">Archiviati</option>
          <option value="">Tutti</option>
        </select>
      </div>
      {usageDegraded ? (
        <p className="mb-2 text-xs text-amber-700">
          Alcuni servizi non rispondono: la colonna «Usato da» può essere incompleta.
        </p>
      ) : null}
      <QueryState
        query={query}
        service="member"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle="Nessun segmento"
        emptyHint="Crea un segmento dinamico con dei criteri, oppure uno statico con un elenco di membri."
      >
        {(d) => (
          <DataTable columns={columns} rows={d.items} rowKey={(s) => s.id} onRowClick={(s) => router.push(`/backoffice/segments/${s.code}`)} />
        )}
      </QueryState>
    </div>
  );
}
