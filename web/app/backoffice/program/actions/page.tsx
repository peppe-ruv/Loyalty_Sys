"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, LhError } from "@/lib/api/client";
import type { EventType } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";

// BO-09 Azioni e fonti (docs/08 §BO-09). Tipi e fonti in sola lettura (custom e interruttori: M6);
// M3.5: scheda `bridge` — ponte interno fatto → azione con interruttore (ADMIN) e contatore.
interface SourceRow {
  code: string;
  name: string;
  kind: string;
  enabled: boolean;
  allowedTypes: string[];
  description: string | null;
}

interface MappingRow {
  factType: string;
  actionType: string;
  enabled: boolean;
}

interface InboundRow {
  id: string;
  typeCode: string;
}

const TABS = [
  { key: "types", label: "Tipi azione" },
  { key: "sources", label: "Fonti" },
  { key: "bridge", label: "Ponte interno" },
];

export default function ActionsPage() {
  const tab = useSearchParams().get("tab") ?? "types";
  return (
    <div>
      <PageHeader title="Azioni e fonti" subtitle="Tipi azione, fonti e ponte interno del programma" />
      <Tabs tabs={TABS} current={tab} />
      {tab === "sources" ? <SourcesTab /> : tab === "bridge" ? <BridgeTab /> : <TypesTab />}
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

function BridgeTab() {
  const qc = useQueryClient();
  const query = useLhQuery<MappingRow[]>("ingestion", "/v1/internal-mappings");
  // Contatore: azioni generate dal ponte tra gli ultimi 500 ingressi con fonte `internal`.
  const recent = useLhQuery<InboundRow[]>("ingestion", "/v1/inbound-events?source=internal&status=ACCEPTED&limit=500");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const counts = new Map<string, number>();
  for (const r of recent.data ?? []) counts.set(r.typeCode, (counts.get(r.typeCode) ?? 0) + 1);

  async function toggle(m: MappingRow) {
    setBusy(m.factType);
    setError(null);
    try {
      await lhFetch("ingestion", `/v1/internal-mappings/${m.factType}`, {
        method: "PUT",
        body: JSON.stringify({ enabled: !m.enabled }),
      });
      qc.invalidateQueries({ queryKey: ["ingestion"] });
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || "Aggiornamento non riuscito");
    } finally {
      setBusy(null);
    }
  }

  const columns: Column<MappingRow>[] = [
    { key: "fact", header: "Fatto", render: (m) => <CodeText>{m.factType}</CodeText> },
    { key: "arrow", header: "", render: () => <span className="text-[var(--color-bo-ink-2)]">→</span> },
    { key: "action", header: "Azione generata", render: (m) => <CodeText>{"action." + m.actionType}</CodeText> },
    {
      key: "count",
      header: "Azioni generate",
      className: "text-right",
      render: (m) => <span className="tabular-nums">{recent.data ? (counts.get(m.actionType) ?? 0) : "—"}</span>,
    },
    {
      key: "enabled",
      header: "Attivo",
      render: (m) => (
        <Can capability="program.config" mode="disable">
          <button
            onClick={() => toggle(m)}
            disabled={busy === m.factType}
            aria-pressed={m.enabled}
            className={`rounded-full px-2.5 py-0.5 text-xs font-medium disabled:opacity-50 ${m.enabled ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-600"}`}
          >
            {m.enabled ? "Attivo" : "Spento"}
          </button>
        </Can>
      ),
    },
  ];

  return (
    <div className="space-y-3">
      <p className="text-sm text-[var(--color-bo-ink-2)]">
        Alcuni fatti del programma rientrano come azioni premianti (fonte <CodeText>internal</CodeText>), così le campagne
        possono reagire, per esempio con un bonus alla salita di livello. Anti-loop: ogni passaggio dal ponte aumenta
        <CodeText>lhhop</CodeText> di 1 e oltre 3 passaggi l&apos;evento finisce in DLQ con <CodeText>LOOP_GUARD</CodeText>.
        Punti, spese, campagne, messaggi e cambi di stato non sono mai mappabili.
      </p>
      {error ? <p className="rounded bg-red-50 p-2 text-sm text-red-800">{error}</p> : null}
      <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna mappatura">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(m) => m.factType} />}
      </QueryState>
    </div>
  );
}
