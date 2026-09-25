"use client";

import { useState, type ReactNode } from "react";
import { useLhMutation, type LhError } from "@/lib/api/client";
import type { Capability } from "@/lib/persona/permissions";
import type { ServiceCode } from "@/lib/api/services";
import { formatElapsed } from "@/lib/format/dates";
import { StatusPill } from "./primitives";
import { Can, useCan } from "./Can";

// Barra del ciclo di vita (docs/08 §3.3): solo le transizioni valide per lo stato, macchina a stati comune (docs/06 §7)
// per campagne, premi e concorsi. M7.1: con approvazione richiesta dalla policy DRAFT offre solo *Invia in revisione*
// (senza policy nota, entrambe le vie: il servizio risponde 409 APPROVAL_REQUIRED); IN_REVIEW si approva o si rifiuta
// con commento (`object.approve`: LEGAL, ADMIN), come nella coda di BO-21; chi non può decidere legge "In attesa di
// LEGAL da 2 h". Ogni transizione apre un dialogo con commento → `POST …/transitions {action, comment}` (commento
// obbligatorio solo per il rifiuto).
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

type Transition = (typeof TRANSITIONS)[string][number];

export function LifecycleBar({
  service,
  transitionsPath,
  status,
  system = false,
  approvalRequired,
  requiredRole,
  submittedAt,
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
  /** IN_REVIEW: ruolo che deve decidere (voce della coda approvazioni, docs/06 §7); assente = LEGAL, come in BO-21. */
  requiredRole?: string | null;
  /** IN_REVIEW: quando l'oggetto è stato inviato in revisione (per "In attesa di LEGAL da 2 h"). */
  submittedAt?: string | null;
  onChanged: () => void;
  /** Resa personalizzata di un errore (es. `INSTANTS_NOT_GENERATED` con il collegamento alla scheda istanti). */
  renderError?: (error: LhError) => ReactNode;
}) {
  const [error, setError] = useState<LhError | null>(null);
  const [open, setOpen] = useState<Transition | null>(null);
  const [comment, setComment] = useState("");
  const canDecide = useCan("object.approve");
  const mutation = useLhMutation<unknown, { action: string; comment?: string }>(
    service,
    "POST",
    () => transitionsPath,
    { onSuccess: () => { setError(null); close(); onChanged(); } },
  );

  function close() {
    setOpen(null);
    setComment("");
  }

  function confirm(t: Transition) {
    const text = comment.trim();
    mutation.mutate(text ? { action: t.action, comment: text } : { action: t.action }, {
      onError: (e) => {
        setError(e);
        close();
      },
    });
  }

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
              onClick={() => {
                setError(null);
                setComment("");
                setOpen(t);
              }}
              disabled={mutation.isPending}
              className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50 disabled:opacity-50"
            >
              {t.label}
            </button>
          </Can>
        ))}
      </div>
      {status === "IN_REVIEW" && !canDecide ? (
        <span className="text-xs text-[var(--color-bo-ink-2)]">
          In attesa di {requiredRole || "LEGAL"}
          {submittedAt ? ` da ${formatElapsed(submittedAt)}` : ""}
        </span>
      ) : null}
      {open ? (
        <TransitionDialog
          transition={open}
          comment={comment}
          onComment={setComment}
          busy={mutation.isPending}
          onConfirm={() => confirm(open)}
          onCancel={close}
        />
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

/** Dialogo di conferma con commento (docs/08 §3.3); per il rifiuto il commento è obbligatorio (docs/03 §3.6). */
function TransitionDialog({
  transition,
  comment,
  onComment,
  busy,
  onConfirm,
  onCancel,
}: {
  transition: Transition;
  comment: string;
  onComment: (value: string) => void;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const reject = transition.action === "REJECT";
  const title = transition.label.replace(/…$/, "");
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30 p-4" onClick={onCancel}>
      <form
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onConfirm();
        }}
        className="w-full max-w-md space-y-3 rounded-lg border border-[var(--color-bo-border)] bg-white p-5 shadow-lg"
      >
        <h2 className="text-base font-semibold text-[var(--color-bo-ink)]">{title}</h2>
        <label className="block text-sm">
          <span className="text-xs text-[var(--color-bo-ink-2)]">
            {reject ? "Motivo del rifiuto (obbligatorio)" : "Commento (facoltativo)"}
          </span>
          <textarea
            autoFocus
            value={comment}
            onChange={(e) => onComment(e.target.value)}
            aria-label={reject ? "Motivo del rifiuto" : "Commento"}
            rows={3}
            className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm"
          />
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onCancel} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
            Annulla
          </button>
          <button
            type="submit"
            disabled={busy || (reject && !comment.trim())}
            className={
              reject
                ? "rounded bg-red-700 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
                : "rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
            }
          >
            {reject ? "Rifiuta" : "Conferma"}
          </button>
        </div>
      </form>
    </div>
  );
}
