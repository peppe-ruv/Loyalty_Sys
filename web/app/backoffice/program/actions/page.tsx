"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, LhError } from "@/lib/api/client";
import type { CampaignSummary } from "@/lib/api/types";
import type { ActionType } from "@/lib/actiontypes/types";
import { CATEGORY_LABEL } from "@/lib/actiontypes/types";
import { campaignsUsing } from "@/lib/actiontypes/schema";
import { SideSheet } from "@/components/bo/SideSheet";
import { ActionTypeDetail } from "@/components/bo/actiontypes/ActionTypeDetail";
import { ActionTypeEditor } from "@/components/bo/actiontypes/ActionTypeEditor";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader, StatusPill, CodeText, EmptyState } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";

// BO-09 Azioni e fonti (docs/08 §BO-09). M6.7: tipi azione con dettaglio (campi, esempio, "usato da"), *Nuovo tipo
// custom* con editor a righe → JSON Schema e *Prova* in BO-28. M3.5: scheda `bridge` — ponte interno fatto → azione
// con interruttore (ADMIN) e contatore. Fonti con interruttore (ADMIN, ingestion PUT /v1/sources/{code}).
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
  const query = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  const sources = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  // "Usato da n campagne" (docs/08 §BO-09): i trigger li conosce campaign; se dorme la colonna resta "—".
  const campaigns = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns");
  const [q, setQ] = useState("");
  const [origin, setOrigin] = useState("");
  const [open, setOpen] = useState<ActionType | null>(null);
  const [editing, setEditing] = useState<ActionType | "new" | null>(null);

  const sourcesFor = (code: string) =>
    sources.data ? sources.data.filter((s) => s.enabled && (s.allowedTypes.length === 0 || s.allowedTypes.includes(code))).map((s) => s.code) : null;
  const usedBy = (code: string) => (campaigns.data ? campaignsUsing(code, campaigns.data) : null);

  const columns: Column<ActionType>[] = [
    { key: "code", header: "Codice", render: (t) => <CodeText>{t.code}</CodeText> },
    { key: "name", header: "Nome", render: (t) => t.name },
    { key: "cat", header: "Categoria", render: (t) => <span className="text-xs">{CATEGORY_LABEL[t.category ?? ""] ?? t.category ?? "—"}</span> },
    { key: "origin", header: "Origine", render: (t) => <StatusPill status={t.origin} /> },
    { key: "enabled", header: "Abilitato", render: (t) => (t.enabled ? "✓" : "—") },
    {
      key: "used",
      header: "Usato da",
      className: "text-right",
      render: (t) => {
        const u = usedBy(t.code);
        return <span className="tabular-nums text-xs">{u == null ? "—" : `${u.length} campagn${u.length === 1 ? "a" : "e"}`}</span>;
      },
    },
  ];

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Cerca per codice o nome"
          aria-label="Cerca tipi azione"
          className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
        />
        <select
          value={origin}
          onChange={(e) => setOrigin(e.target.value)}
          aria-label="Origine"
          className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
        >
          <option value="">Tutte le origini</option>
          <option value="SYSTEM">Di sistema</option>
          <option value="CUSTOM">Custom</option>
        </select>
        <div className="ml-auto">
          <Can capability="actiontype.custom" mode="disable">
            <button
              onClick={() => setEditing("new")}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white"
            >
              Nuovo tipo custom
            </button>
          </Can>
        </div>
      </div>
      <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessun tipo azione">
        {(d) => {
          const needle = q.trim().toLowerCase();
          const rows = d.filter(
            (t) =>
              (!origin || t.origin === origin) &&
              (!needle || t.code.toLowerCase().includes(needle) || t.name.toLowerCase().includes(needle)),
          );
          return rows.length === 0 ? (
            <EmptyState title="Nessun tipo corrisponde ai filtri" />
          ) : (
            <DataTable columns={columns} rows={rows} rowKey={(t) => t.code} onRowClick={(t) => setOpen(t)} />
          );
        }}
      </QueryState>
      <SideSheet open={open != null} title={open?.name ?? ""} onClose={() => setOpen(null)}>
        {open ? (
          <ActionTypeDetail
            type={open}
            sources={sourcesFor(open.code)}
            usedBy={usedBy(open.code)}
            onEdit={() => {
              setEditing(open);
              setOpen(null);
            }}
          />
        ) : null}
      </SideSheet>
      <SideSheet
        open={editing != null}
        title={editing === "new" ? "Nuovo tipo custom" : `Modifica ${editing?.code ?? ""}`}
        onClose={() => setEditing(null)}
      >
        {editing != null ? (
          <ActionTypeEditor
            key={editing === "new" ? "new" : editing.code}
            initial={editing === "new" ? null : editing}
            onSaved={(t) => {
              setEditing(null);
              setOpen(t);
            }}
            onCancel={() => setEditing(null)}
          />
        ) : null}
      </SideSheet>
    </div>
  );
}

function SourcesTab() {
  const qc = useQueryClient();
  const query = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Interruttore (BO-09, F-ING-05): fonte spenta → i suoi eventi diventano REJECTED/SOURCE_DISABLED (visibili in BO-26).
  async function toggle(s: SourceRow) {
    setBusy(s.code);
    setError(null);
    try {
      await lhFetch("ingestion", `/v1/sources/${s.code}`, { method: "PUT", body: JSON.stringify({ enabled: !s.enabled }) });
      qc.invalidateQueries({ queryKey: ["ingestion"] });
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || "Aggiornamento non riuscito");
    } finally {
      setBusy(null);
    }
  }

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
    {
      key: "toggle",
      header: "Attiva",
      render: (s) => (
        <Can capability="program.config" mode="disable">
          <button
            onClick={() => toggle(s)}
            disabled={busy === s.code}
            aria-pressed={s.enabled}
            className={`rounded-full px-2.5 py-0.5 text-xs font-medium disabled:opacity-50 ${s.enabled ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-600"}`}
          >
            {s.enabled ? "Accesa" : "Spenta"}
          </button>
        </Can>
      ),
    },
  ];
  return (
    <div className="space-y-3">
      {error ? <p className="rounded bg-red-50 p-2 text-sm text-red-800">{error}</p> : null}
      <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna fonte">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(s) => s.code} />}
      </QueryState>
    </div>
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
