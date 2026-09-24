"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useLhMutation } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { memberDisplayName } from "@/lib/member/anonymized";
import {
  statusChangeBody,
  statusChangeErrorMessage,
  type MemberStatusAction,
  type MemberStatusTarget,
} from "@/lib/member/status";

// Blocca / Sblocca / Disattiva (BO-03, F-MBR-04): azione reversibile → conferma semplice (docs/08 §3.5), motivo
// facoltativo. POST /v1/members/{id}/status {status, reason}. Esito comunicato dalla scheda (onDone); l'audit e il fatto
// member.status.changed arrivano nel rail.
export function StatusChangeDialog({
  member,
  action,
  onClose,
  onDone,
}: {
  member: MemberView;
  action: MemberStatusAction;
  onClose: () => void;
  onDone: (updated: MemberView) => void;
}) {
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const change = useLhMutation<MemberView, { status: MemberStatusTarget; reason?: string }>(
    "member",
    "POST",
    () => `/v1/members/${member.id}/status`,
    {
      onSuccess: (updated) => {
        // Il wallet e i servizi di gioco allineano lo stato via evento: le loro schede si ricaricano.
        for (const service of ["wallet", "reward", "gamification"]) {
          qc.invalidateQueries({ queryKey: [service] });
        }
        onDone(updated);
      },
    },
  );
  const error = statusChangeErrorMessage(change.error);

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={action.confirmTitle}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-lg border border-[var(--color-bo-border)] bg-white p-5 shadow-lg"
      >
        <h2 className="text-base font-semibold text-[var(--color-bo-ink)]">{action.confirmTitle}</h2>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          {memberDisplayName(member, member.id)} · <span className="font-mono">{member.id}</span> · stato attuale {member.status}
        </p>
        <p className="mt-3 text-sm text-[var(--color-bo-ink)]">{action.confirmBody}</p>
        <label className="mt-3 block text-xs text-[var(--color-bo-ink-2)]">
          Motivo (facoltativo, finisce nell&apos;audit)
          <input
            aria-label="Motivo del cambio di stato"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={200}
            autoFocus
            className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
          />
        </label>
        {error ? (
          <p role="alert" className="mt-2 text-xs text-red-700">
            {error}
          </p>
        ) : null}
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
            Annulla
          </button>
          <button
            onClick={() => change.mutate(statusChangeBody(action.target, reason))}
            disabled={change.isPending}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            {change.isPending ? "Salvataggio…" : action.label}
          </button>
        </div>
      </div>
    </div>
  );
}
