"use client";

import { Plus, Trash2 } from "lucide-react";
import { INPUT } from "@/components/bo/FormBits";
import {
  COMPARATORS,
  COMPARATOR_LABEL,
  SEGMENT_FIELDS,
  fieldDef,
  newRow,
  type BuilderState,
  type CriteriaRow,
} from "@/lib/segments/criteria";
import type { Comparator } from "@/lib/segments/types";
import { cn } from "@/lib/cn";

// ConditionBuilder dei segmenti (docs/08 §3.6, §BO-04): condizioni sui campi del membro, tutte (e) o almeno una (oppure).
// I criteri più complessi (gruppi annidati, "non") si scrivono nella vista JSON dell'editor.
export function CriteriaBuilder({
  state,
  onChange,
  problems,
  disabled,
}: {
  state: BuilderState;
  onChange: (next: BuilderState) => void;
  problems: Record<number, string>;
  disabled?: boolean;
}) {
  const setRow = (i: number, patch: Partial<CriteriaRow>) =>
    onChange({ ...state, rows: state.rows.map((r, j) => (j === i ? { ...r, ...patch } : r)) });

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-sm">
        <span className="text-[var(--color-bo-ink-2)]">I membri che soddisfano</span>
        <select
          aria-label="Combinazione delle condizioni"
          value={state.op}
          disabled={disabled}
          onChange={(e) => onChange({ ...state, op: e.target.value as BuilderState["op"] })}
          className={cn(INPUT.replace("w-full ", ""), "w-auto")}
        >
          <option value="all">tutte le condizioni</option>
          <option value="any">almeno una condizione</option>
        </select>
      </div>

      {state.rows.length === 0 ? (
        <p className="rounded border border-dashed border-[var(--color-bo-border)] p-3 text-xs text-[var(--color-bo-ink-2)]">
          Nessuna condizione: aggiungine una per definire il segmento.
        </p>
      ) : null}

      <ul className="space-y-2">
        {state.rows.map((row, i) => {
          const def = fieldDef(row.field);
          const kind = def?.kind ?? "text";
          const cmps = COMPARATORS[kind];
          const noValue = row.cmp === "exists" || row.cmp === "nexists";
          const enumSingle = kind === "enum" && (row.cmp === "eq" || row.cmp === "neq");
          return (
            <li key={i} className="rounded border border-[var(--color-bo-border)] p-2">
              <div className="grid gap-2 sm:grid-cols-[minmax(0,2fr)_minmax(0,1.2fr)_minmax(0,2fr)_auto]">
                <div className="space-y-1">
                  <select
                    aria-label={`Campo della condizione ${i + 1}`}
                    value={row.field}
                    disabled={disabled}
                    onChange={(e) => {
                      const next = newRow(e.target.value);
                      setRow(i, { field: next.field, cmp: next.cmp, param: "", value: "" });
                    }}
                    className={INPUT}
                  >
                    {SEGMENT_FIELDS.map((f) => (
                      <option key={f.id} value={f.id}>{f.label}</option>
                    ))}
                  </select>
                  {kind === "action" || kind === "attribute" ? (
                    <input
                      aria-label={kind === "action" ? "Tipo di azione" : "Chiave dell'attributo"}
                      value={row.param}
                      disabled={disabled}
                      onChange={(e) => setRow(i, { param: e.target.value })}
                      placeholder={def?.hint}
                      className={INPUT}
                    />
                  ) : null}
                </div>
                <select
                  aria-label={`Comparatore della condizione ${i + 1}`}
                  value={row.cmp}
                  disabled={disabled}
                  onChange={(e) => setRow(i, { cmp: e.target.value as Comparator })}
                  className={INPUT}
                >
                  {cmps.map((c) => (
                    <option key={c} value={c}>{COMPARATOR_LABEL[c]}</option>
                  ))}
                </select>
                {noValue ? (
                  <span className="self-center text-xs text-[var(--color-bo-ink-2)]">nessun valore</span>
                ) : enumSingle ? (
                  <select
                    aria-label={`Valore della condizione ${i + 1}`}
                    value={row.value}
                    disabled={disabled}
                    onChange={(e) => setRow(i, { value: e.target.value })}
                    className={INPUT}
                  >
                    <option value="">Scegli…</option>
                    {def?.options?.map((o) => (
                      <option key={o} value={o}>{o}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    aria-label={`Valore della condizione ${i + 1}`}
                    value={row.value}
                    disabled={disabled}
                    onChange={(e) => setRow(i, { value: e.target.value })}
                    placeholder={placeholder(kind, row.cmp, def?.options, def?.hint)}
                    inputMode={kind === "number" || kind === "action" ? "decimal" : undefined}
                    className={INPUT}
                  />
                )}
                <button
                  type="button"
                  aria-label={`Rimuovi la condizione ${i + 1}`}
                  disabled={disabled}
                  onClick={() => onChange({ ...state, rows: state.rows.filter((_, j) => j !== i) })}
                  className="self-start rounded p-2 text-[var(--color-bo-ink-2)] hover:bg-slate-100 disabled:opacity-40"
                >
                  <Trash2 className="size-4" />
                </button>
              </div>
              {problems[i] ? <p className="mt-1 text-xs text-red-700">{problems[i]}</p> : null}
            </li>
          );
        })}
      </ul>

      <button
        type="button"
        disabled={disabled}
        onClick={() => onChange({ ...state, rows: [...state.rows, newRow()] })}
        className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
      >
        <Plus className="size-3.5" /> Aggiungi condizione
      </button>
    </div>
  );
}

function placeholder(kind: string, cmp: Comparator, options?: string[], hint?: string): string {
  if (cmp === "between") return "minimo, massimo";
  if (cmp === "in" || cmp === "nin") return options ? options.join(", ") : "valori separati da virgola";
  if (kind === "labels") return hint ?? "etichetta";
  if (kind === "number" || kind === "action") return "numero";
  return "valore";
}
