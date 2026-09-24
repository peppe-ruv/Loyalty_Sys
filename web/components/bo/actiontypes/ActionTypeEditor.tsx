"use client";

import { useMemo, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError } from "@/lib/api/client";
import { Field, INPUT } from "@/components/bo/FormBits";
import { CATEGORY_LABEL, CUSTOM_CATEGORIES, type ActionType, type ActionTypeRequest } from "@/lib/actiontypes/types";
import {
  emptyRow,
  isValidCode,
  KIND_LABEL,
  rowErrors,
  rowsToSchema,
  sampleFromRows,
  schemaToRows,
  type FieldKind,
  type FieldRow,
} from "@/lib/actiontypes/schema";

/**
 * Editor di BO-09 (docs/08 §BO-09): *Nuovo tipo custom* con i campi a righe → JSON Schema generato; modifica di un
 * custom (tutto tranne il codice) o di un tipo di sistema (solo nome, descrizione, icona, abilitazione).
 */
export function ActionTypeEditor({
  initial,
  onSaved,
  onCancel,
}: {
  initial: ActionType | null;
  onSaved: (t: ActionType) => void;
  onCancel: () => void;
}) {
  const qc = useQueryClient();
  const isNew = initial == null;
  const isSystem = initial?.origin === "SYSTEM";
  const initialRows = useMemo(() => (initial ? schemaToRows(initial.dataSchema) : [emptyRow()]), [initial]);
  const [code, setCode] = useState(initial?.code ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [category, setCategory] = useState(initial?.category ?? "ENGAGEMENT");
  const [icon, setIcon] = useState(initial?.icon ?? "");
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [rows, setRows] = useState<FieldRow[]>(initialRows ?? []);
  const [sample, setSample] = useState(() =>
    JSON.stringify(initial?.sampleData ?? sampleFromRows(initialRows ?? []), null, 2),
  );
  const [showSchema, setShowSchema] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<LhError | null>(null);

  // Uno schema non piatto (creato fuori da questo editor) si modifica solo come JSON: qui lo si conserva com'è.
  const rowsEditable = !isSystem && initialRows != null;
  const errors = rowsEditable ? rowErrors(rows) : {};
  const schema = rowsEditable ? rowsToSchema(rows) : initial?.dataSchema ?? null;
  const sampleParsed = parseObject(sample);
  const codeOk = !isNew || isValidCode(code);
  const blocked =
    busy ||
    !name.trim() ||
    !codeOk ||
    Object.keys(errors).length > 0 ||
    (rowsEditable && rows.length === 0) ||
    (!isSystem && sampleParsed === undefined);

  function update(i: number, patch: Partial<FieldRow>) {
    setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  }

  async function save() {
    setBusy(true);
    setError(null);
    const body: ActionTypeRequest = isSystem
      ? { name: name.trim(), description: description || null, icon: icon || null, enabled }
      : {
          ...(isNew ? { code: code.trim() } : {}),
          name: name.trim(),
          description: description || null,
          category,
          icon: icon || null,
          enabled,
          dataSchema: schema,
          sampleData: sampleParsed ?? {},
        };
    try {
      const saved = await lhFetch<ActionType>("ingestion", isNew ? "/v1/event-types" : `/v1/event-types/${initial!.code}`, {
        method: isNew ? "POST" : "PUT",
        body: JSON.stringify(body),
      });
      await qc.invalidateQueries({ queryKey: ["ingestion"] });
      onSaved(saved);
    } catch (e) {
      setError(e instanceof LhError ? e : new LhError(0, "ERROR", "Salvataggio non riuscito", false));
    } finally {
      setBusy(false);
    }
  }

  const fieldError = (f: string) => error?.errors?.find((e) => e.field === f)?.message;

  return (
    <div className="space-y-4">
      {isSystem ? (
        <p className="rounded bg-[var(--color-bo-bg)] p-2 text-xs text-[var(--color-bo-ink-2)]">
          Tipo di sistema: si cambiano solo nome, descrizione, icona e abilitazione. Schema e categoria restano quelli del programma.
        </p>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Codice" hint={isNew ? "minuscolo a punti, es. meter.reading.sent" : "non modificabile"}>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.trim())}
            disabled={!isNew}
            className={`${INPUT} font-mono`}
            aria-invalid={!codeOk}
          />
          {!codeOk && code ? <span className="text-xs text-red-700">Da 2 a 4 parti separate da punto, minuscole.</span> : null}
          {fieldError("code") ? <span className="text-xs text-red-700">{fieldError("code")}</span> : null}
        </Field>
        <Field label="Nome">
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} className={INPUT} />
        </Field>
      </div>
      <Field label="Descrizione">
        <input value={description} onChange={(e) => setDescription(e.target.value)} className={INPUT} />
      </Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Categoria">
          <select value={category} onChange={(e) => setCategory(e.target.value)} disabled={isSystem} className={INPUT}>
            {(isSystem ? [category] : CUSTOM_CATEGORIES).map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c] ?? c}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Icona" hint="nome lucide, es. gauge">
          <input value={icon} onChange={(e) => setIcon(e.target.value)} className={INPUT} />
        </Field>
        <Field label="Stato" group>
          <label className="flex items-center gap-2 pt-1.5 text-sm">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            Abilitato
          </label>
        </Field>
      </div>

      {!isSystem ? (
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold">Campi di data</h4>
            <button type="button" onClick={() => setShowSchema((v) => !v)} className="text-xs underline">
              {showSchema ? "Nascondi JSON Schema" : "Vedi JSON Schema"}
            </button>
          </div>
          {rowsEditable ? (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs text-[var(--color-bo-ink-2)]">
                      <th className="py-1 pr-2 font-medium">Nome</th>
                      <th className="py-1 pr-2 font-medium">Tipo</th>
                      <th className="py-1 pr-2 font-medium">Valori</th>
                      <th className="py-1 pr-2 font-medium">Obbl.</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r, i) => (
                      <tr key={i} className="align-top">
                        <td className="py-1 pr-2">
                          <input
                            value={r.name}
                            onChange={(e) => update(i, { name: e.target.value.trim() })}
                            placeholder="reading"
                            aria-label={`Nome del campo ${i + 1}`}
                            className={`${INPUT} font-mono`}
                          />
                          {errors[i] ? <span className="text-xs text-red-700">{errors[i]}</span> : null}
                        </td>
                        <td className="py-1 pr-2">
                          <select
                            value={r.kind}
                            onChange={(e) => update(i, { kind: e.target.value as FieldKind })}
                            aria-label={`Tipo del campo ${i + 1}`}
                            className={INPUT}
                          >
                            {(Object.keys(KIND_LABEL) as FieldKind[]).map((k) => (
                              <option key={k} value={k}>
                                {KIND_LABEL[k]}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td className="py-1 pr-2">
                          <input
                            value={r.options}
                            onChange={(e) => update(i, { options: e.target.value })}
                            disabled={r.kind !== "enum"}
                            placeholder={r.kind === "enum" ? "APP, WEB" : "—"}
                            aria-label={`Valori del campo ${i + 1}`}
                            className={INPUT}
                          />
                        </td>
                        <td className="py-1 pr-2 text-center">
                          <input
                            type="checkbox"
                            checked={r.required}
                            onChange={(e) => update(i, { required: e.target.checked })}
                            aria-label={`Campo ${i + 1} obbligatorio`}
                            className="mt-2"
                          />
                        </td>
                        <td className="py-1">
                          <button
                            type="button"
                            onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}
                            aria-label={`Togli il campo ${i + 1}`}
                            className="mt-1 rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-[var(--color-bo-bg)]"
                          >
                            <Trash2 size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setRows((rs) => [...rs, emptyRow()])}
                  className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
                >
                  <Plus size={12} /> Aggiungi campo
                </button>
                <button
                  type="button"
                  onClick={() => setSample(JSON.stringify(sampleFromRows(rows), null, 2))}
                  className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
                >
                  Rigenera dati d&apos;esempio
                </button>
              </div>
            </>
          ) : (
            <p className="text-xs text-[var(--color-bo-ink-2)]">Schema con campi annidati: resta com&apos;è.</p>
          )}
          {showSchema ? (
            <pre className="max-h-56 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
              {JSON.stringify(schema, null, 2)}
            </pre>
          ) : null}
          <Field label="Dati d'esempio (sampleData)" hint="precompilano il simulatore (BO-28)">
            <textarea
              value={sample}
              onChange={(e) => setSample(e.target.value)}
              rows={5}
              className={`${INPUT} font-mono text-xs`}
              aria-invalid={sampleParsed === undefined}
            />
            {sampleParsed === undefined ? <span className="text-xs text-red-700">JSON non valido: serve un oggetto.</span> : null}
            {fieldError("sampleData") ? <span className="text-xs text-red-700">{fieldError("sampleData")}</span> : null}
            {fieldError("dataSchema") ? <span className="text-xs text-red-700">{fieldError("dataSchema")}</span> : null}
          </Field>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="rounded bg-red-50 p-2 text-sm text-red-800">
          {error.code === "EVENT_TYPE_EXISTS" ? "Esiste già un tipo con questo codice." : error.detail || error.code}
        </p>
      ) : null}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={save}
          disabled={blocked}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {busy ? "Salvataggio…" : isNew ? "Crea tipo" : "Salva"}
        </button>
        <button type="button" onClick={onCancel} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
          Annulla
        </button>
      </div>
    </div>
  );
}

function parseObject(text: string): Record<string, unknown> | undefined {
  try {
    const v = JSON.parse(text || "{}");
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}
