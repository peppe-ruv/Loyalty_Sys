"use client";

import { useLhQuery } from "@/lib/api/client";
import { familyColorVar, type LiveFamily } from "@/lib/realtime/sse";
import { formatRelative } from "@/lib/format/dates";
import { DegradedBox } from "@/components/shared/QueryState";

// Striscia pipeline di BO-24 (docs/08 §BO-24): una tessera per topic con ultimo evento e volume.
const TOPICS: { topic: string; label: string; family: LiveFamily }[] = [
  { topic: "lh.actions.v1", label: "Azioni", family: "ACTION" },
  { topic: "lh.effects.v1", label: "Effetti", family: "EFFECT" },
  { topic: "lh.facts.v1", label: "Fatti", family: "FACT" },
  { topic: "lh.audit.v1", label: "Audit", family: "AUDIT" },
  { topic: "lh.dlq.v1", label: "DLQ", family: "DLQ" },
];

interface TopicStat {
  topic: string;
  lastEventAt: string | null;
  countTotal: number;
}

interface PipelineStatus {
  topics: TopicStat[];
}

export function PipelineStrip() {
  const query = useLhQuery<PipelineStatus>("insight", "/v1/pipeline/status", undefined, {
    refetchInterval: 5000,
  });
  // Stati (docs/07 §6): scheletro delle 5 tessere al primo caricamento; insight addormentato → riquadro ambra (il flusso
  // sotto resta usabile); altro errore → riquadro con Riprova. Con dati già arrivati un errore di aggiornamento li
  // lascia visibili con una nota, invece di svuotare la striscia.
  if (query.isLoading) {
    return (
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-5" aria-busy="true" aria-label="Stato della pipeline">
        {TOPICS.map((t) => (
          <div key={t.topic} className="h-[62px] animate-pulse rounded-md bg-slate-100" />
        ))}
      </div>
    );
  }
  if (query.isError && !query.data) {
    return query.error.asleep ? (
      <DegradedBox service="insight" onRetry={() => query.refetch()} />
    ) : (
      <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        <p className="font-medium">Stato della pipeline non disponibile: {query.error.code}</p>
        <p className="mt-1 text-xs">{query.error.detail}</p>
        <button onClick={() => query.refetch()} className="mt-2 rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100">
          Riprova
        </button>
      </div>
    );
  }
  const stats = new Map((query.data?.topics ?? []).map((t) => [t.topic, t]));

  return (
    <div>
      {query.isError ? (
        <p className="mb-1 text-xs text-amber-700">Aggiornamento non riuscito: valori dell&apos;ultima lettura riuscita.</p>
      ) : null}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-5">
        {TOPICS.map((t) => {
          const stat = stats.get(t.topic);
          return (
            <div
              key={t.topic}
              className="rounded-md border border-[var(--color-bo-border)] bg-white p-3"
              style={{ borderTopColor: familyColorVar(t.family), borderTopWidth: 3 }}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-[var(--color-bo-ink)]">{t.label}</span>
                <span className="font-mono text-sm">{stat?.countTotal ?? 0}</span>
              </div>
              <p className="mt-1 text-[11px] text-[var(--color-bo-ink-2)]">
                {stat?.lastEventAt ? formatRelative(stat.lastEventAt) : "nessun evento"}
              </p>
            </div>
          );
        })}
      </div>
    </div>
  );
}
