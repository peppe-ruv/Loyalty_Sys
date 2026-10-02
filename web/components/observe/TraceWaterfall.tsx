"use client";

import { familyColorVar } from "@/lib/realtime/sse";
import { it } from "@/lib/i18n/it";
import { eventLabel } from "@/lib/observe/eventLabels";
import { formatSecondsShort } from "@/lib/format/duration";
import type { Trace, TraceNode } from "@/lib/observe/trace";

export type { Trace, TraceNode, TraceOutcome } from "@/lib/observe/trace";

// Cascata tecnica del tracciato (docs/08 §BO-25, «Dettaglio tecnico»): una corsia per servizio, un marcatore a punto
// di larghezza fissa per nodo, posizionato per offsetMs su un asse in secondi. Nessuna etichetta inline (issue #204:
// con 16 nodi in pochi secondi le etichette si coprivano): codice, servizio e tempo stanno nel nome accessibile e nel
// tooltip; il clic seleziona il nodo e il chiamante ne mostra il contenuto.

export function TraceWaterfall({
  trace,
  selectedId,
  onSelect,
}: {
  trace: Trace;
  selectedId?: string | null;
  onSelect?: (node: TraceNode) => void;
}) {
  const lanes = Array.from(new Set(trace.nodes.map((n) => n.service)));
  const maxOffset = Math.max(1, trace.durationMs, ...trace.nodes.map((n) => n.offsetMs));
  const seconds = Math.max(1, Math.ceil(maxOffset / 1000));
  const ticks = Array.from({ length: seconds + 1 }, (_, i) => i);
  const pct = (offsetMs: number) => Math.min(100, Math.max(0, (offsetMs / (seconds * 1000)) * 100));

  return (
    <div className="space-y-1.5" role="group" aria-label={it.traces.tech.title}>
      {lanes.map((lane) => (
        <div key={lane} className="grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-2">
          <span className="truncate text-right font-mono text-[11px] text-[var(--color-bo-ink-2)]" title={lane}>
            {lane}
          </span>
          <div className="relative h-7 rounded bg-[var(--color-bo-bg)]">
            {trace.nodes
              .filter((n) => n.service === lane)
              .map((n) => {
                const label = it.traces.tech.marker(eventLabel(n.family, n.shortType), n.shortType, formatSecondsShort(n.offsetMs));
                const selected = selectedId === n.eventId;
                return (
                  <button
                    key={n.eventId}
                    type="button"
                    data-testid="trace-marker"
                    aria-label={label}
                    aria-pressed={selected}
                    title={label}
                    onClick={() => onSelect?.(n)}
                    className={`absolute top-1/2 size-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 p-0 ${
                      selected ? "border-[var(--color-bo-ink)] ring-2 ring-[var(--color-bo-ink)]" : n.family === "DLQ" ? "border-red-700" : "border-white"
                    }`}
                    style={{ left: `${pct(n.offsetMs)}%`, backgroundColor: familyColorVar(n.family) }}
                  />
                );
              })}
          </div>
        </div>
      ))}
      <div className="grid grid-cols-[6rem_minmax(0,1fr)] gap-2" aria-hidden>
        <span />
        <div className="relative h-4 font-mono text-[10px] text-[var(--color-bo-ink-2)]">
          {ticks.map((s) => (
            <span key={s} className="absolute -translate-x-1/2" style={{ left: `${pct(s * 1000)}%` }}>
              {s} s
            </span>
          ))}
        </div>
      </div>
      <p className="sr-only">{it.traces.tech.axis}</p>
    </div>
  );
}
