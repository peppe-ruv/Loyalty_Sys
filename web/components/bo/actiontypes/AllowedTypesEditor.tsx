"use client";

import { useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { LhError } from "@/lib/api/client";
import { useSideSheet } from "@/components/bo/SideSheet";
import { categoryLabel, CATEGORY_ORDER, type ActionType } from "@/lib/actiontypes/types";
import { isProgramGenerated, planAllowedTypes, type AllowedMode, type SourceRow } from "@/lib/actiontypes/sources";
import { readSource, sameAllowedTypes, writeAllowedTypes } from "@/lib/actiontypes/source-writes";
import { it } from "@/lib/i18n/it";

const t = it.actions.sources;

/**
 * Azioni ammesse da una fonte (BO-09 scheda Fonti, solo `program.config`; Q-433). Si invia sempre l'elenco completo
 * con `PUT /v1/sources/{code}`; «Tutte le azioni» (`[]`) è una scelta esplicita con avviso, mai l'effetto di togliere
 * l'ultima casella. Le azioni generate dal programma (categoria `INTERNAL`) non si offrono: una fonte esterna non deve
 * poterle inviare; se sono già nell'elenco restano visibili e si possono solo togliere. Prima di salvare la fonte si
 * rilegge: se l'elenco è cambiato nel frattempo, l'editor lo dice e chiede se ricaricare o salvare comunque.
 * SPEC-GAP: Q-433.
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
  // Elenco di partenza: quello della fonte, o quello riletto dopo un conflitto.
  const [base, setBase] = useState<string[]>(source.allowedTypes);
  const initialMode: AllowedMode = base.length === 0 ? "ALL" : "LIST";
  const [mode, setMode] = useState<AllowedMode>(initialMode);
  const [selected, setSelected] = useState<string[]>(base);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<string[] | null>(null);
  const plan = planAllowedTypes(mode, selected);
  const dirty = mode !== initialMode || !sameAllowedTypes(selected, base);

  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  const groups = useMemo(() => {
    const by = new Map<string, ActionType[]>();
    for (const type of types) {
      if (isProgramGenerated(type)) continue;
      by.set(type.category ?? "", [...(by.get(type.category ?? "") ?? []), type]);
    }
    const rank = (c: string) => {
      const i = (CATEGORY_ORDER as readonly string[]).indexOf(c);
      return i < 0 ? CATEGORY_ORDER.length : i;
    };
    return [...by.entries()].sort(([a], [b]) => rank(a) - rank(b));
  }, [types]);
  // Codici ammessi che non si offrono (azioni sparite o generate dal programma): restano nell'elenco inviato, visibili
  // qui, e si possono solo togliere.
  const orphans = selected.filter((c) => {
    const type = types.find((x) => x.code === c);
    return !type || isProgramGenerated(type);
  });
  const nameOf = (code: string) => types.find((x) => x.code === code)?.name ?? code;

  async function save(force = false) {
    if (!plan.ok) return;
    setBusy(true);
    setError(null);
    try {
      if (!force) {
        const fresh = await readSource(source.code);
        if (!sameAllowedTypes(fresh.allowedTypes, base)) {
          setConflict(fresh.allowedTypes);
          return;
        }
      }
      await writeAllowedTypes(source.code, plan.allowedTypes);
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

  function reload(fresh: string[]) {
    setBase(fresh);
    setSelected(fresh);
    setMode(fresh.length === 0 ? "ALL" : "LIST");
    setConflict(null);
  }

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
              {nameOf(code) !== code ? <span>{nameOf(code)}</span> : null}
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
      {conflict ? (
        <div role="alert" className="space-y-2 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
          <p>{t.conflict(conflict.length ? conflict.map(nameOf).join(", ") : t.allTypes)}</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" onClick={() => reload(conflict)} className="rounded border border-amber-400 px-2 py-0.5 font-medium">
              {t.conflictReload}
            </button>
            <button type="button" onClick={() => save(true)} disabled={busy} className="rounded border border-amber-400 px-2 py-0.5">
              {t.conflictSave}
            </button>
          </div>
        </div>
      ) : null}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => save()}
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
