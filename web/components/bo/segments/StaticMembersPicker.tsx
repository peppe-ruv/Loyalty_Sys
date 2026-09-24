"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { parseMemberIds } from "@/lib/segments/usage";
import { INPUT } from "@/components/bo/FormBits";
import { CodeText, TierBadge } from "@/components/bo/primitives";

// Selettore membri multi-scelta dei segmenti statici (docs/08 §BO-04): ricerca per nome/e-mail/ID oppure incolla di ID.
export function StaticMembersPicker({
  value,
  onChange,
  disabled,
}: {
  value: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
}) {
  const [q, setQ] = useState("");
  const [paste, setPaste] = useState("");
  const [pasteError, setPasteError] = useState<string | null>(null);
  const search = useLhQuery<Page<MemberView>>("member", "/v1/members", { q, size: 20 }, { enabled: q.trim().length >= 2 });

  const add = (ids: string[]) => onChange([...value, ...ids.filter((id) => !value.includes(id))]);

  return (
    <div className="space-y-3">
      <div>
        <p className="mb-1 text-xs font-medium text-[var(--color-bo-ink-2)]">Membri selezionati ({value.length})</p>
        {value.length === 0 ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun membro: cerca qui sotto o incolla degli ID.</p>
        ) : (
          <ul className="flex flex-wrap gap-1.5">
            {value.map((id) => (
              <li key={id} className="inline-flex items-center gap-1 rounded-full bg-slate-100 py-0.5 pl-2 pr-1 text-xs">
                <span className="font-mono">{id}</span>
                <button
                  type="button"
                  aria-label={`Togli ${id}`}
                  disabled={disabled}
                  onClick={() => onChange(value.filter((x) => x !== id))}
                  className="rounded-full p-0.5 hover:bg-slate-200 disabled:opacity-40"
                >
                  <X className="size-3" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block text-sm">
          <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">Cerca un membro</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} disabled={disabled} placeholder="nome, e-mail o ID (min. 2 caratteri)" className={INPUT} />
        </label>
        <div className="text-sm">
          <label className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]" htmlFor="paste-ids">
            Incolla ID
          </label>
          <div className="flex gap-2">
            <input
              id="paste-ids"
              value={paste}
              onChange={(e) => {
                setPaste(e.target.value);
                setPasteError(null);
              }}
              disabled={disabled}
              placeholder="MBR-000004, MBR-000005"
              className={INPUT}
            />
            <button
              type="button"
              disabled={disabled || !paste.trim()}
              onClick={() => {
                const { ids, invalid } = parseMemberIds(paste);
                add(ids);
                setPaste(invalid.join(" "));
                setPasteError(invalid.length ? `Non riconosciuti: ${invalid.join(", ")}` : null);
              }}
              className="shrink-0 rounded border border-[var(--color-bo-border)] px-2.5 text-xs hover:bg-slate-50 disabled:opacity-50"
            >
              Aggiungi
            </button>
          </div>
          {pasteError ? <p className="mt-1 text-xs text-red-700">{pasteError}</p> : null}
        </div>
      </div>

      {q.trim().length >= 2 ? (
        search.isLoading ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Ricerca…</p>
        ) : search.isError ? (
          <p className="text-xs text-amber-700">Ricerca non disponibile: {search.error.asleep ? "il servizio membri dorme" : search.error.detail}</p>
        ) : search.data && search.data.items.length > 0 ? (
          <ul className="divide-y divide-[var(--color-bo-border)] rounded border border-[var(--color-bo-border)]">
            {search.data.items
              .filter((m) => m.status !== "ANONYMIZED")
              .map((m) => (
                <li key={m.id} className="flex items-center justify-between gap-2 px-2 py-1.5 text-sm">
                  <span className="flex items-center gap-2">
                    {m.firstName} {m.lastName} <CodeText>{m.id}</CodeText> <TierBadge tier={m.tier} />
                  </span>
                  <button
                    type="button"
                    disabled={disabled || value.includes(m.id)}
                    onClick={() => add([m.id])}
                    className="rounded border border-[var(--color-bo-border)] px-2 py-0.5 text-xs hover:bg-slate-50 disabled:opacity-40"
                  >
                    {value.includes(m.id) ? "Già presente" : "Aggiungi"}
                  </button>
                </li>
              ))}
          </ul>
        ) : (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun membro trovato.</p>
        )
      ) : null}
    </div>
  );
}
