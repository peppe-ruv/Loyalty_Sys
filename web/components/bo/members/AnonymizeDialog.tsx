"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useLhMutation } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { anonymizeErrorMessage, canConfirmAnonymize, memberDisplayName } from "@/lib/member/anonymized";

// Anonimizza (BO-03, F-MBR-05, docs/08 §3 azioni irreversibili): riepilogo dell'impatto e digitazione dell'ID del
// membro. POST /v1/members/{id}/anonymize {confirm}. Esito comunicato dalla scheda (onDone); l'audit arriva nel rail.
export function AnonymizeDialog({
  member,
  onClose,
  onDone,
}: {
  member: MemberView;
  onClose: () => void;
  onDone: (updated: MemberView) => void;
}) {
  const qc = useQueryClient();
  const [typed, setTyped] = useState("");
  const anonymize = useLhMutation<MemberView, { confirm: string }>("member", "POST", () => `/v1/members/${member.id}/anonymize`, {
    onSuccess: (updated) => {
      // Gli altri servizi ripuliscono i loro dati poco dopo (via eventi): le loro schede si ricaricano.
      for (const service of ["reward", "gamification", "engagement", "insight", "ingestion"]) {
        qc.invalidateQueries({ queryKey: [service] });
      }
      onDone(updated);
    },
  });
  const ready = canConfirmAnonymize(typed, member.id);
  const error = anonymizeErrorMessage(anonymize.error);

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Anonimizza membro"
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-lg border border-red-200 bg-white p-5 shadow-lg"
      >
        <h2 className="text-base font-semibold text-red-800">Anonimizzare {memberDisplayName(member, member.id)}? ●</h2>
        <div className="mt-2 space-y-2 text-sm text-[var(--color-bo-ink)]">
          <p>
            <strong>Si cancellano</strong> in tutti i servizi: nome e cognome, e-mail, telefono, data di nascita, città, ID
            esterno, consensi, attributi personali, indirizzi di spedizione. Al loro posto comparirà «Membro anonimo».
          </p>
          <p>
            <strong>Restano</strong>: l&apos;ID {member.id}, movimenti e saldo, richieste premio, giocate, statistiche e segmenti.
          </p>
          <p className="text-red-700">
            Il membro non potrà più accumulare, spendere o giocare. <strong>Non è reversibile.</strong>
          </p>
        </div>
        <label className="mt-3 block text-xs text-[var(--color-bo-ink-2)]">
          Digita <strong className="font-mono text-[var(--color-bo-ink)]">{member.id}</strong> per confermare
          <input
            aria-label="ID del membro per confermare"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder={member.id}
            autoFocus
            className="mt-1 w-full rounded border border-red-300 px-2 py-1.5 font-mono text-sm"
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
            onClick={() => anonymize.mutate({ confirm: typed.trim() })}
            disabled={!ready || anonymize.isPending}
            className="rounded bg-red-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            {anonymize.isPending ? "Anonimizzo…" : "Anonimizza"}
          </button>
        </div>
      </div>
    </div>
  );
}
