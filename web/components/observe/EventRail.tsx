"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useLiveEvents } from "@/lib/realtime/useLiveEvents";
import { liveFeedView, type ConnectionState, type LiveFamily } from "@/lib/realtime/sse";
import { TopicDot } from "./TopicDot";
import { CodeText } from "@/components/bo/primitives";
import { formatTime } from "@/lib/format/dates";

// Flusso eventi a righe di BO-24 (docs/08 §BO-24). Passando su una riga si evidenziano quelle con lo
// stesso correlationId. Pausa con contatore dei nuovi; buffer 500 righe (nel hook).
const FAMILIES: { value: string; label: string; topic?: string }[] = [
  { value: "", label: "Tutte" },
  { value: "ACTION", label: "Azioni", topic: "lh.actions.v1" },
  { value: "EFFECT", label: "Effetti", topic: "lh.effects.v1" },
  { value: "FACT", label: "Fatti", topic: "lh.facts.v1" },
  { value: "AUDIT", label: "Audit", topic: "lh.audit.v1" },
  { value: "DLQ", label: "DLQ", topic: "lh.dlq.v1" },
];

const CONNECTION: Record<ConnectionState, { label: string; className: string }> = {
  connecting: { label: "connessione…", className: "bg-slate-100 text-slate-600" },
  live: { label: "live", className: "bg-emerald-100 text-emerald-800" },
  reduced: { label: "live ridotto", className: "bg-amber-100 text-amber-800" },
  disconnected: { label: "disconnesso", className: "bg-slate-200 text-slate-700" },
};

export function EventRail() {
  const [family, setFamily] = useState("");
  const [memberId, setMemberId] = useState("");
  const [hovered, setHovered] = useState<string | null>(null);

  const filters = useMemo(() => {
    const topicOf = FAMILIES.find((f) => f.value === family)?.topic;
    return { topics: topicOf ? [topicOf] : undefined, memberId: memberId.trim() || undefined };
  }, [family, memberId]);

  const { events, state, paused, pendingCount, pause, resume, clear } = useLiveEvents(filters);
  const conn = CONNECTION[state];
  const view = liveFeedView(state, events.length);

  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white">
      <div className="flex flex-wrap items-center gap-2 border-b border-[var(--color-bo-border)] p-2">
        <select
          value={family}
          onChange={(e) => setFamily(e.target.value)}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
        >
          {FAMILIES.map((f) => (
            <option key={f.value} value={f.value}>{f.label}</option>
          ))}
        </select>
        <input
          value={memberId}
          onChange={(e) => setMemberId(e.target.value)}
          placeholder="Membro (MBR-…)"
          className="w-36 rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
        />
        <button
          onClick={paused ? resume : pause}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs hover:bg-[var(--color-bo-bg)]"
        >
          {paused ? `Riprendi${pendingCount > 0 ? ` (${pendingCount} nuovi)` : ""}` : "Pausa"}
        </button>
        <button
          onClick={clear}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs hover:bg-[var(--color-bo-bg)]"
        >
          Pulisci
        </button>
        <span className={`ml-auto rounded-full px-2 py-0.5 text-[11px] font-medium ${conn.className}`}>
          ● {conn.label}
        </span>
      </div>

      {view === "loading" ? (
        <div className="space-y-2 p-3" aria-busy="true" aria-label="Collegamento al flusso eventi">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-6 animate-pulse rounded bg-slate-100" />
          ))}
        </div>
      ) : view === "degraded" ? (
        <div role="status" className="m-3 rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800">
          <p className="font-medium">Servizio «insight» non raggiungibile</p>
          <p className="mt-1 text-xs">
            Probabilmente si sta svegliando (piano gratuito). Il flusso si ricollega da solo e, se lo stream non riparte,
            passa al polling ogni 3 s: accendi la demo dal Demo Hub se resta disconnesso.
          </p>
        </div>
      ) : view === "empty" ? (
        <p className="p-6 text-center text-sm text-[var(--color-bo-ink-2)]">
          In attesa di eventi… invia un&apos;azione dal{" "}
          <Link href="/backoffice/demo/simulator" className="text-[var(--color-bo-accent)] hover:underline">
            simulatore
          </Link>{" "}
          o dal portale.
        </p>
      ) : (
        <ul className="max-h-[60vh] divide-y divide-[var(--color-bo-border)] overflow-y-auto">
          {events.slice(0, 200).map((ev) => {
            const highlight = hovered != null && ev.correlationId === hovered;
            return (
              <li
                key={ev.eventId}
                onMouseEnter={() => setHovered(ev.correlationId ?? null)}
                onMouseLeave={() => setHovered(null)}
                className={`flex items-center gap-2 px-3 py-1.5 text-xs ${highlight ? "bg-[var(--color-bo-bg)]" : ""}`}
              >
                <span className="w-16 font-mono text-[var(--color-bo-ink-2)]">
                  {ev.time ? formatTime(ev.time) : "—"}
                </span>
                <TopicDot family={ev.family as LiveFamily} />
                <span className="w-40 truncate font-mono" title={ev.shortType}>{ev.shortType}</span>
                <span className="w-24">{ev.memberId ? <CodeText>{ev.memberId}</CodeText> : "—"}</span>
                <span className="flex-1 truncate text-[var(--color-bo-ink)]">{ev.summary}</span>
                <span className="font-mono text-[10px] text-[var(--color-bo-ink-2)]" title={ev.correlationId ?? ""}>
                  {ev.correlationId ? ev.correlationId.slice(-6) : "—"}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
