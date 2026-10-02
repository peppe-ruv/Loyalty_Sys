"use client";

import { useEffect, useMemo, useState } from "react";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { it } from "@/lib/i18n/it";
import { formatRelative } from "@/lib/format/dates";
import { currencyLabel } from "@/lib/format/points";
import { memberDisplayName } from "@/lib/member/anonymized";
import { isTechOnly, outcomeChips, rootLabel } from "@/lib/observe/traceStory";
import type { Trace, TraceSummary } from "@/lib/observe/trace";
import { QueryState } from "@/components/bo/QueryState";
import { EmptyState, PageHeader } from "@/components/bo/primitives";
import { TraceStatusChip, TraceStoryView } from "@/components/observe/TraceStory";

// BO-25 Tracciati (docs/08 §BO-25, issue #204): elenco leggibile a sinistra, racconto del tracciato a destra
// (sintesi, KPI, «Passo per passo», «Perché questi punti», «Dettaglio tecnico» ripiegato).

const T = it.traces;
const STATUSES = ["COMPLETE", "IN_PROGRESS", "FAILED"] as const;

/** Chip dell'esito: PTS → «punti», STS → «status», le altre valute col codice. */
function currencyChip(code: string): string {
  return code === "PTS" || code === "STS" ? currencyLabel(code) : code;
}

export default function TracesPage() {
  const [selected, setSelected] = useState<string | null>(null);
  const [memberQ, setMemberQ] = useState("");
  const [actionQ, setActionQ] = useState("");
  const [statusQ, setStatusQ] = useState("");
  const [hideTech, setHideTech] = useState(true);

  // Deep-link da BO-22, BO-26, BO-27, BO-28 e richieste premio: /observe/traces?c=<correlationId> preseleziona il tracciato.
  useEffect(() => {
    const c = new URLSearchParams(window.location.search).get("c");
    if (c) setSelected(c);
  }, []);
  // Elenco paginato {items, page} (docs/06 §2); un tracciato sconosciuto è 404 (Q-318).
  const list = useLhQuery<Page<TraceSummary>>("insight", "/v1/traces", { size: 50 }, { refetchInterval: 5000 });
  const detail = useLhQuery<Trace>("insight", selected ? `/v1/traces/${encodeURIComponent(selected)}` : "/v1/traces", undefined, {
    enabled: selected != null,
    refetchInterval: selected ? 3000 : undefined,
  });
  // Nomi dei membri per l'elenco e la sintesi; se member non risponde restano gli ID.
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { size: 100 });
  const memberName = (id: string | null | undefined): string | null => {
    if (!id) return null;
    const m = members.data?.items.find((x) => x.id === id);
    return m ? memberDisplayName(m, id) : null;
  };

  const actionTypes = useMemo(
    () => Array.from(new Set((list.data?.items ?? []).map((t) => t.rootShortType))).sort(),
    [list.data],
  );

  const filter = (items: TraceSummary[]) => {
    const q = memberQ.trim().toLowerCase();
    return items.filter((t) => {
      if (hideTech && isTechOnly(t)) return false;
      if (actionQ && t.rootShortType !== actionQ) return false;
      if (statusQ && t.status !== statusQ) return false;
      if (q) {
        const hay = `${t.memberId ?? ""} ${memberName(t.memberId) ?? ""}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  };

  const fieldClass = "rounded border border-[var(--color-bo-ink-2)] bg-white px-2 py-1.5 text-sm";

  return (
    <div>
      <PageHeader title={T.title} subtitle={T.subtitle} />

      <div className="flex flex-wrap items-end gap-3 rounded-md border border-[var(--color-bo-border)] bg-white px-4 py-3">
        <label className="flex flex-col gap-1 text-xs font-medium">
          {T.filters.member}
          <input
            type="search"
            value={memberQ}
            onChange={(e) => setMemberQ(e.target.value)}
            placeholder={T.filters.memberPlaceholder}
            className={`${fieldClass} w-44 font-normal`}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium">
          {T.filters.action}
          <select value={actionQ} onChange={(e) => setActionQ(e.target.value)} className={`${fieldClass} font-normal`}>
            <option value="">{T.filters.allActions}</option>
            {actionTypes.map((a) => (
              <option key={a} value={a}>
                {rootLabel(a)}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium">
          {T.filters.status}
          <select value={statusQ} onChange={(e) => setStatusQ(e.target.value)} className={`${fieldClass} font-normal`}>
            <option value="">{T.filters.all}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {T.status[s]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-h-9 items-center gap-2 text-sm sm:ml-auto">
          <input
            type="checkbox"
            checked={hideTech}
            onChange={(e) => setHideTech(e.target.checked)}
            className="size-4 accent-[var(--color-bo-accent)]"
          />
          {T.filters.hideTech}
        </label>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-12">
        <section aria-label={T.list.label} className="min-w-0 lg:col-span-5">
          <QueryState
            query={list}
            service="insight"
            isEmpty={(d) => d.items.length === 0}
            emptyTitle={T.list.empty}
            emptyHint={T.list.emptyHint}
          >
            {(data) => {
              const rows = filter(data.items);
              if (rows.length === 0) return <EmptyState title={T.list.noMatch} />;
              return (
                <div className="overflow-hidden rounded-md border border-[var(--color-bo-border)] bg-white">
                  <p className="flex items-center justify-between bg-[var(--color-bo-bg)] px-4 py-2 text-xs font-medium">
                    <span>{T.list.count(rows.length)}</span>
                    <span className="font-normal text-[var(--color-bo-ink-2)]">{T.list.autoRefresh}</span>
                  </p>
                  <ul className="divide-y divide-[var(--color-bo-border)]">
                    {rows.map((t) => {
                      const active = selected === t.correlationId;
                      const name = memberName(t.memberId);
                      return (
                        <li key={t.correlationId}>
                          <button
                            type="button"
                            aria-pressed={active}
                            onClick={() => setSelected(t.correlationId)}
                            className={`block w-full px-4 py-3 text-left hover:bg-[var(--color-bo-bg)] ${
                              active ? "bg-[var(--color-bo-bg)] shadow-[inset_4px_0_0_var(--color-bo-accent)]" : ""
                            }`}
                          >
                            <span className="flex items-start gap-3">
                              <span className="min-w-0 flex-1">
                                <span className="block font-medium text-[var(--color-bo-ink)]">{rootLabel(t.rootShortType)}</span>
                                <span className="block text-xs text-[var(--color-bo-ink-2)]">
                                  {name ?? t.memberId ?? "—"}
                                  {name && t.memberId ? <span className="font-mono"> · {t.memberId}</span> : null}
                                  {t.startedAt ? ` · ${formatRelative(t.startedAt)}` : ""}
                                </span>
                              </span>
                              <TraceStatusChip status={t.status} />
                            </span>
                            <span className="mt-2 flex flex-wrap gap-1.5">
                              {outcomeChips(t, currencyChip).map((c) => (
                                <span
                                  key={c.text}
                                  className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs ${
                                    c.tone === "good" ? "bg-emerald-100 text-emerald-900" : "bg-slate-100 text-slate-800"
                                  }`}
                                >
                                  {c.text}
                                </span>
                              ))}
                            </span>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              );
            }}
          </QueryState>
        </section>

        <section aria-label={T.detail.label} className="min-w-0 lg:col-span-7">
          {selected == null ? (
            <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-6 text-center text-sm text-[var(--color-bo-ink-2)]">
              {T.detail.choose}
            </div>
          ) : (
            <QueryState query={detail} service="insight">
              {(t) => <TraceStoryView trace={t} memberName={memberName(t.memberId)} />}
            </QueryState>
          )}
        </section>
      </div>
    </div>
  );
}
