"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Copy } from "lucide-react";
import { useLhMutation, type LhError } from "@/lib/api/client";
import type { ServiceCode } from "@/lib/api/services";
import { Can } from "@/components/bo/Can";

// *Duplica* (M7.6; docs/08 §ciclo di vita, F-CMP-13): copia in DRAFT e apre la copia. Capability `object.edit`.
export function DuplicateButton({
  service,
  path,
  hrefFor,
}: {
  service: ServiceCode;
  /** Es. `/v1/campaigns/{id}/duplicate`. */
  path: string;
  hrefFor: (copy: { id: string }) => string;
}) {
  const router = useRouter();
  const [error, setError] = useState<LhError | null>(null);
  const duplicate = useLhMutation<{ id: string }, undefined>(
    service,
    "POST",
    () => path,
    {
      onSuccess: (copy) => router.push(hrefFor(copy)),
    },
  );
  return (
    <Can capability="object.edit" mode="disable">
      <span className="inline-flex items-center gap-2">
        <button
          type="button"
          onClick={() => {
            setError(null);
            duplicate.mutate(undefined, { onError: setError });
          }}
          disabled={duplicate.isPending}
          className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50 disabled:opacity-50"
        >
          <Copy className="size-3.5" aria-hidden /> Duplica
        </button>
        {error && (
          <span role="alert" className="text-xs text-red-700">
            {error.detail || error.code}
          </span>
        )}
      </span>
    </Can>
  );
}
