"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, LhError } from "@/lib/api/client";
import type { CampaignSummary } from "@/lib/api/types";
import { categoryLabel, ORIGIN_LABEL, SOURCE_KIND_LABEL, type ActionType } from "@/lib/actiontypes/types";
import { campaignImpact } from "@/lib/actiontypes/schema";
import { externalSourcesFor, isEditableSource, isProgramGenerated, type SourceRow } from "@/lib/actiontypes/sources";
import { outcomeCount, type InboundRow as InboundEvent, type OutcomeCounts } from "@/lib/inbound/inbound";
import { formatRelative } from "@/lib/format/dates";
import { actionIcon, actionIconLabel } from "@/lib/icons/action-icons";
import { SideSheet } from "@/components/bo/SideSheet";
import { ActionTypeDetail } from "@/components/bo/actiontypes/ActionTypeDetail";
import { ActionTypeEditor, type SavedInfo } from "@/components/bo/actiontypes/ActionTypeEditor";
import { AllowedTypesEditor } from "@/components/bo/actiontypes/AllowedTypesEditor";
import { HowItWorks } from "@/components/bo/actiontypes/HowItWorks";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader, CodeText, EmptyState } from "@/components/bo/primitives";
import { Can, useCan } from "@/components/bo/Can";
import { useIsDemo } from "@/components/bo/PersonaContext";
import { it } from "@/lib/i18n/it";
import { cn } from "@/lib/cn";

// BO-09 Azioni e fonti (docs/08 §BO-09; F-ING-05, F-ING-06). Riquadro «Come funziona»; scheda Azioni con icona, fonti
// («solo simulatore» in ambra), dettaglio con «Da dove può arrivare» ed editor a sezioni numerate; scheda Fonti con
// volumi delle ultime 24 ore e ultimo evento (dal Monitor ingressi), interruttore confermato e azioni ammesse
// modificabili da ADMIN solo sulle fonti esterne (Q-433); scheda «Azioni generate dal programma» (ponte interno
// fatto → azione, M3.5) spiegata in chiaro, con l'anti-loop sotto *Dettagli tecnici*. Gli stati vuoti rimandano alla
// Console demo solo nel profilo `demo`.

const T = it.actions;

interface MappingRow {
  factType: string;
  actionType: string;
  enabled: boolean;
}

const TABS = [
  { key: "types", label: T.tabs.types },
  { key: "sources", label: T.tabs.sources },
  { key: "bridge", label: T.tabs.bridge },
];

export default function ActionsPage() {
  const tab = useSearchParams().get("tab") ?? "types";
  return (
    <div>
      <PageHeader title={T.pageTitle} subtitle={T.pageSubtitle} />
      <HowItWorks />
      <Tabs tabs={TABS} current={tab} />
      {tab === "sources" ? <SourcesTab /> : tab === "bridge" ? <BridgeTab /> : <TypesTab />}
    </div>
  );
}

function TypesTab() {
  const query = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  const sources = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  // «Usata da n campagne» (docs/08 §BO-09): i trigger li conosce campaign; se dorme la colonna resta "—".
  const campaigns = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns");
  const canCreate = useCan("actiontype.custom");
  const [q, setQ] = useState("");
  const [origin, setOrigin] = useState("");
  const [openCode, setOpenCode] = useState<string | null>(null);
  const [justCreated, setJustCreated] = useState<{ code: string; failedOn: string[] } | null>(null);
  const [editing, setEditing] = useState<ActionType | "new" | null>(null);
  const [dirty, setDirty] = useState(false);
  const onDirtyChange = useCallback((d: boolean) => setDirty(d), []);

  const types = query.data ?? [];
  // Il dettaglio legge sempre l'ultima versione dalla cache (dopo un *Abilita* o una modifica).
  const open = openCode ? (types.find((x) => x.code === openCode) ?? null) : null;
  const usedBy = (code: string) => (campaigns.data ? campaignImpact(code, campaigns.data).using : null);
  const closeEditor = () => {
    setEditing(null);
    setDirty(false);
  };

  const columns: Column<ActionType>[] = [
    {
      key: "icon",
      header: T.list.icon,
      render: (a) => {
        const Icon = actionIcon(a.icon);
        return <Icon className="size-4 text-[var(--color-bo-ink-2)]" aria-label={actionIconLabel(a.icon) ?? a.icon ?? undefined} role="img" />;
      },
    },
    { key: "name", header: T.list.name, render: (a) => <span className="font-medium">{a.name}</span> },
    { key: "code", header: T.list.code, render: (a) => <CodeText>{a.code}</CodeText> },
    { key: "cat", header: T.list.category, render: (a) => <span className="text-xs">{categoryLabel(a.category)}</span> },
    {
      key: "origin",
      header: T.list.origin,
      render: (a) => (
        <span className={cn("rounded-full px-2 py-0.5 text-xs", a.origin === "CUSTOM" ? "bg-violet-100 text-violet-800" : "bg-slate-100 text-slate-700")}>
          {ORIGIN_LABEL[a.origin]}
        </span>
      ),
    },
    {
      key: "sources",
      header: T.list.sources,
      render: (a) => {
        if (!sources.data) return <span className="text-xs">—</span>;
        const ext = externalSourcesFor(a.code, sources.data);
        if (ext.length) return <span className="text-xs">{ext.map((s) => s.name).join(", ")}</span>;
        if (isProgramGenerated(a)) return <span className="text-xs text-[var(--color-bo-ink-2)]">{T.list.fromProgram}</span>;
        return <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-900">{T.list.onlySimulator}</span>;
      },
    },
    { key: "enabled", header: T.list.enabled, render: (a) => <span className="text-xs">{a.enabled ? T.list.yes : T.list.no}</span> },
    {
      key: "used",
      header: T.list.usedBy,
      className: "text-right",
      render: (a) => {
        const u = usedBy(a.code);
        return <span className="tabular-nums text-xs">{u == null ? "—" : T.list.campaigns(u.length)}</span>;
      },
    },
  ];

  const createAction = canCreate ? { label: T.list.emptyAction, onClick: () => setEditing("new") } : undefined;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={T.list.search}
          aria-label={T.list.searchLabel}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
        />
        <select
          value={origin}
          onChange={(e) => setOrigin(e.target.value)}
          aria-label={T.list.originLabel}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
        >
          <option value="">{T.list.allOrigins}</option>
          <option value="SYSTEM">{ORIGIN_LABEL.SYSTEM}</option>
          <option value="CUSTOM">{ORIGIN_LABEL.CUSTOM}</option>
        </select>
        <div className="ml-auto">
          <Can capability="actiontype.custom" mode="disable">
            <button onClick={() => setEditing("new")} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white">
              {T.list.newAction}
            </button>
          </Can>
        </div>
      </div>
      <QueryState
        query={query}
        service="ingestion"
        isEmpty={(d) => d.length === 0}
        emptyTitle={T.list.emptyTitle}
        emptyHint={T.list.emptyHint}
        emptyAction={createAction}
      >
        {(d) => {
          const needle = q.trim().toLowerCase();
          const rows = d.filter(
            (a) =>
              (!origin || a.origin === origin) &&
              (!needle || a.code.toLowerCase().includes(needle) || a.name.toLowerCase().includes(needle)),
          );
          if (rows.length > 0) return <DataTable columns={columns} rows={rows} rowKey={(a) => a.code} onRowClick={(a) => setOpenCode(a.code)} />;
          if (origin === "CUSTOM" && !needle)
            return <EmptyState title={T.list.emptyCustomTitle} hint={T.list.emptyCustomHint} action={createAction} />;
          return (
            <EmptyState
              title={T.list.noMatchTitle}
              hint={T.list.noMatchHint}
              action={{
                label: T.list.clearFilters,
                onClick: () => {
                  setQ("");
                  setOrigin("");
                },
              }}
            />
          );
        }}
      </QueryState>
      <SideSheet
        open={open != null}
        title={open?.name ?? ""}
        onClose={() => {
          setOpenCode(null);
          setJustCreated(null);
        }}
      >
        {open ? (
          <ActionTypeDetail
            type={open}
            sources={sources.data ?? null}
            campaigns={campaigns.data ?? null}
            created={justCreated?.code === open.code ? justCreated : null}
            onEdit={() => {
              setEditing(open);
              setOpenCode(null);
              setJustCreated(null);
            }}
          />
        ) : null}
      </SideSheet>
      <SideSheet
        open={editing != null}
        title={editing === "new" ? T.editor.titleNew : T.editor.titleEdit(editing?.name ?? "")}
        onClose={closeEditor}
        dirty={dirty}
        confirmText={T.sheet.confirmTextAction}
      >
        {editing != null ? (
          <ActionTypeEditor
            key={editing === "new" ? "new" : editing.code}
            initial={editing === "new" ? null : editing}
            onDirtyChange={onDirtyChange}
            onSaved={(saved, info: SavedInfo) => {
              closeEditor();
              if (editing === "new") setJustCreated({ code: saved.code, failedOn: info.failedOn });
              setOpenCode(saved.code);
            }}
            onUseExisting={(existing) => {
              closeEditor();
              setOpenCode(existing.code);
            }}
            onCancel={closeEditor}
          />
        ) : null}
      </SideSheet>
    </div>
  );
}

/** Ultime 24 ore di una fonte: totale e accettate (`GET /v1/inbound-events/counts`); «—» se il servizio non risponde. */
function SourceVolume({ code, from }: { code: string; from: string }) {
  const counts = useLhQuery<OutcomeCounts>("ingestion", "/v1/inbound-events/counts", { source: code, from });
  if (counts.isLoading) return <span className="text-xs text-[var(--color-bo-ink-2)]">…</span>;
  const total = outcomeCount("", counts.data);
  if (counts.isError || total == null) return <span className="text-xs">—</span>;
  return <span className="tabular-nums text-xs">{T.sources.volumeValue(outcomeCount("ACCEPTED", counts.data) ?? 0, total)}</span>;
}

/** Ultimo evento ricevuto da una fonte (`GET /v1/inbound-events?limit=1`), in tempo relativo. */
function SourceLastEvent({ code }: { code: string }) {
  const last = useLhQuery<InboundEvent[]>("ingestion", "/v1/inbound-events", { source: code, limit: 1 });
  if (last.isLoading) return <span className="text-xs text-[var(--color-bo-ink-2)]">…</span>;
  if (last.isError || !Array.isArray(last.data)) return <span className="text-xs">—</span>;
  const at = last.data[0]?.receivedAt;
  return <span className="text-xs">{at ? formatRelative(at) : T.sources.noEvents}</span>;
}

function SourcesTab() {
  const qc = useQueryClient();
  const isDemo = useIsDemo();
  // Finestra delle ultime 24 ore, fissata all'apertura della scheda (chiave stabile delle query).
  const [from] = useState(() => new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString());
  const query = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  const types = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmOff, setConfirmOff] = useState<SourceRow | null>(null);
  const [editing, setEditing] = useState<SourceRow | null>(null);
  const [dirty, setDirty] = useState(false);
  const onDirtyChange = useCallback((d: boolean) => setDirty(d), []);
  const names = new Map((types.data ?? []).map((x) => [x.code, x.name]));

  // Interruttore (F-ING-05): fonte spenta → i suoi eventi diventano REJECTED/SOURCE_DISABLED (visibili in BO-26).
  async function setEnabled(s: SourceRow, enabled: boolean) {
    setBusy(s.code);
    setError(null);
    setConfirmOff(null);
    try {
      await lhFetch("ingestion", `/v1/sources/${s.code}`, { method: "PUT", body: JSON.stringify({ enabled }) });
      qc.invalidateQueries({ queryKey: ["ingestion"] });
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || T.sources.failed);
    } finally {
      setBusy(null);
    }
  }

  const columns: Column<SourceRow>[] = [
    {
      key: "name",
      header: T.sources.name,
      render: (s) => (
        <div>
          <p className="font-medium">{s.name}</p>
          <CodeText>{s.code}</CodeText>
          {s.description ? <p className="text-xs text-[var(--color-bo-ink-2)]">{s.description}</p> : null}
        </div>
      ),
    },
    { key: "kind", header: T.sources.kind, render: (s) => <span className="text-xs">{SOURCE_KIND_LABEL[s.kind] ?? s.kind}</span> },
    {
      key: "allowed",
      header: T.sources.allowed,
      render: (s) =>
        // Il ponte interno e il simulatore accettano sempre tutto: limitarli romperebbe il ponte e il simulatore.
        isEditableSource(s) ? (
          <div className="space-y-1 text-xs">
            <p>{s.allowedTypes.length ? s.allowedTypes.map((c) => names.get(c) ?? c).join(", ") : T.sources.allTypes}</p>
            <Can capability="program.config" mode="disable">
              <button type="button" onClick={() => setEditing(s)} className="underline" aria-label={`${T.sources.editAllowed}: ${s.name}`}>
                {T.sources.editAllowed}
              </button>
            </Can>
          </div>
        ) : (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{T.sources.internalAllowed}</p>
        ),
    },
    { key: "volume", header: T.sources.volume, className: "text-right", render: (s) => <SourceVolume code={s.code} from={from} /> },
    { key: "last", header: T.sources.lastEvent, render: (s) => <SourceLastEvent code={s.code} /> },
    {
      key: "state",
      header: T.sources.state,
      render: (s) => (
        <Can capability="program.config" mode="disable">
          <button
            type="button"
            role="switch"
            aria-checked={s.enabled}
            aria-label={T.sources.switchLabel(s.name)}
            onClick={() => (s.enabled ? setConfirmOff(s) : setEnabled(s, true))}
            disabled={busy === s.code}
            className={cn(
              "rounded-full px-2.5 py-0.5 text-xs font-medium disabled:opacity-50",
              s.enabled ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-600",
            )}
          >
            {s.enabled ? T.sources.on : T.sources.off}
          </button>
        </Can>
      ),
    },
  ];
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-2 text-sm text-[var(--color-bo-ink-2)]">
        <p className="max-w-2xl">{T.sources.intro}</p>
        <Link href="/backoffice/observe/inbound" className="text-xs underline">
          {T.sources.monitor}
        </Link>
      </div>
      {confirmOff ? (
        <div role="alertdialog" aria-labelledby="bo09-off" className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          <p id="bo09-off">{T.sources.confirmOff(confirmOff.name)}</p>
          <div className="mt-2 flex gap-2">
            <button type="button" autoFocus onClick={() => setConfirmOff(null)} className="rounded border border-amber-400 px-3 py-1 text-xs font-medium">
              {T.sources.confirmOffNo}
            </button>
            <button type="button" onClick={() => setEnabled(confirmOff, false)} className="rounded bg-amber-700 px-3 py-1 text-xs font-medium text-white">
              {T.sources.confirmOffYes}
            </button>
          </div>
        </div>
      ) : null}
      {error ? <p role="alert" className="rounded bg-red-50 p-2 text-sm text-red-800">{error}</p> : null}
      <QueryState
        query={query}
        service="ingestion"
        isEmpty={(d) => d.length === 0}
        emptyTitle={T.sources.emptyTitle}
        emptyHint={isDemo ? T.sources.emptyHint : T.sources.emptyHintEnterprise}
        emptyAction={isDemo ? { label: T.sources.emptyAction, href: "/backoffice/demo/console" } : undefined}
      >
        {(d) => <DataTable columns={columns} rows={d} rowKey={(s) => s.code} />}
      </QueryState>
      <SideSheet
        open={editing != null}
        title={editing ? T.sources.editTitle(editing.name) : ""}
        onClose={() => {
          setEditing(null);
          setDirty(false);
        }}
        dirty={dirty}
        confirmText={T.sheet.confirmTextSources}
      >
        {editing ? (
          <AllowedTypesEditor
            key={editing.code}
            source={editing}
            types={types.data ?? []}
            onDirtyChange={onDirtyChange}
            onSaved={() => {
              setEditing(null);
              setDirty(false);
            }}
          />
        ) : null}
      </SideSheet>
    </div>
  );
}

function BridgeTab() {
  const qc = useQueryClient();
  const isDemo = useIsDemo();
  const query = useLhQuery<MappingRow[]>("ingestion", "/v1/internal-mappings");
  const types = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  // Contatore: azioni generate dal ponte tra gli ultimi 500 ingressi con fonte `internal`.
  const recent = useLhQuery<Pick<InboundEvent, "id" | "typeCode">[]>("ingestion", "/v1/inbound-events?source=internal&status=ACCEPTED&limit=500");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const names = new Map((types.data ?? []).map((x) => [x.code, x.name]));

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
      setError(err.detail || err.code || T.sources.failed);
    } finally {
      setBusy(null);
    }
  }

  const columns: Column<MappingRow>[] = [
    { key: "fact", header: T.bridge.fact, render: (m) => <CodeText>{m.factType}</CodeText> },
    { key: "arrow", header: "", render: () => <span className="text-[var(--color-bo-ink-2)]" aria-hidden>→</span> },
    {
      key: "action",
      header: T.bridge.action,
      render: (m) => (
        <div>
          <p>{names.get(m.actionType) ?? m.actionType}</p>
          <CodeText>{m.actionType}</CodeText>
        </div>
      ),
    },
    {
      key: "count",
      header: T.bridge.count,
      className: "text-right",
      render: (m) => <span className="tabular-nums">{recent.data ? (counts.get(m.actionType) ?? 0) : "—"}</span>,
    },
    {
      key: "enabled",
      header: T.sources.state,
      render: (m) => (
        <Can capability="program.config" mode="disable">
          <button
            type="button"
            role="switch"
            aria-checked={m.enabled}
            aria-label={`${m.factType} → ${m.actionType}`}
            onClick={() => toggle(m)}
            disabled={busy === m.factType}
            className={cn(
              "rounded-full px-2.5 py-0.5 text-xs font-medium disabled:opacity-50",
              m.enabled ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-600",
            )}
          >
            {m.enabled ? T.bridge.on : T.bridge.off}
          </button>
        </Can>
      ),
    },
  ];

  return (
    <div className="space-y-3">
      <p className="text-sm text-[var(--color-bo-ink-2)]">{T.bridge.intro}</p>
      <details className="text-xs text-[var(--color-bo-ink-2)]">
        <summary className="cursor-pointer font-medium">{T.bridge.technical}</summary>
        <p className="mt-1">{T.bridge.technicalText}</p>
      </details>
      {error ? <p role="alert" className="rounded bg-red-50 p-2 text-sm text-red-800">{error}</p> : null}
      <QueryState
        query={query}
        service="ingestion"
        isEmpty={(d) => d.length === 0}
        emptyTitle={T.bridge.emptyTitle}
        emptyHint={isDemo ? T.bridge.emptyHint : T.bridge.emptyHintEnterprise}
        emptyAction={isDemo ? { label: T.bridge.emptyAction, href: "/backoffice/demo/console" } : undefined}
      >
        {(d) => <DataTable columns={columns} rows={d} rowKey={(m) => m.factType} />}
      </QueryState>
    </div>
  );
}
