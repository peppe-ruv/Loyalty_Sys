"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { CheckCircle2, Circle, Plus, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { CampaignSummary } from "@/lib/api/types";
import { INPUT } from "@/components/bo/FormBits";
import { useCan } from "@/components/bo/Can";
import { useIsDemo } from "@/components/bo/PersonaContext";
import { useSideSheet } from "@/components/bo/SideSheet";
import { IconPicker } from "@/components/bo/actiontypes/IconPicker";
import { CATEGORY_LABEL, CUSTOM_CATEGORIES, type ActionType, type ActionTypeRequest } from "@/lib/actiontypes/types";
import {
  campaignImpact,
  emptyRow,
  isBlankRow,
  KIND_LABEL,
  optionsOf,
  rowErrors,
  rowsToSchema,
  sampleFromRows,
  schemaToRows,
  type FieldKind,
  type FieldRow,
} from "@/lib/actiontypes/schema";
import {
  codeChecks,
  integratorTypes,
  isAcceptableCode,
  suggestCode,
  systemNamespaceClash,
  VERB_CHOICES,
} from "@/lib/actiontypes/code";
import { personalKeys, STANDARD_FIELDS, standardRow, technicalName } from "@/lib/actiontypes/fields";
import { ACTION_TEMPLATES, applyTemplate, duplicateFrom } from "@/lib/actiontypes/templates";
import {
  externalSourcesFor,
  isEditableSource,
  isProgramGenerated,
  reachFor,
  sourcesToEnable,
  type SourceRow,
} from "@/lib/actiontypes/sources";
import { addAllowedType } from "@/lib/actiontypes/source-writes";
import { it } from "@/lib/i18n/it";
import { cn } from "@/lib/cn";

const t = it.actions.editor;

/** Riga dell'editor: la riga del JSON Schema più lo stato dell'interfaccia. */
interface UiRow extends FieldRow {
  key: number;
  /** Nome tecnico scritto a mano: l'etichetta non lo cambia più. */
  manual: boolean;
}

let nextKey = 1;
const uiRow = (r: FieldRow, manual = true): UiRow => ({ ...r, key: nextKey++, manual });

export interface SavedInfo {
  /** Fonti su cui l'azione è stata abilitata subito (solo ADMIN, sezione 5). */
  enabledOn: string[];
  /** Fonti spuntate su cui l'abilitazione non è riuscita: chi riceve l'azione lo deve dire. */
  failedOn: string[];
  /** Dopo il salvataggio nessuna fonte esterna accesa la accetta (stessa regola dell'elenco e del dettaglio). */
  onlySimulator: boolean;
}

/**
 * Editor di BO-09 (docs/08 §BO-09): cinque sezioni numerate, tutte visibili (non wizard, §3.2), ciascuna con la sua
 * colonna *Guida*. Crea un'azione personalizzata (`POST /v1/event-types`), modifica una personalizzata (tutto tranne il
 * codice, `PUT` con il contenuto completo: Q-436) o un'azione di sistema (solo nome, descrizione, icona, abilitazione).
 * Usato anche da BO-06 «2 · Quando» dentro un foglio laterale (`context="campaign"`, Q-432).
 */
export function ActionTypeEditor({
  initial,
  onSaved,
  onCancel,
  onUseExisting,
  onDirtyChange,
  prefillName = "",
  context = "program",
}: {
  initial: ActionType | null;
  onSaved: (t: ActionType, info: SavedInfo) => void;
  onCancel: () => void;
  /** «Usa quella esistente» da un modello: l'azione di sistema che copre già il caso. */
  onUseExisting?: (t: ActionType) => void;
  onDirtyChange?: (dirty: boolean) => void;
  prefillName?: string;
  /** Dalla campagna l'azione nasce sempre abilitata: la scelta dei trigger mostra solo quelle abilitate. */
  context?: "program" | "campaign";
}) {
  const qc = useQueryClient();
  const sheet = useSideSheet();
  const isDemo = useIsDemo();
  const canConfigSources = useCan("program.config");
  const isNew = initial == null;
  const isSystem = initial?.origin === "SYSTEM";
  const types = useLhQuery<ActionType[]>("ingestion", "/v1/event-types");
  const sources = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  const campaigns = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns", undefined, { enabled: !isNew });

  const initialRows = useMemo(() => (initial ? schemaToRows(initial.dataSchema) : [emptyRow()]), [initial]);
  const [name, setName] = useState(initial?.name ?? prefillName);
  const [nameTouched, setNameTouched] = useState(false);
  const [description, setDescription] = useState(initial?.description ?? "");
  const [category, setCategory] = useState(initial?.category ?? "ENGAGEMENT");
  const [icon, setIcon] = useState(initial?.icon ?? "zap");
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [codeMode, setCodeMode] = useState<"auto" | "custom">("auto");
  const [customCode, setCustomCode] = useState("");
  const [participle, setParticiple] = useState("");
  // Una riga nuova prende il nome tecnico dall'etichetta; quelle salvate tengono il loro.
  const [rows, setRows] = useState<UiRow[]>(() => (initialRows ?? []).map((r) => uiRow(r, !isNew)));
  const [editedSample, setEditedSample] = useState<string | null>(() =>
    initial?.sampleData ? JSON.stringify(initial.sampleData, null, 2) : null,
  );
  const [picked, setPicked] = useState<string[]>([]);
  const [notice, setNotice] = useState<{ kind: "existing"; type: ActionType; internal: boolean } | { kind: "skipped"; names: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<LhError | null>(null);

  const allTypes = useMemo(() => types.data ?? [], [types.data]);
  const taken = useMemo(() => allTypes.map((x) => x.code).filter((c) => c !== initial?.code), [allTypes, initial]);
  const suggestion = useMemo(() => suggestCode(name, taken, participle || null), [name, taken, participle]);
  const code = !isNew ? initial.code : codeMode === "custom" ? customCode : (suggestion.code ?? "");
  const checks = codeChecks(code, taken);
  const codeOk = !isNew || isAcceptableCode(code, taken);
  const takenBy = isNew && code ? allTypes.find((x) => x.code === code) : undefined;
  const clash = isNew && code ? systemNamespaceClash(code, allTypes) : null;

  // Uno schema non piatto o con regole che le righe perderebbero (creato fuori da questo editor) si modifica solo come
  // JSON: qui lo si conserva com'è.
  const rowsEditable = !isSystem && initialRows != null;
  // Le righe ancora vuote (nome, etichetta e spiegazione) non contano: né nello schema né tra gli errori.
  const filledRows = useMemo(() => rows.filter((r) => !isBlankRow(r)), [rows]);
  const errors = rowsEditable ? rowErrors(rows) : {};
  const blockingErrors = Object.keys(errors).filter((i) => !isBlankRow(rows[Number(i)]));
  const schema = rowsEditable ? rowsToSchema(filledRows) : (initial?.dataSchema ?? null);
  const autoSample = useMemo(() => JSON.stringify(sampleFromRows(filledRows), null, 2), [filledRows]);
  const sampleText = editedSample ?? autoSample;
  const sampleParsed = parseObject(sampleText);
  // SPEC-GAP: Q-435 — anche le chiavi dell'esempio scritto a mano non portano dati personali.
  const samplePersonal = !isSystem && sampleParsed ? personalKeys(sampleParsed) : [];

  const missing = [
    !name.trim() && t.missingItems.name,
    isNew && !codeOk && t.missingItems.code,
    rowsEditable && filledRows.length === 0 && t.missingItems.rows,
    blockingErrors.length > 0 && t.missingItems.rowErrors,
    !isSystem && sampleParsed === undefined && t.missingItems.sample,
    samplePersonal.length > 0 && t.missingItems.samplePersonal,
  ].filter(Boolean) as string[];
  // Finché l'elenco delle azioni non è arrivato, l'unicità del codice non si può controllare.
  const blocked = busy || missing.length > 0 || (isNew && types.isLoading);

  // Modifiche non salvate: il foglio chiede conferma prima di chiudersi (Q-432).
  // Il codice proposto non conta: cambia da solo quando arriva l'elenco delle azioni.
  const snapshot = JSON.stringify([name, description, category, icon, enabled, codeMode, customCode, participle, rows.map(stripUi), editedSample, picked]);
  const [baseline] = useState(snapshot);
  const dirty = snapshot !== baseline;
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  // Impatto: schema cambiato o azione disattivata mentre delle campagne la usano. SPEC-GAP: Q-436.
  const impact = !isNew && campaigns.data ? campaignImpact(initial.code, campaigns.data) : null;
  const baseSchema = useMemo(() => (initialRows ? JSON.stringify(rowsToSchema(initialRows)) : null), [initialRows]);
  const schemaChanged = rowsEditable && baseSchema !== JSON.stringify(schema);
  const disabling = !!initial?.enabled && !enabled;

  const sourceList = sources.data ?? [];
  const programGenerated = isProgramGenerated({ category });
  const externalPickable = sourceList.filter((s) => isEditableSource(s) && s.allowedTypes.length > 0);

  function updateRow(key: number, patch: Partial<UiRow>) {
    setRows((rs) =>
      rs.map((r) => {
        if (r.key !== key) return r;
        const next = { ...r, ...patch };
        if (patch.label !== undefined && !r.manual) next.name = technicalName(patch.label);
        return next;
      }),
    );
  }

  function chooseTemplate(key: string) {
    const template = ACTION_TEMPLATES.find((x) => x.key === key);
    if (!template) return;
    const choice = applyTemplate(template, allTypes);
    if (choice.kind === "existing") {
      setNotice(choice);
      return;
    }
    setNotice(null);
    setName(choice.name);
    setDescription(choice.description);
    setCategory(choice.category);
    setIcon(choice.icon);
    if (choice.code) {
      setCodeMode("custom");
      setCustomCode(choice.code);
    }
    setRows(choice.rows.length ? choice.rows.map((r) => uiRow(r)) : [uiRow(emptyRow(), false)]);
    setEditedSample(null);
  }

  function duplicate(code: string) {
    const source = allTypes.find((x) => x.code === code);
    if (!source) return;
    const copy = duplicateFrom(source);
    setDescription(copy.description);
    setCategory(copy.category);
    setIcon(copy.icon);
    setRows(copy.rows.length ? copy.rows.map((r) => uiRow(r)) : [uiRow(emptyRow(), false)]);
    setEditedSample(null);
    setNotice(copy.skipped.length ? { kind: "skipped", names: copy.skipped.join(", ") } : null);
  }

  async function save() {
    setBusy(true);
    setError(null);
    const finalCode = code.trim();
    const body: ActionTypeRequest = isSystem
      ? { name: name.trim(), description: description.trim() || null, icon: icon || null, enabled }
      : {
          // La PUT sostituisce tutto: si manda sempre il contenuto completo (Q-436).
          ...(isNew ? { code: finalCode } : {}),
          name: name.trim(),
          description: description.trim() || null,
          category,
          icon: icon || null,
          enabled: context === "campaign" ? true : enabled,
          dataSchema: schema,
          sampleData: sampleParsed ?? {},
        };
    let saved: ActionType;
    try {
      saved = await lhFetch<ActionType>("ingestion", isNew ? "/v1/event-types" : `/v1/event-types/${initial.code}`, {
        method: isNew ? "POST" : "PUT",
        body: JSON.stringify(body),
      });
    } catch (e) {
      setError(e instanceof LhError ? e : new LhError(0, "ERROR", t.saveFailed, false));
      setBusy(false);
      return;
    }
    // Sezione 5 (solo ADMIN): abilitazione subito sulle fonti scelte. Ogni fonte si rilegge prima della `PUT`, che
    // manda sempre l'elenco completo (Q-433).
    const enabledOn: string[] = [];
    const failedOn: string[] = [];
    const targets = sourcesToEnable(picked, sourceList, { canConfigSources, isNew, code: saved.code, category: saved.category });
    for (const source of targets) {
      try {
        await addAllowedType(source.code, saved.code);
        enabledOn.push(source.name);
      } catch {
        failedOn.push(source.name);
      }
    }
    await qc.invalidateQueries({ queryKey: ["ingestion"] });
    // L'invalidazione rilegge le fonti: il «solo simulatore» si calcola come nell'elenco e nel dettaglio.
    const fresh = qc.getQueryData<SourceRow[]>(["ingestion", "/v1/sources", {}]) ?? sourceList;
    const acceptsNow = externalSourcesFor(saved.code, fresh).length > 0 || enabledOn.length > 0;
    setBusy(false);
    onSaved(saved, { enabledOn, failedOn, onlySimulator: !isProgramGenerated(saved) && !acceptsNow });
  }

  const fieldError = (f: string) => error?.errors?.find((e) => e.field === f)?.message;
  const cancel = () => (sheet ? sheet.requestClose() : onCancel());
  const integrator = integratorTypes(code || "…");
  const exampleSource = sourceList.find((s) => s.kind === "HTTP")?.code ?? "app";

  return (
    <div className="space-y-5">
      {isSystem ? <p className="rounded bg-[var(--color-bo-bg)] p-2 text-xs text-[var(--color-bo-ink-2)]">{t.systemNotice}</p> : null}
      {isNew && isDemo ? <p className="text-xs text-[var(--color-bo-ink-2)]">{t.demoNotice}</p> : null}

      <nav aria-label={t.sections} className="flex flex-wrap gap-x-3 gap-y-1 text-xs">
        {(
          [
            ["ate-s1", t.s1.title],
            ["ate-s2", t.s2.title],
            ...(isSystem ? [] : [["ate-s3", t.s3.title], ["ate-s4", t.s4.title]]),
            ["ate-s5", t.s5.title],
          ] as [string, string][]
        ).map(([id, title]) => (
          <a key={id} href={`#${id}`} className="underline">
            {title}
          </a>
        ))}
      </nav>

      {isNew ? (
        <div className="grid gap-3 rounded border border-dashed border-[var(--color-bo-border)] p-3 sm:grid-cols-2">
          <p className="text-xs font-medium sm:col-span-2">{t.start.title}</p>
          <label className="block text-xs">
            <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.start.template}</span>
            <select value="" onChange={(e) => chooseTemplate(e.target.value)} className={INPUT} disabled={!types.data}>
              <option value="">{t.start.templatePlaceholder}</option>
              {ACTION_TEMPLATES.map((x) => (
                <option key={x.key} value={x.key}>
                  {x.name}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-xs">
            <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.start.duplicate}</span>
            <select value="" onChange={(e) => duplicate(e.target.value)} className={INPUT} disabled={!types.data}>
              <option value="">{t.start.duplicatePlaceholder}</option>
              {allTypes.map((x) => (
                <option key={x.code} value={x.code}>
                  {x.name} ({x.code})
                </option>
              ))}
            </select>
          </label>
          {notice?.kind === "existing" ? (
            <div role="status" className="rounded bg-sky-50 p-2 text-xs text-sky-900 sm:col-span-2">
              <p>{notice.internal ? t.start.existingInternal(notice.type.name) : t.start.existing(notice.type.name, notice.type.code)}</p>
              {onUseExisting && (!notice.internal || context === "campaign") ? (
                <button type="button" onClick={() => onUseExisting(notice.type)} className="mt-1 rounded border border-sky-300 px-2 py-0.5 font-medium">
                  {t.start.useExisting}
                </button>
              ) : null}
            </div>
          ) : notice?.kind === "skipped" ? (
            <p role="status" className="rounded bg-amber-50 p-2 text-xs text-amber-900 sm:col-span-2">
              {t.start.skipped(notice.names)}
            </p>
          ) : null}
        </div>
      ) : null}

      <EditorSection id="ate-s1" title={t.s1.title} guide={t.s1.guide}>
        <label className="block text-sm">
          <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{t.s1.name}</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => setNameTouched(true)}
            maxLength={60}
            className={INPUT}
            aria-invalid={nameTouched && !name.trim()}
          />
          {nameTouched && !name.trim() ? <span className="text-xs text-red-700">{t.s1.nameRequired}</span> : null}
        </label>
        <label className="block text-sm">
          <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{t.s1.description}</span>
          <input value={description} onChange={(e) => setDescription(e.target.value)} className={INPUT} />
          <span className="mt-1 block text-xs text-[var(--color-bo-ink-2)]">{t.s1.descriptionHint}</span>
        </label>
        <label className="block text-sm">
          <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{t.s1.category}</span>
          <select value={category} onChange={(e) => setCategory(e.target.value)} disabled={isSystem} className={INPUT}>
            {(isSystem ? [category] : CUSTOM_CATEGORIES).map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c] ?? c}
              </option>
            ))}
          </select>
        </label>
        <IconPicker value={icon} onChange={setIcon} />
        {context === "program" ? (
          <div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              {t.s1.enabled}
            </label>
            <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s1.enabledHint}</p>
          </div>
        ) : null}
      </EditorSection>

      <EditorSection id="ate-s2" title={t.s2.title} guide={t.s2.guide}>
        {!isNew ? (
          <div>
            <p className="font-mono text-sm">{code}</p>
            <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s2.readOnly}</p>
          </div>
        ) : (
          <div className="space-y-2">
            {codeMode === "custom" ? (
              <label className="block text-sm">
                <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{t.s2.label}</span>
                <input
                  value={customCode}
                  onChange={(e) => setCustomCode(e.target.value.trim())}
                  className={`${INPUT} font-mono`}
                  aria-invalid={!codeOk}
                  aria-describedby="ate-code-checks"
                />
              </label>
            ) : (
              <p className="text-sm">
                <span className="text-xs text-[var(--color-bo-ink-2)]">{t.s2.label}: </span>
                <span className="font-mono">{code || "—"}</span>
              </p>
            )}
            {codeMode === "auto" && !code && !suggestion.needsVerb ? (
              <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s2.waitingName}</p>
            ) : null}
            {codeMode === "auto" && suggestion.needsVerb ? (
              <label className="block text-sm">
                <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{t.s2.verbQuestion}</span>
                <select
                  value={participle}
                  onChange={(e) => setParticiple(e.target.value)}
                  className={INPUT}
                  aria-label={t.s2.verbQuestion}
                  aria-describedby="ate-verb-hint"
                >
                  <option value="">{t.s2.verbPlaceholder}</option>
                  {VERB_CHOICES.map((v) => (
                    <option key={v.participle} value={v.participle}>
                      {v.label} ({v.participle})
                    </option>
                  ))}
                </select>
                <span id="ate-verb-hint" className="mt-1 block text-xs text-[var(--color-bo-ink-2)]">
                  {t.s2.verbHint}
                </span>
              </label>
            ) : null}
            <button
              type="button"
              className="text-xs underline"
              onClick={() => {
                if (codeMode === "auto") {
                  setCustomCode(code);
                  setCodeMode("custom");
                } else setCodeMode("auto");
              }}
            >
              {codeMode === "auto" ? t.s2.customize : t.s2.useSuggested}
            </button>
            <ul id="ate-code-checks" aria-label={t.s2.label} className="space-y-0.5 text-xs">
              {checks.map((c) => {
                // L'unicità si verifica sull'elenco delle azioni: finché non arriva resta «in verifica».
                const pending = c.key === "unique" && types.isLoading;
                const ok = c.ok && !pending;
                return (
                  <li key={c.key} className={cn("flex items-center gap-1", ok ? "text-emerald-800" : "text-[var(--color-bo-ink-2)]")}>
                    {ok ? <CheckCircle2 className="size-3.5" aria-hidden /> : <Circle className="size-3.5" aria-hidden />}
                    <span>{t.s2.checks[c.key]}</span>
                    <span className="sr-only">: {pending ? t.s2.checkPending : ok ? t.s2.checkOk : t.s2.checkKo}</span>
                  </li>
                );
              })}
            </ul>
            {takenBy ? <p className="text-xs text-red-700">{t.s2.taken(takenBy.name)}</p> : null}
            {clash ? <p className="text-xs text-amber-800">{t.s2.namespace(clash)}</p> : null}
            {fieldError("code") ? <p className="text-xs text-red-700">{fieldError("code")}</p> : null}
          </div>
        )}
        <p className="rounded bg-[var(--color-bo-bg)] p-2 text-xs">
          {t.s2.integrator} <code className="font-mono">type = {integrator.short}</code> {t.s2.integratorOr}{" "}
          <code className="font-mono">{integrator.full}</code>.
        </p>
      </EditorSection>

      {!isSystem ? (
        <EditorSection
          id="ate-s3"
          title={t.s3.title}
          guide={
            <>
              <p>{t.s3.guide}</p>
              <ol className="mt-2 list-decimal space-y-1 pl-4">
                {t.s3.questions.map((q) => (
                  <li key={q}>{q}</li>
                ))}
              </ol>
              <p className="mt-2">{t.s3.required}</p>
            </>
          }
        >
          {rowsEditable ? (
            <>
              {rows.length === 0 ? <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s3.none}</p> : null}
              <ol className="space-y-3">
                {rows.map((r, i) => {
                  const err = errors[i];
                  // Una riga ancora vuota non è un errore: non conta finché non la compili.
                  const showErr = err && !isBlankRow(r);
                  return (
                    <li key={r.key} className="space-y-2 rounded border border-[var(--color-bo-border)] p-2">
                      <div className="grid gap-2 sm:grid-cols-2">
                        <label className="block text-xs">
                          <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s3.label}</span>
                          <input
                            value={r.label ?? ""}
                            onChange={(e) => updateRow(r.key, { label: e.target.value })}
                            placeholder={t.s3.labelPlaceholder}
                            className={INPUT}
                            aria-label={`${t.s3.label} ${i + 1}`}
                          />
                        </label>
                        <label className="block text-xs">
                          <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s3.technicalName}</span>
                          <input
                            value={r.name}
                            onChange={(e) => updateRow(r.key, { name: e.target.value.trim(), manual: true })}
                            className={`${INPUT} font-mono`}
                            aria-label={`${t.s3.technicalName} ${i + 1}`}
                            aria-invalid={!!showErr}
                          />
                        </label>
                        <label className="block text-xs">
                          <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s3.kind}</span>
                          <select
                            value={r.kind}
                            onChange={(e) => updateRow(r.key, { kind: e.target.value as FieldKind })}
                            className={INPUT}
                            aria-label={`${t.s3.kind} ${i + 1}`}
                          >
                            {(Object.keys(KIND_LABEL) as FieldKind[]).map((k) => (
                              <option key={k} value={k}>
                                {KIND_LABEL[k]}
                              </option>
                            ))}
                          </select>
                          <span className="mt-1 block text-[var(--color-bo-ink-2)]">{t.s3.kinds[r.kind]}</span>
                        </label>
                        {r.kind === "enum" ? (
                          <label className="block text-xs">
                            <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s3.values}</span>
                            <input
                              value={r.options}
                              onChange={(e) => updateRow(r.key, { options: e.target.value })}
                              placeholder={t.s3.valuesPlaceholder}
                              className={INPUT}
                              aria-label={`${t.s3.values} ${i + 1}`}
                            />
                          </label>
                        ) : null}
                        <label className="block text-xs sm:col-span-2">
                          <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s3.fieldDescription}</span>
                          <input
                            value={r.description ?? ""}
                            onChange={(e) => updateRow(r.key, { description: e.target.value })}
                            className={INPUT}
                            aria-label={`${t.s3.fieldDescription} ${i + 1}`}
                          />
                        </label>
                      </div>
                      <div className="flex items-center justify-between">
                        <label className="flex items-center gap-2 text-xs">
                          <input type="checkbox" checked={r.required} onChange={(e) => updateRow(r.key, { required: e.target.checked })} />
                          {t.s3.requiredLabel}
                        </label>
                        <button
                          type="button"
                          onClick={() => setRows((rs) => rs.filter((x) => x.key !== r.key))}
                          aria-label={t.s3.remove(i + 1)}
                          className="rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-[var(--color-bo-bg)]"
                        >
                          <Trash2 size={14} aria-hidden />
                        </button>
                      </div>
                      {showErr ? (
                        <p role="alert" className="text-xs text-red-700">
                          {err}
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => setRows((rs) => [...rs, uiRow(emptyRow(), false)])}
                  className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
                >
                  <Plus size={12} aria-hidden /> {t.s3.add}
                </button>
                <select
                  value=""
                  aria-label={t.s3.standard}
                  onChange={(e) => {
                    const f = e.target.value;
                    if (!f || rows.some((r) => r.name === f)) return;
                    setRows((rs) => [...rs.filter((r) => r.name !== "" || (r.label ?? "") !== ""), uiRow(standardRow(f))]);
                  }}
                  className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
                >
                  <option value="">{t.s3.standardPlaceholder}</option>
                  {STANDARD_FIELDS.map((f) => (
                    <option key={f.name} value={f.name} disabled={rows.some((r) => r.name === f.name)}>
                      {f.label} ({f.name})
                    </option>
                  ))}
                </select>
              </div>
            </>
          ) : (
            <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s3.nested}</p>
          )}
        </EditorSection>
      ) : null}

      {!isSystem ? (
        <EditorSection id="ate-s4" title={t.s4.title} guide={t.s4.guide}>
          <figure>
            <figcaption className="mb-1 text-xs font-medium">{t.s4.system}</figcaption>
            <pre className="max-h-56 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
              {JSON.stringify(
                {
                  specversion: "1.0",
                  id: "evt-0001",
                  source: `urn:loyaltyhub:source:${exampleSource}`,
                  type: code || "…",
                  subject: "member:MBR-000003",
                  time: "2026-09-28T10:15:00Z",
                  data: sampleParsed ?? {},
                },
                null,
                2,
              )}
            </pre>
          </figure>
          <div>
            <p className="mb-1 text-xs font-medium">{t.s4.campaign}</p>
            {filledRows.length === 0 || !rowsEditable ? (
              <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s4.campaignEmpty}</p>
            ) : (
              <ul className="space-y-0.5 text-xs">
                {filledRows
                  .filter((r) => r.name)
                  .map((r) => (
                    <li key={r.key}>
                      <span className="font-medium">{t.s4.if}</span> · {r.label || r.name} (<code className="font-mono">data.{r.name}</code>){" "}
                      {conditionHint(r)}
                    </li>
                  ))}
              </ul>
            )}
          </div>
          {editedSample != null ? (
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="text-[var(--color-bo-ink-2)]">{t.s4.edited}</span>
              <button type="button" onClick={() => setEditedSample(null)} className="underline">
                {t.s4.restore}
              </button>
            </div>
          ) : null}
          <details>
            <summary className="cursor-pointer text-xs font-medium">{t.s4.advanced}</summary>
            <div className="mt-2 space-y-2">
              <label className="block text-xs">
                <span className="mb-1 block text-[var(--color-bo-ink-2)]">{t.s4.sample}</span>
                <textarea
                  value={sampleText}
                  onChange={(e) => setEditedSample(e.target.value)}
                  rows={5}
                  className={`${INPUT} font-mono text-xs`}
                  aria-invalid={sampleParsed === undefined}
                />
              </label>
              <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s4.schema}</p>
              <pre className="max-h-56 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">{JSON.stringify(schema, null, 2)}</pre>
            </div>
          </details>
          {sampleParsed === undefined ? <p className="text-xs text-red-700">{t.s4.sampleInvalid}</p> : null}
          {samplePersonal.length > 0 ? (
            <p role="alert" className="text-xs text-red-700">
              {t.s4.samplePersonal(samplePersonal.join(", "))}
            </p>
          ) : null}
          {fieldError("sampleData") ? <p className="text-xs text-red-700">{fieldError("sampleData")}</p> : null}
          {fieldError("dataSchema") ? <p className="text-xs text-red-700">{fieldError("dataSchema")}</p> : null}
        </EditorSection>
      ) : null}

      <EditorSection id="ate-s5" title={t.s5.title} guide={t.s5.guide}>
        {sources.isLoading ? (
          <p role="status" className="text-xs text-[var(--color-bo-ink-2)]">
            {t.s5.loading}
          </p>
        ) : sources.isError ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{it.actions.detail.sourcesUnavailable}</p>
        ) : sourceList.length === 0 ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s5.noExternal}</p>
        ) : (
          <ul className="space-y-1 text-xs">
            {programGenerated ? <li className="text-[var(--color-bo-ink-2)]">{it.actions.detail.programGenerated}</li> : null}
            {reachFor({ code: code || "\u0000", category }, sourceList).map(({ source, status }) => {
              const pickable = isNew && canConfigSources && !programGenerated && externalPickable.includes(source);
              return (
                <li key={source.code} className="flex flex-wrap items-center justify-between gap-2">
                  {pickable ? (
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={picked.includes(source.code)}
                        onChange={(e) =>
                          setPicked((p) => (e.target.checked ? [...p, source.code] : p.filter((c) => c !== source.code)))
                        }
                      />
                      {source.name}
                    </label>
                  ) : (
                    <span>{source.name}</span>
                  )}
                  <span className={cn(status === "NOT_ALLOWED" && !picked.includes(source.code) ? "text-amber-800" : "text-[var(--color-bo-ink-2)]")}>
                    {picked.includes(source.code) ? it.actions.detail.reachStatus.ACCEPTS : it.actions.detail.reachStatus[status]}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
        {isNew ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{canConfigSources ? t.s5.pickAdmin : t.s5.notAdmin}</p>
        ) : null}
      </EditorSection>

      {impact && impact.using.length > 0 && (schemaChanged || disabling) ? (
        <div role="status" className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
          <p>{disabling ? t.disableImpact(impact.using.length, impact.active) : t.impact(impact.using.length, impact.active)}</p>
          <ul className="mt-1 flex flex-wrap gap-x-3">
            {impact.using.map((c) => (
              <li key={c.id}>
                <Link href={`/backoffice/campaigns/${c.id}`} className="underline">
                  {c.name}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="rounded bg-red-50 p-2 text-sm text-red-800">
          {error.code === "EVENT_TYPE_EXISTS" ? t.exists : error.detail || error.code}
        </p>
      ) : null}
      {isNew ? <p className="text-xs text-[var(--color-bo-ink-2)]">{t.s2.permanence}</p> : null}
      {missing.length > 0 && !busy ? (
        <p id="ate-missing" className="text-xs text-[var(--color-bo-ink-2)]">
          {t.missing} {missing.join(" · ")}
        </p>
      ) : null}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={save}
          disabled={blocked}
          aria-describedby={missing.length > 0 ? "ate-missing" : undefined}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {busy ? t.saving : isNew ? t.create : t.save}
        </button>
        <button type="button" onClick={cancel} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
          {t.cancel}
        </button>
      </div>
    </div>
  );
}

function EditorSection({ id, title, guide, children }: { id: string; title: string; guide: React.ReactNode; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="grid gap-3 border-t border-[var(--color-bo-border)] pt-3 sm:grid-cols-[minmax(0,1fr)_170px]">
      <div className="min-w-0 space-y-3">
        <h3 id={`${id}-title`} className="text-sm font-semibold">
          {title}
        </h3>
        {children}
      </div>
      <aside aria-label={`${it.actions.editor.guide}: ${title}`} className="rounded bg-[var(--color-bo-bg)] p-2 text-xs text-[var(--color-bo-ink-2)]">
        <p className="mb-1 font-medium text-[var(--color-bo-ink)]">{it.actions.editor.guide}</p>
        {typeof guide === "string" ? <p>{guide}</p> : guide}
      </aside>
    </section>
  );
}

/** Come il costruttore di condizioni proporrà il campo (sezione 4, «Come la vedrà la campagna»). */
function conditionHint(r: FieldRow): string {
  const h = it.actions.editor.s4.hints;
  switch (r.kind) {
    case "number":
    case "integer":
      return h.number;
    case "boolean":
      return h.boolean;
    case "date":
      return h.date;
    case "enum":
      return h.enum(optionsOf(r).join(", ") || "…");
    default:
      return h.string;
  }
}

function stripUi(r: UiRow): FieldRow {
  return { name: r.name, kind: r.kind, required: r.required, options: r.options, label: r.label ?? "", description: r.description ?? "" };
}

function parseObject(text: string): Record<string, unknown> | undefined {
  try {
    const v = JSON.parse(text || "{}");
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

