"use client";

import { useState, type ReactNode } from "react";
import { useLhMutation, type LhError } from "@/lib/api/client";
import type { Capability } from "@/lib/persona/permissions";
import type { ServiceCode } from "@/lib/api/services";
import { StatusPill } from "./primitives";
import { Can } from "./Can";

// Barra del ciclo di vita (docs/08 §3.3): solo le transizioni valide per lo stato, macchina a stati comune (docs/06 §7)
// per campagne, premi e concorsi. M7.1: con approvazione richiesta dalla policy DRAFT offre solo *Invia in revisione*
// (senza policy nota, entrambe le vie: il servizio risponde 409 APPROVAL_REQUIRED); IN_REVIEW si approva o si rifiuta
// con commento (`object.approve`: LEGAL, ADMIN), come nella coda di BO-21.
const TRANSITIONS: Record<string, { action: string; label: string; capability?: Capability }[]> = {
  DRAFT: [
    { action: "SUBMIT", label: "Invia in revisione" },
    { action: "PUBLISH", label: "Pubblica" },
    { action: "ARCHIVE", label: "Archivia" },
  ],
  IN_REVIEW: [
    { action: "APPROVE", label: "Approva", capability: "object.approve" },
    { action: "REJECT", label: "Rifiuta…", capability: "object.approve" },
  ],
  APPROVED: [{ action: "PUBLISH", label: "Pubblica" }],
  LIVE: [{ action: "PAUSE", label: "Metti in pausa" }, { action: "END", label: "Termina" }],
  PAUSED: [{ action: "RESUME", label: "Riprendi" }, { action: "END", label: "Termina" }],
  ENDED: [{ action: "ARCHIVE", label: "Archivia" }],
};

export function LifecycleBar({
  service,
  transitionsPath,
  status,
  system = false,
  approvalRequired,
  onChanged,
  renderError,
}: {
  service: ServiceCode;
  /** Es. `/v1/campaigns/{id}/transitions`. */
  transitionsPath: string;
  status: string;
  system?: boolean;
  /** Dalla policy (docs/06 §7): `true` nasconde *Pubblica* da DRAFT, `false` nasconde *Invia in revisione*. */
  approvalRequired?: boolean;
  onChanged: () => void;
  /** Resa personalizzata di un errore (es. `INSTANTS_NOT_GENERATED` con il collegamento alla scheda istanti). */
  renderError?: (error: LhError) => ReactNode;
}) {
  const [error, setError] = useState<LhError | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [comment, setComment] = useState("");
  const mutation = useLhMutation<unknown, { action: string; comment?: string }>(
    service,
    "POST",
    () => transitionsPath,
    { onSuccess: () => { setError(null); setRejecting(false); setComment(""); onChanged(); } },
  );

  const available = (TRANSITIONS[status] ?? []).filter(
    (t) =>
      !(system && t.action === "ARCHIVE") &&
      !(status === "DRAFT" && t.action === "PUBLISH" && approvalRequired === true) &&
      !(status === "DRAFT" && t.action === "SUBMIT" && approvalRequired === false),
  );

  return (
    <div className="flex flex-wrap items-center gap-2">
      <StatusPill status={status} />
      {system ? <span className="text-xs text-slate-400">🔒 sistema</span> : null}
      <div className="flex gap-2">
        {available.map((t) => (
          <Can key={t.action} capability={t.capability ?? "object.edit"} mode="disable">
            <button
              onClick={() =>
                t.action === "REJECT"
                  ? setRejecting(true)
                  : mutation.mutate({ action: t.action }, { onError: setError })
              }
              disabled={mutation.isPending}
              className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50 disabled:opacity-50"
            >
              {t.label}
            </button>
          </Can>
        ))}
      </div>
      {rejecting ? (
        <form
          className="flex w-full flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            mutation.mutate({ action: "REJECT", comment }, { onError: setError });
          }}
        >
          <input
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="Motivo del rifiuto (obbligatorio)"
            aria-label="Motivo del rifiuto"
            className="min-w-64 flex-1 rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm"
          />
          <button
            type="submit"
            disabled={!comment.trim() || mutation.isPending}
            className="rounded bg-red-700 px-2.5 py-1 text-sm font-medium text-white disabled:opacity-50"
          >
            Rifiuta
          </button>
          <button type="button" onClick={() => setRejecting(false)} className="text-sm underline">
            Annulla
          </button>
        </form>
      ) : null}
      {error ? (
        <span className="text-xs text-red-700" role="alert">
          {renderError?.(error) ??
            (error.code === "APPROVAL_REQUIRED"
              ? "Serve l'approvazione: usa «Invia in revisione»."
              : error.detail || error.code)}
        </span>
      ) : null}
    </div>
  );
}
