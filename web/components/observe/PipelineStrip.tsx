"use client";

import { useLhQuery } from "@/lib/api/client";
import { familyColorVar, type LiveFamily } from "@/lib/realtime/sse";
import { formatRelative } from "@/lib/format/dates";

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
  const stats = new Map((query.data?.topics ?? []).map((t) => [t.topic, t]));

  return (
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
  );
}
