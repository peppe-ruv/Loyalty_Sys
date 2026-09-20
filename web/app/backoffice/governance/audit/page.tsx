"use client";

import { useState } from "react";
import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { DiffView } from "@/components/bo/audit/DiffView";
import type { AuditPage, AuditRecord } from "@/lib/api/insight";

// BO-22 — Audit (docs/08 §BO-22): scritture da backoffice e job. Tabella + filtri; dettaglio con DiffView
// campo per campo e JSON grezzo. Override di ADMIN e RESET marcati. Dati: insight GET /v1/audit(/{id}).

const ACTIONS = ["", "CREATE", "UPDATE", "DELETE", "TRANSITION", "ADJUST", "JOB", "RESET"];
const SERVICES = ["", "member", "campaign", "wallet", "ingestion", "reward", "gamification", "engagement"];

const ACTION_TONE: Record<string, string> = {
  CREATE: "bg-emerald-100 text-emerald-800",
  UPDATE: "bg-blue-100 text-blue-800",
  DELETE: "bg-red-100 text-red-800",
  TRANSITION: "bg-violet-100 text-violet-800",
  ADJUST: "bg-amber-100 text-amber-800",
  JOB: "bg-slate-100 text-slate-700",
  RESET: "bg-rose-100 text-rose-800",
};

const ROLE_TONE: Record<string, string> = {
  ADMIN: "bg-indigo-100 text-indigo-800",
  MARKETING: "bg-teal-100 text-teal-800",
  LEGAL: "bg-amber-100 text-amber-800",
  CARE: "bg-sky-100 text-sky-800",
  ANALYST: "bg-slate-100 text-slate-700",
};

export default function AuditPage() {
  const [actor, setActor] = useState("");
  const [service, setService] = useState("");
  const [action, setAction] = useState("");
  const [selected, setSelected] = useState<AuditRecord | null>(null);

  const query = useLhQuery<AuditPage>(
    "insight",
    "/v1/audit",
    { actor: actor || undefined, service: service || undefined, action: action || undefined, limit: 100 },
    { refetchInterval: 5000 },
  );

  return (
    <div>
      <PageHeader
        title="Audit"
        subtitle="Ogni scrittura da backoffice e job: chi, cosa, quando e il diff campo per campo."
      />

      <div className="mb-3 flex flex-wrap items-end gap-2">
        <Field label="Attore">
          <input
            value={actor}
            onChange={(e) => setActor(e.target.value)}
            placeholder="nome o ruolo…"
            className="w-40 rounded-md border border-[var(--color-bo-border)] bg-white px-2 py-1 text-xs"
          />
        </Field>
        <Field label="Servizio">
          <Select value={service} onChange={setService} options={SERVICES} allLabel="Tutti i servizi" />
        </Field>
        <Field label="Azione">
          <Select value={action} onChange={setAction} options={ACTIONS} allLabel="Tutte le azioni" />
        </Field>
      </div>

      <div className="grid gap-4 lg:grid-cols-[3fr_2fr]">
        <QueryState query={query} service="insight" isEmpty={(d) => d.items.length === 0}
          emptyTitle="Nessuna voce di audit"
          emptyHint="Crea o modifica un membro/campagna dal backoffice: comparirà qui.">
          {(data) => (
            <div className="overflow-hidden rounded-md border border-[var(--color-bo-border)] bg-white">
              <table className="w-full text-xs">
                <thead className="bg-[var(--color-bo-bg)] text-left text-[var(--color-bo-ink-2)]">
                  <tr>
                    <th className="px-3 py-2">Quando</th>
                    <th className="px-3 py-2">Attore</th>
                    <th className="px-3 py-2">Servizio</th>
                    <th className="px-3 py-2">Azione</th>
                    <th className="px-3 py-2">Oggetto</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--color-bo-border)]">
                  {data.items.map((a) => (
                    <tr
                      key={a.id}
                      onClick={() => setSelected(a)}
                      className={`cursor-pointer hover:bg-[var(--color-bo-bg)] ${selected?.id === a.id ? "bg-[var(--color-bo-bg)]" : ""}`}
                    >
                      <td className="px-3 py-2 whitespace-nowrap">{formatDateTime(a.at)}</td>
                      <td className="px-3 py-2"><ActorStamp role={a.actorRole} name={a.actorName} /></td>
                      <td className="px-3 py-2 font-mono">{a.service}</td>
                      <td className="px-3 py-2"><ActionBadge action={a.action} /></td>
                      <td className="px-3 py-2">
                        <span className="font-mono text-[var(--color-bo-ink-2)]">{a.entityType}</span>{" "}
                        <CodeText>{a.entityId}</CodeText>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {data.total > data.items.length ? (
                <p className="px-3 py-2 text-[11px] text-[var(--color-bo-ink-2)]">
                  Mostrate {data.items.length} di {data.total} voci — affina i filtri per restringere.
                </p>
              ) : null}
            </div>
          )}
        </QueryState>

        <div>
          {selected == null ? (
            <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-6 text-center text-sm text-[var(--color-bo-ink-2)]">
              Seleziona una voce per vederne il dettaglio e il diff.
            </div>
          ) : (
            <Detail entry={selected} />
          )}
        </div>
      </div>
    </div>
  );
}

function Detail({ entry }: { entry: AuditRecord }) {
  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white p-4">
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-[var(--color-bo-ink)]">{entry.summary || "—"}</p>
          <p className="mt-0.5 text-xs text-[var(--color-bo-ink-2)]">
            {formatDateTime(entry.at)} · <span className="font-mono">{entry.service}</span> ·{" "}
            <span className="font-mono">{entry.entityType}</span> <CodeText>{entry.entityId}</CodeText>
          </p>
        </div>
        <ActionBadge action={entry.action} />
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
        <ActorStamp role={entry.actorRole} name={entry.actorName} />
        {entry.actorName === "system" ? (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] text-slate-600">sistema</span>
        ) : null}
        {entry.correlationId ? (
          <a
            href={`/backoffice/observe/traces?c=${entry.correlationId}`}
            className="text-[var(--color-bo-accent)] hover:underline"
          >
            Vedi tracciato →
          </a>
        ) : null}
      </div>

      <div className="mt-3 rounded-md border border-[var(--color-bo-border)] p-3">
        <p className="mb-2 text-xs font-medium text-[var(--color-bo-ink-2)]">Modifiche</p>
        <DiffView before={entry.before} after={entry.after} />
      </div>

      <details className="mt-2">
        <summary className="cursor-pointer text-xs text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]">
          JSON grezzo
        </summary>
        <pre className="mt-2 max-h-56 overflow-auto rounded-md bg-[var(--color-bo-bg)] p-2 text-[11px] leading-relaxed text-[var(--color-bo-ink)]">
          {JSON.stringify({ before: entry.before, after: entry.after }, null, 2)}
        </pre>
      </details>
    </div>
  );
}

function ActorStamp({ role, name }: { role: string | null; name: string | null }) {
  const tone = ROLE_TONE[role ?? ""] ?? "bg-slate-100 text-slate-700";
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${tone}`}>{role ?? "—"}</span>
      <span className="text-[var(--color-bo-ink)]">{name ?? "—"}</span>
    </span>
  );
}

function ActionBadge({ action }: { action: string }) {
  const tone = ACTION_TONE[action] ?? "bg-slate-100 text-slate-700";
  return <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${tone}`}>{action}</span>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[11px] text-[var(--color-bo-ink-2)]">{label}</span>
      {children}
    </label>
  );
}

function Select({
  value,
  onChange,
  options,
  allLabel,
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
  allLabel: string;
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded-md border border-[var(--color-bo-border)] bg-white px-2 py-1 text-xs"
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o === "" ? allLabel : o}
        </option>
      ))}
    </select>
  );
}
