"use client";

import { useSearchParams } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { EventType } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";

// BO-09 Azioni e fonti (docs/08 §BO-09), sola lettura in M1. Tipi custom, interruttori e ponte: M6.
interface SourceRow {
  code: string;
  name: string;
  kind: string;
  enabled: boolean;
  allowedTypes: string[];
  description: string | null;
}

const TABS = [
  { key: "types", label: "Tipi azione" },
  { key: "sources", label: "Fonti" },
];

export default function ActionsPage() {
  const tab = useSearchParams().get("tab") ?? "types";
  return (
    <div>
      <PageHeader title="Azioni e fonti" subtitle="Tipi azione e fonti del programma (sola lettura in M1)" />
      <Tabs tabs={TABS} current={tab} />
      {tab === "sources" ? <SourcesTab /> : <TypesTab />}
    </div>
  );
}

function TypesTab() {
  const query = useLhQuery<EventType[]>("ingestion", "/v1/event-types");
  const columns: Column<EventType>[] = [
    { key: "code", header: "Codice", render: (t) => <CodeText>{t.code}</CodeText> },
    { key: "name", header: "Nome", render: (t) => t.name },
    { key: "cat", header: "Categoria", render: (t) => <span className="text-xs">{t.category ?? "—"}</span> },
    { key: "origin", header: "Origine", render: (t) => <StatusPill status={t.origin} /> },
    { key: "enabled", header: "Abilitato", render: (t) => (t.enabled ? "✓" : "—") },
    { key: "schema", header: "Schema", render: (t) => (t.dataSchema ? "JSON Schema" : "—") },
  ];
  return (
    <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessun tipo azione">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(t) => t.code} />}
    </QueryState>
  );
}

function SourcesTab() {
  const query = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  const columns: Column<SourceRow>[] = [
    { key: "code", header: "Codice", render: (s) => <CodeText>{s.code}</CodeText> },
    { key: "name", header: "Nome", render: (s) => s.name },
    { key: "kind", header: "Tipo", render: (s) => <span className="text-xs">{s.kind}</span> },
    { key: "enabled", header: "Stato", render: (s) => <StatusPill status={s.enabled ? "ACTIVE" : "BLOCKED"} /> },
    {
      key: "allowed",
      header: "Tipi ammessi",
      render: (s) => <span className="text-xs">{s.allowedTypes.length ? s.allowedTypes.join(", ") : "tutti"}</span>,
    },
  ];
  return (
    <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna fonte">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(s) => s.code} />}
    </QueryState>
  );
}
