"use client";

import { useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError } from "@/lib/api/client";
import { useSideSheet } from "@/components/bo/SideSheet";
import { categoryLabel, CATEGORY_ORDER, type ActionType } from "@/lib/actiontypes/types";
import { planAllowedTypes, type AllowedMode, type SourceRow } from "@/lib/actiontypes/sources";
import { it } from "@/lib/i18n/it";

const t = it.actions.sources;

/**
 * Azioni ammesse da una fonte (BO-09 scheda Fonti, solo `program.config`; Q-433). Si invia sempre l'elenco completo
 * con `PUT /v1/sources/{code}`; «Tutte le azioni» (`[]`) è una scelta esplicita con avviso, mai l'effetto di togliere
 * l'ultima casella.
 */
export function AllowedTypesEditor({
  source,
  types,
  onSaved,
  onDirtyChange,
}: {
  source: SourceRow;
  types: ActionType[];
  onSaved: () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const qc = useQueryClient();
  const sheet = useSideSheet();
  const initialMode: AllowedMode = source.allowedTypes.length === 0 ? "ALL" : "LIST";
  const [mode, setMode] = useState<AllowedMode>(initialMode);
  const [selected, setSelected] = useState<string[]>(source.allowedTypes);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const plan = planAllowedTypes(mode, selected);
  const dirty = mode !== initialMode || JSON.stringify([...selected].sort()) !== JSON.stringify([...source.allowedTypes].sort());

  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  const groups = useMemo(() => {
    const by = new Map<string, ActionType[]>();
    for (const type of types) by.set(type.category ?? "", [...(by.get(type.category ?? "") ?? []), type]);
    const rank = (c: string) => {
      const i = (CATEGORY_ORDER as readonly string[]).indexOf(c);
      return i < 0 ? CATEGORY_ORDER.length : i;
    };
    return [...by.entries()].sort(([a], [b]) => rank(a) - rank(b));
  }, [types]);
  // Codici ammessi che non esistono più tra le azioni: restano nell'elenco inviato, visibili qui.
  const orphans = selected.filter((c) => !types.some((x) => x.code === c));

  async function save() {
    if (!plan.ok) return;
    setBusy(true);
    setError(null);
    try {
      await lhFetch("ingestion", `/v1/sources/${source.code}`, {
        method: "PUT",
        body: JSON.stringify({ allowedTypes: plan.allowedTypes }),
      });
      await qc.invalidateQueries({ queryKey: ["ingestion"] });
      onSaved();
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || t.failed);
    } finally {
      setBusy(false);
    }
  }

  const toggle = (code: string, on: boolean) => setSelected((s) => (on ? [...s, code] : s.filter((c) => c !== code)));

  return (
    <div className="space-y-4 text-sm">
      {source.description ? <p className="text-[var(--color-bo-ink-2)]">{source.description}</p> : null}
      <fieldset className="space-y-1">
        <legend className="sr-only">{t.allowed}</legend>
        <label className="flex items-center gap-2">
          <input type="radio" name="allowed-mode" checked={mode === "LIST"} onChange={() => setMode("LIST")} />
          {t.modeList}
        </label>
        <label className="flex items-center gap-2">
          <input type="radio" name="allowed-mode" checked={mode === "ALL"} onChange={() => setMode("ALL")} />
          {t.modeAll}
        </label>
      </fieldset>
      {mode === "ALL" ? (
        <p role="status" className="rounded bg-amber-50 p-2 text-xs text-amber-900">
          {t.modeAllWarning}
        </p>
      ) : (
        <div className="space-y-3">
          {groups.map(([category, list]) => (
            <fieldset key={category} className="space-y-1">
              <legend className="text-xs font-medium uppercase tracking-wide text-[var(--color-bo-ink-2)]">{categoryLabel(category)}</legend>
              {list.map((type) => (
                <label key={type.code} className="flex items-center gap-2 text-xs">
                  <input type="checkbox" checked={selected.includes(type.code)} onChange={(e) => toggle(type.code, e.target.checked)} />
                  <span>{type.name}</span>
                  <code className="font-mono text-[var(--color-bo-ink-2)]">{type.code}</code>
                  {!type.enabled ? <span className="text-slate-500">({it.actions.detail.disabled})</span> : null}
                </label>
              ))}
            </fieldset>
          ))}
          {orphans.map((code) => (
            <label key={code} className="flex items-center gap-2 text-xs text-amber-900">
              <input type="checkbox" checked onChange={(e) => toggle(code, e.target.checked)} />
              <code className="font-mono">{code}</code>
            </label>
          ))}
          {!plan.ok ? (
            <p role="alert" className="text-xs text-red-700">
              {t.emptyList}
            </p>
          ) : null}
        </div>
      )}
      {error ? (
        <p role="alert" className="rounded bg-red-50 p-2 text-xs text-red-800">
          {error}
        </p>
      ) : null}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={save}
          disabled={busy || !plan.ok || !dirty}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {busy ? it.actions.editor.saving : t.save}
        </button>
        <button type="button" onClick={() => sheet?.requestClose()} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
          {it.actions.editor.cancel}
        </button>
      </div>
    </div>
  );
}
