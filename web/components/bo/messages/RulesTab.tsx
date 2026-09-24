"use client";

import { useState } from "react";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import { FACTS, factInfo, factLabel } from "@/lib/messages/facts";
import {
  COMPARATORS,
  conditionProblems,
  describeCondition,
  fromBuilder,
  needsValue,
  parseConditionJson,
  toBuilder,
  type BuilderModel,
  type BuilderRow,
} from "@/lib/messages/condition";
import { errorsByField } from "@/lib/messages/templates";
import type { ConditionNode, MessageTemplate, NotificationRule, RuleRequest } from "@/lib/messages/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can, useCan } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { Field, INPUT } from "@/components/bo/FormBits";
import { GeneratedText } from "@/components/bo/GeneratedSentence";
import { CodeText } from "@/components/bo/primitives";
import { cn } from "@/lib/cn";

// Campi affiancati: lo stile dei campi senza la larghezza piena.
const INLINE = INPUT.replace("w-full ", "");

// BO-19 `rules` (docs/08 §BO-19, F-MSG-01): regole fatto → template con condizione opzionale su data.* (stesso formato
// delle condizioni di campagna) e interruttore. Il tipo di fatto si sceglie dal catalogo di docs/05 §5; la condizione con
// un costruttore semplice (righe in "tutte"/"almeno una") o in JSON; gli errori del servizio tornano sui campi.
export function RulesTab() {
  const rules = useLhQuery<NotificationRule[]>("engagement", "/v1/notification-rules");
  const templates = useLhQuery<MessageTemplate[]>("engagement", "/v1/message-templates");
  const [editing, setEditing] = useState<NotificationRule | "new" | null>(null);
  const tplName = (code: string) => (templates.data ?? []).find((t) => t.code === code)?.name ?? code;

  const columns: Column<NotificationRule>[] = [
    { key: "code", header: "Regola", render: (r) => <CodeText>{r.code}</CodeText> },
    {
      key: "fact",
      header: "Quando arriva",
      render: (r) => (
        <span className="flex flex-col">
          <span>{factLabel(r.factType)}</span>
          <span className="font-mono text-[11px] text-[var(--color-bo-ink-2)]">{r.factType}</span>
        </span>
      ),
    },
    { key: "condition", header: "Condizione", render: (r) => <span className="font-mono text-xs">{describeCondition(r.condition)}</span> },
    {
      key: "template",
      header: "Invia",
      render: (r) => (
        <span className="flex flex-col">
          <span>{tplName(r.templateCode)}</span>
          <CodeText>{r.templateCode}</CodeText>
        </span>
      ),
    },
    { key: "enabled", header: "Abilitata", render: (r) => <EnabledToggle rule={r} /> },
  ];

  return (
    <>
      <div className="mb-3 flex justify-end">
        <Can capability="content.write" mode="disable">
          <button onClick={() => setEditing("new")} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
            Nuova regola
          </button>
        </Can>
      </div>
      <QueryState query={rules} service="engagement" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna regola" emptyHint="Collega un tipo di fatto a un template per inviare messaggi.">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(r) => r.id} onRowClick={setEditing} />}
      </QueryState>
      <SideSheet
        open={editing != null}
        title={editing === "new" ? "Nuova regola di notifica" : editing ? <span className="flex flex-col"><span className="font-semibold">Regola</span><CodeText>{editing.code}</CodeText></span> : ""}
        onClose={() => setEditing(null)}
      >
        {editing != null ? (
          <RuleEditor
            key={editing === "new" ? "new" : editing.id}
            rule={editing === "new" ? null : editing}
            templates={templates.data ?? []}
            onDone={() => setEditing(null)}
          />
        ) : null}
      </SideSheet>
    </>
  );
}

/** Interruttore in riga: PUT {enabled, version}; senza content.write è solo un'indicazione. */
function EnabledToggle({ rule: r }: { rule: NotificationRule }) {
  const canEdit = useCan("content.write");
  const toggle = useLhMutation<NotificationRule, RuleRequest>("engagement", "PUT", () => `/v1/notification-rules/${r.id}`);
  const on = toggle.isPending ? !r.enabled : r.enabled;
  return (
    <span className="inline-flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={on ? "Disattiva la regola" : "Attiva la regola"}
        disabled={!canEdit || toggle.isPending}
        title={canEdit ? undefined : "Richiede uno dei ruoli: ADMIN, MARKETING"}
        onClick={() => toggle.mutate({ enabled: !r.enabled, version: r.version })}
        className={cn(
          "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-50",
          on ? "bg-emerald-500" : "bg-slate-300",
        )}
      >
        <span className={cn("inline-block size-4 rounded-full bg-white shadow transition-transform", on ? "translate-x-4" : "translate-x-0.5")} />
      </button>
      {toggle.isError ? <span role="alert" className="text-xs text-red-700">{toggle.error.detail || toggle.error.code}</span> : null}
    </span>
  );
}

type Mode = "builder" | "json";

function RuleEditor({ rule: r, templates, onDone }: { rule: NotificationRule | null; templates: MessageTemplate[]; onDone: () => void }) {
  const canEdit = useCan("content.write");
  const isNew = r == null;
  const [code, setCode] = useState(r?.code ?? "");
  const [factType, setFactType] = useState(r?.factType ?? "wallet.points.earned");
  const [templateCode, setTemplateCode] = useState(r?.templateCode ?? templates[0]?.code ?? "");
  const [enabled, setEnabled] = useState(r?.enabled ?? true);
  const initialBuilder = toBuilder(r?.condition ?? null);
  const [mode, setMode] = useState<Mode>(initialBuilder ? "builder" : "json");
  const [builder, setBuilder] = useState<BuilderModel>(initialBuilder ?? { op: "all", rows: [] });
  const [json, setJson] = useState(r?.condition ? JSON.stringify(r.condition, null, 2) : "");
  const [serverError, setServerError] = useState<LhError | null>(null);

  // Condizione corrente secondo la modalità, con gli errori di forma (stesse regole del servizio).
  const parsed: { condition: ConditionNode | null; error: string | null } =
    mode === "builder" ? { condition: fromBuilder(builder), error: null } : parseConditionJson(json);
  const problems = parsed.error ? [parsed.error] : conditionProblems(parsed.condition);

  const save = useLhMutation<NotificationRule, RuleRequest>(
    "engagement",
    isNew ? "POST" : "PUT",
    () => (isNew ? "/v1/notification-rules" : `/v1/notification-rules/${r.id}`),
    { onSuccess: onDone },
  );

  function switchMode(next: Mode) {
    if (next === mode) return;
    if (next === "json") {
      setJson(parsed.condition ? JSON.stringify(parsed.condition, null, 2) : "");
      setMode("json");
    } else {
      const b = toBuilder(parsed.condition);
      if (b && !parsed.error) {
        setBuilder(b);
        setMode("builder");
      }
    }
  }

  const fields = Object.keys(factInfo(factType)?.sample ?? {}).map((k) => `data.${k}`);
  const serverFields = errorsByField(serverError?.errors);
  const conditionErrors = [...problems, ...(serverFields.condition ?? [])];
  const tpl = templates.find((t) => t.code === templateCode);
  const sentence = `Quando arriva **${factLabel(factType)}**${parsed.condition && !parsed.error ? ` e ${describeCondition(parsed.condition)}` : ""}, invia **${tpl?.name ?? templateCode}** al membro${enabled ? "" : " (regola disattivata)"}.`;
  const builderAvailable = mode === "builder" || (!parsed.error && toBuilder(parsed.condition) != null);

  return (
    <form
      className="space-y-3"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setServerError(null);
        if (problems.length > 0) return;
        save.mutate(
          {
            code: isNew && code.trim() ? code.trim().toUpperCase() : undefined,
            factType,
            // {} = nessuna condizione (esplicito: un campo assente lascerebbe invariata quella salvata)
            condition: parsed.condition ?? {},
            templateCode,
            enabled,
            version: r?.version,
          },
          { onError: setServerError },
        );
      }}
    >
      <GeneratedText text={sentence} />
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Tipo di fatto">
          <select value={factType} disabled={!canEdit} onChange={(e) => setFactType(e.target.value)} className={INPUT} aria-invalid={!!serverFields.factType}>
            {!FACTS.some((f) => f.type === factType) ? <option value={factType}>{factType}</option> : null}
            {FACTS.map((f) => <option key={f.type} value={f.type}>{f.label} · {f.type}</option>)}
          </select>
          {serverFields.factType ? <Errors messages={serverFields.factType} /> : null}
        </Field>
        <Field label="Codice" hint={isNew ? "Facoltativo: se vuoto viene generato (NR-…)." : undefined}>
          <input value={code} disabled={!canEdit || !isNew} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="NR-…" className={`${INPUT} font-mono`} aria-invalid={!!serverFields.code} />
          {serverFields.code ? <Errors messages={serverFields.code} /> : null}
        </Field>
      </div>

      <Field group label="Condizione (facoltativa)">
        <div className="mb-2 flex gap-1 text-xs">
          {(["builder", "json"] as Mode[]).map((m) => (
            <button
              key={m}
              type="button"
              disabled={m === "builder" && !builderAvailable}
              title={m === "builder" && !builderAvailable ? "Condizione troppo articolata per il costruttore semplice: modificala in JSON" : undefined}
              onClick={() => switchMode(m)}
              className={cn("rounded border px-2 py-0.5 disabled:opacity-40", mode === m ? "border-[var(--color-bo-accent)] font-medium text-[var(--color-bo-accent)]" : "border-[var(--color-bo-border)]")}
            >
              {m === "builder" ? "Costruttore" : "JSON"}
            </button>
          ))}
        </div>
        {mode === "builder" ? (
          <ConditionBuilder model={builder} fields={fields} disabled={!canEdit} onChange={setBuilder} />
        ) : (
          <textarea
            rows={6}
            value={json}
            disabled={!canEdit}
            onChange={(e) => setJson(e.target.value)}
            placeholder='{"field":"data.currency","cmp":"eq","value":"PTS"}'
            className={`${INPUT} font-mono text-xs`}
            aria-invalid={conditionErrors.length > 0}
          />
        )}
        {conditionErrors.length ? <Errors messages={conditionErrors} /> : null}
        <datalist id="rule-fields">{fields.map((f) => <option key={f} value={f} />)}</datalist>
      </Field>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Template">
          <select value={templateCode} disabled={!canEdit} onChange={(e) => setTemplateCode(e.target.value)} className={INPUT} aria-invalid={!!serverFields.templateCode}>
            {!templates.some((t) => t.code === templateCode) ? <option value={templateCode}>{templateCode || "—"}</option> : null}
            {templates.map((t) => <option key={t.code} value={t.code}>{t.name} · {t.code}</option>)}
          </select>
          {serverFields.templateCode ? <Errors messages={serverFields.templateCode} /> : null}
        </Field>
        <Field group label="Stato">
          <label className="inline-flex items-center gap-2 text-sm">
            <input type="checkbox" checked={enabled} disabled={!canEdit} onChange={(e) => setEnabled(e.target.checked)} />
            Abilitata
          </label>
        </Field>
      </div>

      {canEdit ? (
        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" disabled={save.isPending || problems.length > 0} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {isNew ? "Crea regola" : "Salva"}
          </button>
          {serverError?.status === 409 && serverError.code === "VERSION_CONFLICT" ? (
            <span role="alert" className="text-xs text-red-700">Qualcun altro ha modificato la regola: chiudi e riapri per ricaricarla.</span>
          ) : serverError && Object.keys(serverFields).length === 0 ? (
            <span role="alert" className="text-xs text-red-700">{serverError.detail || serverError.code}</span>
          ) : null}
        </div>
      ) : (
        <p className="text-xs text-[var(--color-bo-ink-2)]">Sola lettura: le regole si modificano con il ruolo ADMIN o MARKETING.</p>
      )}
    </form>
  );
}

function ConditionBuilder({
  model,
  fields,
  disabled,
  onChange,
}: {
  model: BuilderModel;
  fields: string[];
  disabled: boolean;
  onChange: (m: BuilderModel) => void;
}) {
  const setRow = (i: number, patch: Partial<BuilderRow>) => onChange({ ...model, rows: model.rows.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  return (
    <div className="space-y-2">
      {model.rows.length > 1 ? (
        <select
          aria-label="Combinazione"
          value={model.op}
          disabled={disabled}
          onChange={(e) => onChange({ ...model, op: e.target.value as BuilderModel["op"] })}
          className={cn(INLINE, "w-auto")}
        >
          <option value="all">Tutte le condizioni</option>
          <option value="any">Almeno una condizione</option>
        </select>
      ) : null}
      {model.rows.length === 0 ? <p className="text-xs text-[var(--color-bo-ink-2)]">Nessuna condizione: la regola scatta a ogni fatto di questo tipo.</p> : null}
      {model.rows.map((r, i) => (
        <div key={i} className="flex flex-wrap items-center gap-1.5">
          <input
            aria-label="Campo"
            list="rule-fields"
            value={r.field}
            disabled={disabled}
            onChange={(e) => setRow(i, { field: e.target.value })}
            placeholder="data.campo"
            className={cn(INLINE, "w-44 font-mono text-xs")}
          />
          <select aria-label="Confronto" value={r.cmp} disabled={disabled} onChange={(e) => setRow(i, { cmp: e.target.value })} className={cn(INLINE, "w-40")}>
            {COMPARATORS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
          {needsValue(r.cmp) ? (
            <input
              aria-label="Valore"
              value={r.value}
              disabled={disabled}
              onChange={(e) => setRow(i, { value: e.target.value })}
              placeholder={r.cmp === "in" || r.cmp === "nin" ? '["A","B"]' : r.cmp === "between" ? "[10, 100]" : "valore"}
              className={cn(INLINE, "w-36 font-mono text-xs")}
            />
          ) : null}
          {!disabled ? (
            <button type="button" onClick={() => onChange({ ...model, rows: model.rows.filter((_, j) => j !== i) })} className="text-xs text-red-700 hover:underline">
              rimuovi
            </button>
          ) : null}
        </div>
      ))}
      {!disabled ? (
        <button
          type="button"
          onClick={() => onChange({ ...model, rows: [...model.rows, { field: fields[0] ?? "data.", cmp: "eq", value: "" }] })}
          className="text-xs text-[var(--color-bo-accent)] hover:underline"
        >
          + Aggiungi condizione
        </button>
      ) : null}
    </div>
  );
}

function Errors({ messages }: { messages: string[] }) {
  return (
    <span role="alert" className="mt-1 block text-xs text-red-700">
      {messages.join(" · ")}
    </span>
  );
}
