"use client";

import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { formatDateTime } from "@/lib/format/dates";
import { formatActor } from "@/lib/approvals/queue";
import { HISTORY_SOURCES, type ApprovalHistoryRow, type HistoryEntityType } from "@/lib/approvals/types";

const ACTION_LABEL: Record<string, string> = {
  SUBMIT: "Inviato in revisione",
  APPROVE: "Approvato",
  REJECT: "Rifiutato",
  PUBLISH: "Pubblicato",
  PAUSE: "Messo in pausa",
  RESUME: "Ripreso",
  END: "Terminato",
  ARCHIVE: "Archiviato",
};

/** Storico delle transizioni di un oggetto governato (docs/03 §3.6: chi, quando, commento), dal più recente. */
export function ApprovalHistoryList({ entityType, id }: { entityType: HistoryEntityType; id: string }) {
  const src = HISTORY_SOURCES[entityType];
  const query = useLhQuery<ApprovalHistoryRow[]>(src.service, `/v1/${src.resource}/${id}/approval-history`);
  return (
    <QueryState query={query} service={src.service} isEmpty={(d) => d.length === 0} emptyTitle="Nessuna transizione registrata">
      {(rows) => (
        <ol className="space-y-2 border-l border-[var(--color-bo-border)] pl-3">
          {rows.map((h) => (
            <li key={h.id} className="text-sm">
              <p>
                <span className="font-medium">{ACTION_LABEL[h.action] ?? h.action}</span>{" "}
                <span className="text-[var(--color-bo-ink-2)]">
                  · {formatActor(h.actor)} · {formatDateTime(h.createdAt)}
                </span>
              </p>
              <p className="text-xs text-[var(--color-bo-ink-2)]">
                {h.fromStatus ?? "—"} → {h.toStatus}
              </p>
              {h.comment ? <p className="mt-0.5 rounded bg-[var(--color-bo-bg)] px-2 py-1 text-xs">«{h.comment}»</p> : null}
            </li>
          ))}
        </ol>
      )}
    </QueryState>
  );
}
