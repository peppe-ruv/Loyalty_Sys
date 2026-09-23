"use client";

import { useState } from "react";
import { useLhMutation } from "@/lib/api/client";
import type { ServiceCode } from "@/lib/api/services";
import { StatusPill } from "./primitives";
import { Can } from "./Can";

// Barra del ciclo di vita (docs/08 §3.3): solo le transizioni valide per lo stato. Approvazione off → DRAFT: Pubblica.
// Macchina a stati comune (docs/06 §7): la stessa barra serve campagne, premi e gli altri oggetti configurabili.
const TRANSITIONS: Record<string, { action: string; label: string }[]> = {
  DRAFT: [{ action: "PUBLISH", label: "Pubblica" }, { action: "ARCHIVE", label: "Archivia" }],
  IN_REVIEW: [{ action: "PUBLISH", label: "Pubblica" }],
  LIVE: [{ action: "PAUSE", label: "Metti in pausa" }, { action: "END", label: "Termina" }],
  PAUSED: [{ action: "RESUME", label: "Riprendi" }, { action: "END", label: "Termina" }],
  ENDED: [{ action: "ARCHIVE", label: "Archivia" }],
};

export function LifecycleBar({
  service,
  transitionsPath,
  status,
  system = false,
  onChanged,
}: {
  service: ServiceCode;
  /** Es. `/v1/campaigns/{id}/transitions`. */
  transitionsPath: string;
  status: string;
  system?: boolean;
  onChanged: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const mutation = useLhMutation<unknown, { action: string }>(
    service,
    "POST",
    () => transitionsPath,
    { onSuccess: () => onChanged() },
  );

  const available = (TRANSITIONS[status] ?? []).filter((t) => !(system && t.action === "ARCHIVE"));

  return (
    <div className="flex flex-wrap items-center gap-2">
      <StatusPill status={status} />
      {system ? <span className="text-xs text-slate-400">🔒 sistema</span> : null}
      <div className="flex gap-2">
        {available.map((t) => (
          <Can key={t.action} capability="object.edit" mode="disable">
            <button
              onClick={() =>
                mutation.mutate(
                  { action: t.action },
                  { onError: (e) => setError(e.detail || e.code) },
                )
              }
              disabled={mutation.isPending}
              className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50 disabled:opacity-50"
            >
              {t.label}
            </button>
          </Can>
        ))}
      </div>
      {error ? <span className="text-xs text-red-700">{error}</span> : null}
    </div>
  );
}
