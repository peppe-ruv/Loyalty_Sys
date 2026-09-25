"use client";

import { useEffect, useState } from "react";
import { useLhQuery, type Page } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { TraceWaterfall, type Trace } from "@/components/observe/TraceWaterfall";

// BO-25 Tracciati (docs/08 §BO-25): elenco dei giri azione→esito e cascata per servizio.
interface TraceSummary {
  correlationId: string;
  memberId: string | null;
  rootShortType: string;
  startedAt: string | null;
  durationMs: number;
  status: string;
  outcomeSummary: string;
}

const STATUS_CLASS: Record<string, string> = {
  COMPLETE: "bg-emerald-100 text-emerald-800",
  IN_PROGRESS: "bg-amber-100 text-amber-800",
  FAILED: "bg-red-100 text-red-800",
};

export default function TracesPage() {
  const [selected, setSelected] = useState<string | null>(null);

  // Deep-link da BO-22 (audit): /observe/traces?c=<correlationId> preseleziona il tracciato.
  useEffect(() => {
    const c = new URLSearchParams(window.location.search).get("c");
    if (c) setSelected(c);
  }, []);
  // Elenco paginato {items, page} (docs/06 §2); un tracciato sconosciuto è 404 (Q-318).
  const list = useLhQuery<Page<TraceSummary>>("insight", "/v1/traces", { size: 50 }, {
    refetchInterval: 5000,
  });
  const detail = useLhQuery<Trace>("insight", selected ? `/v1/traces/${selected}` : "/v1/traces", undefined, {
    enabled: selected != null,
    refetchInterval: selected ? 3000 : undefined,
  });

  return (
    <div>
      <PageHeader
        title="Tracciati"
        subtitle="Ogni giro azione → valutazione → effetti → fatti, con i tempi e l'esito. Clic su una riga per la cascata."
      />
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <QueryState query={list} service="insight" isEmpty={(d) => d.items.length === 0}
          emptyTitle="Nessun tracciato" emptyHint="Invia un'azione dal simulatore o dal portale.">
          {(data) => (
            <div className="overflow-hidden rounded-md border border-[var(--color-bo-border)] bg-white">
              <table className="w-full text-xs">
                <thead className="bg-[var(--color-bo-bg)] text-left text-[var(--color-bo-ink-2)]">
                  <tr>
                    <th className="px-3 py-2">Quando</th>
                    <th className="px-3 py-2">Membro</th>
                    <th className="px-3 py-2">Azione</th>
                    <th className="px-3 py-2">Esito</th>
                    <th className="px-3 py-2">Stato</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--color-bo-border)]">
                  {data.items.map((t) => (
                    <tr
                      key={t.correlationId}
                      onClick={() => setSelected(t.correlationId)}
                      className={`cursor-pointer hover:bg-[var(--color-bo-bg)] ${selected === t.correlationId ? "bg-[var(--color-bo-bg)]" : ""}`}
                    >
                      <td className="px-3 py-2">{t.startedAt ? formatDateTime(t.startedAt) : "—"}</td>
                      <td className="px-3 py-2">{t.memberId ? <CodeText>{t.memberId}</CodeText> : "—"}</td>
                      <td className="px-3 py-2 font-mono">{t.rootShortType}</td>
                      <td className="px-3 py-2">{t.outcomeSummary || "—"}</td>
                      <td className="px-3 py-2">
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${STATUS_CLASS[t.status] ?? "bg-slate-200"}`}>
                          {t.status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </QueryState>

        <div>
          {selected == null ? (
            <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-6 text-center text-sm text-[var(--color-bo-ink-2)]">
              Seleziona un tracciato per vederne la cascata.
            </div>
          ) : (
            <QueryState query={detail} service="insight">
              {(t) => <TraceWaterfall trace={t} />}
            </QueryState>
          )}
        </div>
      </div>
    </div>
  );
}
