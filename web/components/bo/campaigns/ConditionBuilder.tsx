"use client";

import { useId, useMemo, useRef, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { AlertTriangle, ChevronDown, FolderPlus, Plus, Trash2, X } from "lucide-react";
import { lhFetch, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Tier } from "@/lib/api/types";
import type { Segment } from "@/lib/segments/types";
import {
  GROUP_LABEL,
  MAX_DEPTH,
  appendChild,
  buildCatalog,
  canAddGroup,
  changeComparator,
  changeField,
  coerceScalar,
  commonDataFields,
  comparatorLabel,
  comparatorsFor,
  emptyGroup,
  fieldWarnings,
  groupFields,
  isPlausiblePath,
  lookupField,
  newLeaf,
  parseList,
  removeNode,
  updateNode,
  validateTree,
  valueShape,
  valueText,
  type AttributeDefinition,
  type Comparator,
  type ConditionField,
  type EventTypeField,
  type GroupOp,
  type UiGroup,
  type UiLeaf,
  type UiNode,
} from "@/lib/campaign/conditions";
import { INPUT } from "@/components/bo/FormBits";
import { cn } from "@/lib/cn";

// ConditionBuilder delle campagne (docs/08 §BO-06 sezione "4 Se", docs/03 §3.3): gruppi TUTTE / ALMENO UNA / NESSUNA
// annidabili fino a 3 livelli; riga = campo (combobox raggruppato per spazio data/member/context/history) · operatore
// coerente col tipo · valore secondo il tipo. I campi `data.*` vengono dagli schemi dei trigger scelti (intersezione);
// quelli del membro da tier, segmenti e attributi custom. Un percorso fuori catalogo resta accettato.

// ---------- catalogo dai servizi ----------

export type SourceState = "loading" | "ok" | "asleep" | "error";

export interface ConditionCatalog {
  catalog: ConditionField[];
  fieldsByTrigger: Record<string, EventTypeField[] | undefined>;
  /** Stato delle fonti del catalogo (per gli avvisi degraded). */
  sources: { data: SourceState; tiers: SourceState; segments: SourceState; attributes: SourceState };
  retry: () => void;
}

function stateOf(q: { isLoading: boolean; isError: boolean; error: LhError | null }): SourceState {
  if (q.isLoading) return "loading";
  if (q.isError) return q.error?.asleep ? "asleep" : "error";
  return "ok";
}

/** Carica e compone il catalogo dei campi per i trigger scelti (ogni fonte degrada da sola). */
export function useConditionCatalog(triggers: string[]): ConditionCatalog {
  const fieldQueries = useQueries({
    queries: triggers.map((code) => {
      const path = `/v1/event-types/${encodeURIComponent(code)}/fields`;
      return {
        queryKey: ["ingestion", path, {}],
        queryFn: () => lhFetch<EventTypeField[]>("ingestion", path),
        staleTime: 60_000,
      };
    }),
  });
  const tiers = useLhQuery<Tier[]>("wallet", "/v1/tiers");
  const segments = useLhQuery<Page<Segment>>("member", "/v1/segments", { status: "ACTIVE", size: 100 });
  const attributes = useLhQuery<AttributeDefinition[]>("member", "/v1/attribute-definitions");

  const fieldsKey = fieldQueries.map((q) => q.dataUpdatedAt).join(",");
  const fieldsByTrigger = useMemo(() => {
    const out: Record<string, EventTypeField[] | undefined> = {};
    triggers.forEach((code, i) => {
      const data = fieldQueries[i]?.data;
      out[code] = Array.isArray(data) ? data : undefined;
    });
    return out;
    // fieldQueries cambia identità a ogni render: la chiave dei dati basta.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggers.join("|"), fieldsKey]);

  const catalog = useMemo(
    () =>
      buildCatalog({
        dataFields: commonDataFields(fieldsByTrigger, triggers),
        tiers: tiers.data?.slice().sort((a, b) => a.rank - b.rank).map((t) => t.code),
        segments: segments.data?.items.map((s) => ({ code: s.code, name: s.name })),
        attributes: Array.isArray(attributes.data) ? attributes.data : undefined,
      }),
    [fieldsByTrigger, triggers, tiers.data, segments.data, attributes.data],
  );

  const dataState: SourceState =
    triggers.length === 0
      ? "ok"
      : fieldQueries.some((q) => q.isLoading)
        ? "loading"
        : fieldQueries.some((q) => q.isError && (q.error as LhError | null)?.asleep)
          ? "asleep"
          : fieldQueries.some((q) => q.isError)
            ? "error"
            : "ok";

  return {
    catalog,
    fieldsByTrigger,
    sources: { data: dataState, tiers: stateOf(tiers), segments: stateOf(segments), attributes: stateOf(attributes) },
    retry: () => {
      fieldQueries.forEach((q) => q.isError && q.refetch());
      if (tiers.isError) tiers.refetch();
      if (segments.isError) segments.refetch();
      if (attributes.isError) attributes.refetch();
    },
  };
}

// ---------- costruttore ----------

interface Ctx {
  tree: UiGroup;
  onChange: (next: UiGroup) => void;
  catalog: ConditionField[];
  problems: Record<string, string>;
  warnings: Record<string, string>;
  disabled?: boolean;
  defaultField: ConditionField;
}

export function ConditionBuilder({
  tree,
  onChange,
  catalog,
  triggers,
  disabled,
}: {
  tree: UiGroup;
  onChange: (next: UiGroup) => void;
  catalog: ConditionCatalog;
  triggers: string[];
  disabled?: boolean;
}) {
  const problems = useMemo(() => validateTree(tree, catalog.catalog), [tree, catalog.catalog]);
  const unavailable = useMemo(() => {
    const out: ("data" | "member")[] = [];
    if (catalog.sources.data !== "ok") out.push("data");
    if (catalog.sources.attributes !== "ok") out.push("member");
    return out;
  }, [catalog.sources.data, catalog.sources.attributes]);
  const warnings = useMemo(
    () => fieldWarnings(tree, { triggers, fieldsByTrigger: catalog.fieldsByTrigger, catalog: catalog.catalog, unavailable }),
    [tree, triggers, catalog.fieldsByTrigger, catalog.catalog, unavailable],
  );
  const defaultField = catalog.catalog.find((f) => f.space === "data") ?? lookupField(catalog.catalog, "member.tier");
  const ctx: Ctx = { tree, onChange, catalog: catalog.catalog, problems, warnings, disabled, defaultField };

  return (
    <div className="space-y-2">
      <CatalogNotices catalog={catalog} triggers={triggers} />
      <GroupView group={tree} level={1} label="" ctx={ctx} />
    </div>
  );
}

function CatalogNotices({ catalog, triggers }: { catalog: ConditionCatalog; triggers: string[] }) {
  const { sources } = catalog;
  const notes: string[] = [];
  if (sources.data === "asleep" || sources.data === "error") {
    notes.push(
      sources.data === "asleep"
        ? "Campi dei dati non disponibili: il servizio «ingestion» si sta svegliando. Puoi scrivere il percorso a mano (es. data.amount)."
        : "Campi dei dati non disponibili per uno dei trigger: puoi scrivere il percorso a mano (es. data.amount).",
    );
  }
  if (sources.attributes === "asleep" || sources.attributes === "error" || sources.segments === "asleep" || sources.segments === "error") {
    notes.push("Attributi custom e segmenti non disponibili (servizio «member»): scrivi member.attributes.<chiave> o il codice del segmento a mano.");
  }
  if (sources.tiers === "asleep" || sources.tiers === "error") {
    notes.push("Livelli non letti dal servizio «wallet»: uso BASE, SILVER, GOLD, PLATINUM.");
  }
  return (
    <>
      {triggers.length === 0 ? (
        <p className="text-xs text-[var(--color-bo-ink-2)]">Scegli almeno un trigger per vedere i campi dei dati dell&apos;azione.</p>
      ) : sources.data === "loading" ? (
        <p className="text-xs text-[var(--color-bo-ink-2)]" aria-live="polite">Carico i campi dei dati dei trigger scelti…</p>
      ) : triggers.length > 1 && sources.data === "ok" ? (
        <p className="text-xs text-[var(--color-bo-ink-2)]">Con più trigger sono proposti solo i campi dei dati comuni a tutti.</p>
      ) : null}
      {notes.length > 0 ? (
        <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900" role="status">
          <ul className="list-disc space-y-0.5 pl-4">
            {notes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
          <button type="button" onClick={catalog.retry} className="mt-1.5 rounded border border-amber-300 px-2 py-0.5 hover:bg-amber-100">
            Riprova
          </button>
        </div>
      ) : null}
    </>
  );
}

const OP_HINT: Record<GroupOp, string> = {
  all: "vale se tutte le righe sono vere",
  any: "vale se almeno una riga è vera",
  not: "vale se le righe non sono vere",
};

function GroupView({ group, level, label, ctx }: { group: UiGroup; level: number; label: string; ctx: Ctx }) {
  const root = level === 1;
  const set = (patch: Partial<UiGroup>) => ctx.onChange(updateNode(ctx.tree, group.id, (g) => ({ ...(g as UiGroup), ...patch })));
  const addGroupAllowed = canAddGroup(ctx.tree, group.id);
  const name = root ? "principale" : label;

  return (
    <div
      role="group"
      aria-label={root ? "Condizioni" : `Gruppo ${label}`}
      className={cn("space-y-2", root ? "" : "rounded-md border border-dashed border-[var(--color-bo-border)] bg-slate-50/60 p-2")}
    >
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-[var(--color-bo-ink-2)]">{root ? "Devono essere vere" : "Gruppo: devono essere vere"}</span>
        <select
          aria-label={`Combinazione del gruppo ${name}`}
          value={group.op}
          disabled={ctx.disabled}
          onChange={(e) => set({ op: e.target.value as GroupOp })}
          className={cn(INPUT.replace("w-full ", ""), "w-auto font-semibold")}
        >
          {(Object.keys(GROUP_LABEL) as GroupOp[]).map((op) => (
            <option key={op} value={op}>
              {GROUP_LABEL[op]}
            </option>
          ))}
        </select>
        <span className="text-xs text-[var(--color-bo-ink-2)]">{OP_HINT[group.op]}</span>
        {!root ? (
          <button
            type="button"
            aria-label={`Togli il gruppo ${label}`}
            disabled={ctx.disabled}
            onClick={() => ctx.onChange(removeNode(ctx.tree, group.id))}
            className="ml-auto rounded p-1.5 text-[var(--color-bo-ink-2)] hover:bg-slate-100 disabled:opacity-40"
          >
            <Trash2 className="size-4" />
          </button>
        ) : null}
      </div>

      {group.op === "not" && group.rules.length > 1 ? (
        // SPEC-GAP: Q-90 — docs/08 chiama il gruppo `not` «NESSUNA», ma il motore (ConditionEvaluator) lo valuta come
        // «non tutte vere insieme». Etichetta della spec, con l'avviso sulla semantica reale quando le righe sono più d'una.
        <p className="flex items-start gap-1 text-xs text-amber-800">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          Il motore valuta NESSUNA come «non tutte vere insieme». Per escludere ciascuna riga, mettile in un gruppo ALMENO UNA
          dentro questo gruppo.
        </p>
      ) : null}

      {group.rules.length === 0 ? (
        <p className="rounded border border-dashed border-[var(--color-bo-border)] bg-white p-3 text-xs text-[var(--color-bo-ink-2)]">
          {root
            ? "Nessuna condizione: la campagna scatta a ogni azione dei trigger scelti (salvo pubblico e limiti)."
            : "Gruppo vuoto: aggiungi una condizione, altrimenti non viene salvato."}
        </p>
      ) : (
        <ul className="space-y-2">
          {group.rules.map((node, i) => {
            const childLabel = root ? `${i + 1}` : `${label}.${i + 1}`;
            return (
              <li key={node.id}>
                {node.kind === "group" ? (
                  <GroupView group={node} level={level + 1} label={childLabel} ctx={ctx} />
                ) : (
                  <LeafRow leaf={node} label={childLabel} ctx={ctx} />
                )}
              </li>
            );
          })}
        </ul>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={ctx.disabled}
          onClick={() => ctx.onChange(appendChild(ctx.tree, group.id, newLeaf(ctx.defaultField)))}
          className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] bg-white px-2.5 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
        >
          <Plus className="size-3.5" /> Condizione
        </button>
        <button
          type="button"
          disabled={ctx.disabled || !addGroupAllowed}
          title={addGroupAllowed ? undefined : `Al massimo ${MAX_DEPTH} livelli di gruppi`}
          onClick={() => {
            const g: UiNode = { ...emptyGroup("any"), rules: [newLeaf(ctx.defaultField)] };
            ctx.onChange(appendChild(ctx.tree, group.id, g));
          }}
          className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] bg-white px-2.5 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
        >
          <FolderPlus className="size-3.5" /> Gruppo
        </button>
      </div>
    </div>
  );
}

function LeafRow({ leaf, label, ctx }: { leaf: UiLeaf; label: string; ctx: Ctx }) {
  const field = lookupField(ctx.catalog, leaf.field);
  const cmps = comparatorsFor(field.type);
  // Un comparatore non coerente (da JSON) resta visibile finché non lo si cambia.
  const cmpOptions: Comparator[] = cmps.includes(leaf.cmp) ? cmps : [leaf.cmp, ...cmps];
  const set = (next: UiLeaf) => ctx.onChange(updateNode(ctx.tree, leaf.id, () => next));
  const problem = ctx.problems[leaf.id];
  const warning = ctx.warnings[leaf.id];

  return (
    <div className={cn("rounded border bg-white p-2", problem ? "border-red-300" : "border-[var(--color-bo-border)]")}>
      <div className="grid gap-2 md:grid-cols-[minmax(0,2fr)_minmax(0,1.1fr)_minmax(0,2fr)_auto]">
        <FieldCombobox
          label={`Campo della condizione ${label}`}
          field={field}
          catalog={ctx.catalog}
          disabled={ctx.disabled}
          onSelect={(next) => set(changeField(leaf, field, next))}
        />
        <select
          aria-label={`Operatore della condizione ${label}`}
          value={leaf.cmp}
          disabled={ctx.disabled}
          onChange={(e) => set(changeComparator(leaf, e.target.value as Comparator, field))}
          className={INPUT}
        >
          {cmpOptions.map((c) => (
            <option key={c} value={c}>
              {comparatorLabel(c, field.type)}
            </option>
          ))}
        </select>
        <ValueEditor
          label={`Valore della condizione ${label}`}
          leaf={leaf}
          field={field}
          disabled={ctx.disabled}
          onChange={(value) => set({ ...leaf, value })}
        />
        <button
          type="button"
          aria-label={`Togli la condizione ${label}`}
          disabled={ctx.disabled}
          onClick={() => ctx.onChange(removeNode(ctx.tree, leaf.id))}
          className="self-start rounded p-2 text-[var(--color-bo-ink-2)] hover:bg-slate-100 disabled:opacity-40"
        >
          <Trash2 className="size-4" />
        </button>
      </div>
      {problem ? <p className="mt-1 text-xs text-red-700">{problem}</p> : null}
      {warning ? (
        <p className="mt-1 flex items-start gap-1 text-xs text-amber-800">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          {warning}
        </p>
      ) : null}
      {!problem && !warning && field.hint ? <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{field.hint}</p> : null}
    </div>
  );
}

// ---------- combobox dei campi ----------

function FieldCombobox({
  label,
  field,
  catalog,
  disabled,
  onSelect,
}: {
  label: string;
  field: ConditionField;
  catalog: ConditionField[];
  disabled?: boolean;
  onSelect: (f: ConditionField) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listId = useId();

  const groups = useMemo(() => groupFields(catalog, query), [catalog, query]);
  const typed = query.trim();
  const freeText = typed && isPlausiblePath(typed) && !catalog.some((f) => f.path === typed) ? typed : null;
  const options: ConditionField[] = [
    ...groups.flatMap((g) => g.fields),
    ...(freeText ? [lookupField([], freeText)] : []),
  ];

  function choose(f: ConditionField) {
    onSelect(f);
    setOpen(false);
    setQuery("");
  }

  if (!open) {
    return (
      <button
        type="button"
        aria-label={`${label}: ${field.path || "nessuno"}`}
        aria-haspopup="listbox"
        disabled={disabled}
        onClick={() => {
          setOpen(true);
          setActive(0);
          setTimeout(() => inputRef.current?.focus(), 0);
        }}
        className={cn(INPUT, "flex min-h-[34px] items-center gap-2 text-left")}
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate">{field.path ? field.label : "Scegli un campo…"}</span>
          {field.path && field.label !== field.path ? (
            <span className="block truncate font-mono text-[11px] text-[var(--color-bo-ink-2)]">{field.path}</span>
          ) : null}
        </span>
        {field.custom && field.path ? (
          <span className="shrink-0 rounded bg-slate-100 px-1 text-[10px] text-[var(--color-bo-ink-2)]">libero</span>
        ) : null}
        <ChevronDown className="size-4 shrink-0 text-[var(--color-bo-ink-2)]" />
      </button>
    );
  }

  const activeId = options[active] ? `${listId}-${active}` : undefined;
  let index = -1;
  return (
    <div className="relative">
      <input
        ref={inputRef}
        role="combobox"
        aria-label={label}
        aria-expanded="true"
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={activeId}
        value={query}
        placeholder="Cerca o scrivi un percorso (es. data.amount)"
        onChange={(e) => {
          setQuery(e.target.value);
          setActive(0);
        }}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setActive((a) => Math.min(a + 1, options.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if (e.key === "Enter") {
            e.preventDefault();
            if (options[active]) choose(options[active]);
          } else if (e.key === "Escape") {
            e.preventDefault();
            setOpen(false);
            setQuery("");
          }
        }}
        className={cn(INPUT, "font-mono text-xs")}
      />
      <ul
        id={listId}
        role="listbox"
        aria-label={label}
        className="absolute z-20 mt-1 max-h-72 w-full min-w-[16rem] overflow-y-auto rounded-md border border-[var(--color-bo-border)] bg-white py-1 text-sm shadow-lg"
      >
        {groups.map((g) => (
          <li key={g.space} role="presentation">
            <p className="px-2 pb-0.5 pt-1.5 text-[11px] font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">
              {g.label} <span className="font-mono normal-case">{g.space}.*</span>
            </p>
            <ul role="group" aria-label={g.label}>
              {g.fields.map((f) => {
                index += 1;
                const i = index;
                return (
                  <li
                    key={f.path}
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={i === active}
                    onMouseDown={(e) => e.preventDefault()}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => choose(f)}
                    className={cn("cursor-pointer px-2 py-1", i === active ? "bg-slate-100" : "", f.path === field.path ? "font-medium" : "")}
                  >
                    <span className="block">{f.label}</span>
                    <span className="block font-mono text-[11px] text-[var(--color-bo-ink-2)]">{f.path}</span>
                  </li>
                );
              })}
            </ul>
          </li>
        ))}
        {freeText ? (
          <li
            id={`${listId}-${options.length - 1}`}
            role="option"
            aria-selected={active === options.length - 1}
            onMouseDown={(e) => e.preventDefault()}
            onMouseEnter={() => setActive(options.length - 1)}
            onClick={() => choose(lookupField([], freeText))}
            className={cn("cursor-pointer border-t border-[var(--color-bo-border)] px-2 py-1.5", active === options.length - 1 ? "bg-slate-100" : "")}
          >
            Usa il percorso <span className="font-mono">{freeText}</span>
          </li>
        ) : null}
        {options.length === 0 ? (
          <li role="presentation" className="px-2 py-1.5 text-xs text-[var(--color-bo-ink-2)]">
            Nessun campo. Un percorso libero inizia con data., member., context. o history.
          </li>
        ) : null}
      </ul>
    </div>
  );
}

// ---------- editor del valore ----------

function ValueEditor({
  label,
  leaf,
  field,
  disabled,
  onChange,
}: {
  label: string;
  leaf: UiLeaf;
  field: ConditionField;
  disabled?: boolean;
  onChange: (value: unknown) => void;
}) {
  const shape = valueShape(leaf.cmp);
  const coerce = (raw: string) => coerceScalar(raw, field);

  if (shape === "none") {
    return <span className="self-center text-xs text-[var(--color-bo-ink-2)]">nessun valore</span>;
  }

  if (shape === "list") {
    const values = Array.isArray(leaf.value) ? leaf.value : [];
    if (field.type === "enum" && field.options) {
      return <MultiToggle label={label} field={field} values={values} disabled={disabled} onChange={onChange} />;
    }
    return <ChipsInput label={label} field={field} values={values} disabled={disabled} onChange={onChange} />;
  }

  if (shape === "range") {
    const pair = Array.isArray(leaf.value) && leaf.value.length === 2 ? leaf.value : [null, null];
    const setAt = (i: number, v: unknown) => onChange(i === 0 ? [v, pair[1]] : [pair[0], v]);
    return (
      <div className="flex items-center gap-1.5">
        {[0, 1].map((i) => (
          <span key={i} className="contents">
            {i === 1 ? <span className="text-xs text-[var(--color-bo-ink-2)]">e</span> : null}
            {field.type === "date" ? (
              <input
                type="date"
                aria-label={`${label}, ${i === 0 ? "da" : "a"}`}
                value={valueText(pair[i])}
                disabled={disabled}
                onChange={(e) => setAt(i, e.target.value || null)}
                className={INPUT}
              />
            ) : (
              <DraftInput
                label={`${label}, ${i === 0 ? "da" : "a"}`}
                value={pair[i]}
                coerce={(raw) => (raw.trim() === "" ? null : coerce(raw))}
                numeric={field.type === "number" || field.type === "unknown"}
                disabled={disabled}
                onChange={(v) => setAt(i, v)}
              />
            )}
          </span>
        ))}
      </div>
    );
  }

  // scalare
  if ((field.type === "enum" || field.type === "list") && field.options && field.options.length > 0) {
    const current = valueText(leaf.value);
    const extra = current && !field.options.map(String).includes(current) ? [current] : [];
    return (
      <select aria-label={label} value={current} disabled={disabled} onChange={(e) => onChange(coerce(e.target.value))} className={INPUT}>
        <option value="">Scegli…</option>
        {field.options.map((o) => (
          <option key={o} value={o}>
            {field.optionLabels?.[o] ? `${field.optionLabels[o]} (${o})` : o}
          </option>
        ))}
        {extra.map((o) => (
          <option key={o} value={o}>
            {o} (non ammesso)
          </option>
        ))}
      </select>
    );
  }
  if (field.type === "boolean") {
    return (
      <select
        aria-label={label}
        value={leaf.value === true ? "true" : leaf.value === false ? "false" : ""}
        disabled={disabled}
        onChange={(e) => onChange(coerce(e.target.value))}
        className={INPUT}
      >
        <option value="true">sì</option>
        <option value="false">no</option>
      </select>
    );
  }
  if (field.type === "date") {
    return (
      <input type="date" aria-label={label} value={valueText(leaf.value)} disabled={disabled} onChange={(e) => onChange(e.target.value)} className={INPUT} />
    );
  }
  return (
    <DraftInput
      label={label}
      value={leaf.value}
      coerce={coerce}
      numeric={field.type === "number"}
      placeholder={field.type === "number" ? "numero" : field.hint ?? "valore"}
      disabled={disabled}
      onChange={onChange}
    />
  );
}

const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** Input di testo che conserva ciò che si digita (es. "12," o "1.") e propaga il valore convertito. */
function DraftInput({
  label,
  value,
  coerce,
  numeric,
  placeholder,
  disabled,
  onChange,
}: {
  label: string;
  value: unknown;
  coerce: (raw: string) => unknown;
  numeric?: boolean;
  placeholder?: string;
  disabled?: boolean;
  onChange: (v: unknown) => void;
}) {
  const [text, setText] = useState(() => valueText(value));
  const [seen, setSeen] = useState<unknown>(value);
  if (!same(seen, value)) {
    // Il valore è cambiato da fuori (cambio di operatore, vista JSON): riallinea il testo.
    setSeen(value);
    if (!same(coerce(text), value)) setText(valueText(value));
  }
  return (
    <input
      aria-label={label}
      value={text}
      inputMode={numeric ? "decimal" : undefined}
      placeholder={placeholder}
      disabled={disabled}
      onChange={(e) => {
        const v = coerce(e.target.value);
        setText(e.target.value);
        setSeen(v);
        onChange(v);
      }}
      className={INPUT}
    />
  );
}

/** Elenco di valori a chip: Invio o virgola aggiungono, Backspace sul campo vuoto toglie l'ultimo. */
function ChipsInput({
  label,
  field,
  values,
  disabled,
  onChange,
}: {
  label: string;
  field: ConditionField;
  values: unknown[];
  disabled?: boolean;
  onChange: (v: unknown[]) => void;
}) {
  const [text, setText] = useState("");
  const commit = (raw: string) => {
    const added = parseList(raw, field).filter((x) => !values.some((v) => same(v, x)));
    if (added.length > 0) onChange([...values, ...added]);
    setText("");
  };
  return (
    <div className={cn(INPUT, "flex min-h-[34px] flex-wrap items-center gap-1 py-1")}>
      {values.map((v, i) => (
        <span key={`${valueText(v)}-${i}`} className="inline-flex items-center gap-0.5 rounded bg-slate-100 px-1.5 py-0.5 text-xs">
          {field.optionLabels?.[String(v)] ?? valueText(v)}
          <button
            type="button"
            aria-label={`Togli ${valueText(v)}`}
            disabled={disabled}
            onClick={() => onChange(values.filter((_, j) => j !== i))}
            className="rounded p-0.5 hover:bg-slate-200"
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
      <input
        aria-label={label}
        value={text}
        disabled={disabled}
        placeholder={values.length === 0 ? "valori separati da virgola" : ""}
        onChange={(e) => {
          if (/[,;]/.test(e.target.value)) commit(e.target.value);
          else setText(e.target.value);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            commit(text);
          } else if (e.key === "Backspace" && text === "" && values.length > 0) {
            onChange(values.slice(0, -1));
          }
        }}
        onBlur={() => text.trim() && commit(text)}
        className="min-w-[6rem] flex-1 bg-transparent text-sm outline-none"
      />
    </div>
  );
}

/** Scelta multipla tra i valori di un enum (in / nin). */
function MultiToggle({
  label,
  field,
  values,
  disabled,
  onChange,
}: {
  label: string;
  field: ConditionField;
  values: unknown[];
  disabled?: boolean;
  onChange: (v: unknown[]) => void;
}) {
  const options = field.options ?? [];
  const selected = new Set(values.map(String));
  const extra = values.filter((v) => !options.includes(String(v)));
  const toggle = (o: string) => {
    const v = coerceScalar(o, field);
    onChange(selected.has(o) ? values.filter((x) => String(x) !== o) : [...values, v]);
  };
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1">
      {options.map((o) => (
        <button
          key={o}
          type="button"
          role="checkbox"
          aria-checked={selected.has(o)}
          disabled={disabled}
          onClick={() => toggle(o)}
          className={cn(
            "rounded-full border px-2 py-0.5 text-xs disabled:opacity-60",
            selected.has(o)
              ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)]/10 font-medium text-[var(--color-bo-accent)]"
              : "border-[var(--color-bo-border)] hover:bg-slate-50",
          )}
        >
          {field.optionLabels?.[o] ?? o}
        </button>
      ))}
      {extra.map((v) => (
        <button
          key={`x-${valueText(v)}`}
          type="button"
          disabled={disabled}
          title="Valore non ammesso: clic per toglierlo"
          onClick={() => onChange(values.filter((x) => !same(x, v)))}
          className="rounded-full border border-red-300 bg-red-50 px-2 py-0.5 text-xs text-red-800"
        >
          {valueText(v)} ×
        </button>
      ))}
    </div>
  );
}
