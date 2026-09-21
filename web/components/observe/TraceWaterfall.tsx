"use client";

import { familyColorVar, type LiveFamily } from "@/lib/realtime/sse";
import { TopicDot } from "./TopicDot";

// Cascata del tracciato (docs/08 §BO-25): una corsia per servizio, nodi posizionati per offsetMs, colorati
// per famiglia; sotto, il pannello esito. Versione M2.3: nodi e tempi; il payload per nodo arriva più avanti.
export interface TraceNode {
  eventId: string;
  family: LiveFamily;
  shortType: string;
  service: string;
  time?: string | null;
  offsetMs: number;
  parentEventId?: string | null;
  summary: string;
}

export interface TraceOutcome {
  points: { currency: string; amount: number }[];
  tierChange?: { from?: string | null; to?: string | null } | null;
  messages: number;
  coupons: number;
  plays: number;
  dlq: number;
}

export interface Trace {
  correlationId: string;
  memberId?: string | null;
  startedAt?: string | null;
  durationMs: number;
  status: string;
  nodes: TraceNode[];
  outcome: TraceOutcome;
}

const STATUS_CLASS: Record<string, string> = {
  COMPLETE: "bg-emerald-100 text-emerald-800",
  IN_PROGRESS: "bg-amber-100 text-amber-800",
  FAILED: "bg-red-100 text-red-800",
};

export function TraceWaterfall({ trace }: { trace: Trace }) {
  const lanes = Array.from(new Set(trace.nodes.map((n) => n.service)));
  const maxOffset = Math.max(1, ...trace.nodes.map((n) => n.offsetMs));

  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${STATUS_CLASS[trace.status] ?? "bg-slate-200"}`}>
          {trace.status}
        </span>
        <span className="text-xs text-[var(--color-bo-ink-2)]">durata {trace.durationMs} ms</span>
        {trace.memberId ? <span className="font-mono text-xs">{trace.memberId}</span> : null}
      </div>

      <div className="mt-4 space-y-2">
        {lanes.map((lane) => {
          const laneNodes = trace.nodes.filter((n) => n.service === lane);
          return (
            <div key={lane} className="flex items-center gap-3">
              <span className="w-24 shrink-0 text-right text-[11px] font-medium text-[var(--color-bo-ink-2)]">
                {lane}
              </span>
              <div className="relative h-8 flex-1 rounded bg-[var(--color-bo-bg)]">
                {laneNodes.map((n) => (
                  <span
                    key={n.eventId}
                    title={`${n.shortType} · ${n.offsetMs} ms\n${n.summary}`}
                    className="absolute top-1 flex -translate-x-1/2 items-center gap-1 whitespace-nowrap rounded border border-[var(--color-bo-border)] bg-white px-1.5 py-0.5 text-[10px] shadow-sm"
                    style={{ left: `${Math.min(96, (n.offsetMs / maxOffset) * 92 + 2)}%` }}
                  >
                    <TopicDot family={n.family} />
                    {n.shortType}
                  </span>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <div className="mt-4 border-t border-[var(--color-bo-border)] pt-3">
        <p className="text-xs font-semibold text-[var(--color-bo-ink)]">Esito</p>
        <div className="mt-1 flex flex-wrap gap-2 text-xs">
          {trace.outcome.points.length === 0 && !trace.outcome.tierChange && trace.outcome.dlq === 0 ? (
            <span className="text-[var(--color-bo-ink-2)]">nessun effetto registrato</span>
          ) : (
            <>
              {trace.outcome.points.map((p) => (
                <span key={p.currency} className="rounded bg-emerald-50 px-2 py-0.5 font-medium text-emerald-800">
                  +{p.amount} {p.currency}
                </span>
              ))}
              {trace.outcome.tierChange?.to ? (
                <span className="rounded bg-indigo-50 px-2 py-0.5 font-medium text-indigo-800">
                  livello → {trace.outcome.tierChange.to}
                </span>
              ) : null}
              {trace.outcome.dlq > 0 ? (
                <span className="rounded bg-red-50 px-2 py-0.5 font-medium text-red-800">{trace.outcome.dlq} DLQ</span>
              ) : null}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
