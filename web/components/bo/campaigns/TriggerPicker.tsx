"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Plus, RefreshCw, Search, X } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { EventType } from "@/lib/api/types";
import type { ActionType } from "@/lib/actiontypes/types";
import { categoryLabel, CATEGORY_ORDER } from "@/lib/actiontypes/types";
import { actionIcon } from "@/lib/icons/action-icons";
import { INPUT } from "@/components/bo/FormBits";
import { DegradedBox, EmptyState } from "@/components/bo/primitives";
import { Can, useCan } from "@/components/bo/Can";
import { useIsDemo } from "@/components/bo/PersonaContext";
import { SideSheet } from "@/components/bo/SideSheet";
import { ActionTypeEditor, type SavedInfo } from "@/components/bo/actiontypes/ActionTypeEditor";
import { it } from "@/lib/i18n/it";
import { cn } from "@/lib/cn";

// Scelta dei trigger di una campagna (docs/08 §BO-06 sezione "2 Quando", F-ING-06): azioni abilitate da
// `ingestion GET /v1/event-types`, raggruppate per categoria, multi-scelta con icona, ricerca e pill «Personalizzata».
// Scorciatoia verso BO-09 (Q-432): *+ Crea una nuova azione*, *Crea «ricerca» come nuova azione* e l'azione dello stato
// vuoto aprono l'editor di BO-09 in un foglio laterale dentro la campagna, così la bozza non si perde; al salvataggio
// l'azione entra nei trigger. I link verso altre schermate (simulatore, Azioni e fonti) si aprono in una nuova scheda
// per lo stesso motivo. *Aggiorna* rilegge le azioni create in un'altra scheda (niente refetch al focus).
// Se un trigger scelto non ha `amount` ma la bozza lo usa (condizione o effetto `PER_AMOUNT`), avviso. SPEC-GAP: Q-432.
// Se ingestion dorme o risponde con errore si torna al campo di testo con i codici (stato degraded, docs/07 §6).

const T = it.actions.picker;
const ACTIONS_HREF = "/backoffice/program/actions";

/** Azioni abilitate filtrate dal testo e raggruppate per categoria (ordine fisso, poi alfabetico). */
export function groupEventTypes(types: EventType[], query: string): { category: string; types: EventType[] }[] {
  const q = query.trim().toLowerCase();
  const visible = types.filter(
    (t) =>
      t.enabled &&
      (!q || t.name.toLowerCase().includes(q) || t.code.toLowerCase().includes(q) || (t.description ?? "").toLowerCase().includes(q)),
  );
  const by = new Map<string, EventType[]>();
  for (const t of visible) {
    const c = t.category ?? "";
    by.set(c, [...(by.get(c) ?? []), t]);
  }
  const order = CATEGORY_ORDER as readonly string[];
  const rank = (c: string) => {
    const i = order.indexOf(c);
    return i < 0 ? order.length - 0.5 : i;
  };
  return [...by.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([category, list]) => ({ category, types: list }));
}

/** L'azione ha il campo `amount` (condizione d'esempio e effetto «per importo» della bozza). */
export function hasAmountField(type: Pick<ActionType, "dataSchema">): boolean {
  const props = (type.dataSchema?.properties ?? {}) as Record<string, unknown>;
  return Object.prototype.hasOwnProperty.call(props, "amount");
}

interface Created {
  name: string;
  code: string;
  onlySimulator: boolean;
  failedOn: string[];
}

/** Link verso un'altra schermata del backoffice: nuova scheda, così la bozza della campagna resta aperta (Q-432). */
function NewTabLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} target="_blank" rel="noopener noreferrer" className="underline">
      {children} <span className="opacity-80">{T.newTab}</span>
    </Link>
  );
}

export function TriggerPicker({
  value,
  onChange,
  disabled,
  conditionUsesAmount = false,
  effectUsesAmount = false,
  onDropAmountExample,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  /** Le condizioni della bozza usano `data.amount`. */
  conditionUsesAmount?: boolean;
  /** Un effetto della bozza usa `data.amount` (`PER_AMOUNT`). */
  effectUsesAmount?: boolean;
  /** Toglie la condizione d'esempio `data.amount ≥ 1`; assente = non c'è più. */
  onDropAmountExample?: () => void;
}) {
  const query = useLhQuery<EventType[]>("ingestion", "/v1/event-types");
  const canCreate = useCan("actiontype.custom");
  const isDemo = useIsDemo();
  const [search, setSearch] = useState("");
  const searchInput = useRef<HTMLInputElement>(null);
  const [creating, setCreating] = useState<{ name: string } | null>(null);
  const [dirty, setDirty] = useState(false);
  const [created, setCreated] = useState<Created | null>(null);
  const onDirtyChange = useCallback((d: boolean) => setDirty(d), []);
  const types = useMemo(() => query.data ?? [], [query.data]);
  const byCode = useMemo(() => new Map(types.map((t) => [t.code, t])), [types]);
  const groups = useMemo(() => groupEventTypes(types, search), [types, search]);
  // Trigger scelti senza Importo mentre la bozza lo usa: per quelle azioni la campagna non scatterebbe.
  const withoutAmount = useMemo(
    () =>
      conditionUsesAmount || effectUsesAmount
        ? value.map((c) => byCode.get(c)).filter((t): t is EventType => !!t && !hasAmountField(t))
        : [],
    [value, byCode, conditionUsesAmount, effectUsesAmount],
  );

  const toggle = (code: string) => onChange(value.includes(code) ? value.filter((c) => c !== code) : [...value, code]);
  const add = (code: string) => onChange(value.includes(code) ? value : [...value, code]);
  const openCreate = (name = "") => {
    setCreated(null);
    setCreating({ name });
  };
  const closeCreate = () => {
    setCreating(null);
    setDirty(false);
  };

  function onSaved(saved: ActionType, info: SavedInfo) {
    closeCreate();
    add(saved.code);
    setSearch("");
    setCreated({ name: saved.name, code: saved.code, onlySimulator: info.onlySimulator, failedOn: info.failedOn });
  }

  const sheet = (
    <SideSheet
      open={creating != null}
      title={T.sheetTitle}
      onClose={closeCreate}
      dirty={dirty}
      confirmText={it.actions.sheet.confirmTextAction}
      returnFocusRef={searchInput}
    >
      {creating ? (
        <ActionTypeEditor
          initial={null}
          context="campaign"
          prefillName={creating.name}
          onDirtyChange={onDirtyChange}
          onSaved={onSaved}
          onUseExisting={(existing) => {
            closeCreate();
            add(existing.code);
          }}
          onCancel={closeCreate}
        />
      ) : null}
    </SideSheet>
  );

  if (query.isLoading) {
    return (
      <div className="space-y-2" role="status" aria-busy="true">
        <span className="sr-only">{T.loading}</span>
        <div className="h-8 animate-pulse rounded bg-slate-100" />
        <div className="grid gap-2 sm:grid-cols-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-12 animate-pulse rounded bg-slate-100" />
          ))}
        </div>
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="space-y-3">
        {query.error.asleep ? (
          <DegradedBox service="ingestion" onRetry={() => query.refetch()} />
        ) : (
          <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <p className="font-medium">{T.unavailable(query.error.code)}</p>
            {query.error.detail ? <p className="mt-1 text-xs">{query.error.detail}</p> : null}
            <button type="button" onClick={() => query.refetch()} className="mt-2 rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100">
              {T.retry}
            </button>
          </div>
        )}
        <FallbackInput value={value} onChange={onChange} disabled={disabled} />
      </div>
    );
  }

  const enabledCount = types.filter((t) => t.enabled).length;
  const unknown = value.filter((c) => !byCode.get(c)?.enabled);

  return (
    <div className="space-y-3">
      <SelectedChips value={value} byCode={byCode} onRemove={toggle} disabled={disabled} />
      {created ? (
        <div role="status" className="space-y-1 rounded border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-900">
          <p className="font-medium">{T.created(created.name)}</p>
          {created.onlySimulator ? <p>{it.actions.detail.onlySimulatorBanner}</p> : null}
          {isDemo ? <NewTabLink href={`/backoffice/demo/simulator?type=${encodeURIComponent(created.code)}`}>{it.actions.detail.tryIt}</NewTabLink> : null}
        </div>
      ) : null}
      {created && created.failedOn.length > 0 ? (
        <p role="alert" className="rounded border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {it.actions.editor.sourcesFailed(created.failedOn.join(", "))}
        </p>
      ) : null}
      {withoutAmount.length > 0 ? (
        <div role="alert" className="space-y-1 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
          <p>{T.amountWarning(withoutAmount.map((t) => `«${t.name}»`).join(", "), withoutAmount.length)}</p>
          {conditionUsesAmount ? <p>{T.amountCondition}</p> : null}
          {effectUsesAmount ? <p>{T.amountEffect}</p> : null}
          {conditionUsesAmount && onDropAmountExample && !disabled ? (
            <button type="button" onClick={onDropAmountExample} className="rounded border border-amber-400 px-2 py-0.5">
              {T.dropExample}
            </button>
          ) : null}
        </div>
      ) : null}
      {unknown.length > 0 ? (
        <p className="text-xs text-amber-800">
          {T.unknown(unknown.join(", "), unknown.length)} <NewTabLink href={ACTIONS_HREF}>{T.link}</NewTabLink>.
        </p>
      ) : null}
      {enabledCount === 0 ? (
        <>
          <EmptyState
            title={T.emptyTitle}
            hint={canCreate ? T.emptyHint : T.emptyHintNoCreate}
            action={canCreate && !disabled ? { label: T.create, onClick: () => openCreate() } : undefined}
          />
          <p className="text-xs">
            <NewTabLink href={ACTIONS_HREF}>{T.manage}</NewTabLink>
          </p>
          <FallbackInput value={value} onChange={onChange} disabled={disabled} />
        </>
      ) : (
        <>
          <div className="flex items-center gap-2">
            <label className="relative block flex-1">
              <span className="sr-only">{T.searchLabel}</span>
              <Search className="pointer-events-none absolute left-2 top-1/2 size-4 -translate-y-1/2 text-[var(--color-bo-ink-2)]" aria-hidden />
              <input
                ref={searchInput}
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={T.searchPlaceholder}
                className={cn(INPUT, "pl-8")}
              />
            </label>
            <button
              type="button"
              onClick={() => query.refetch()}
              aria-label={T.refreshLabel}
              title={T.refreshLabel}
              className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-xs"
            >
              <RefreshCw className={cn("size-3.5", query.isFetching && "animate-spin")} aria-hidden />
              {T.refresh}
            </button>
          </div>
          {groups.length === 0 ? (
            <div className="space-y-1 text-xs text-[var(--color-bo-ink-2)]">
              <p>{T.noMatch(search)}</p>
              {!disabled ? (
                <Can capability="actiontype.custom">
                  <button type="button" onClick={() => openCreate(search.trim())} className="inline-flex items-center gap-1 font-medium text-[var(--color-bo-accent)] underline">
                    <Plus className="size-3.5" aria-hidden />
                    {T.createFrom(search.trim())}
                  </button>
                </Can>
              ) : null}
            </div>
          ) : (
            <div className="max-h-80 space-y-3 overflow-y-auto pr-1">
              {groups.map((g) => (
                <fieldset key={g.category} className="space-y-1.5">
                  <legend className="mb-1 text-xs font-medium uppercase tracking-wide text-[var(--color-bo-ink-2)]">{categoryLabel(g.category)}</legend>
                  <div className="grid gap-1.5 sm:grid-cols-2">
                    {g.types.map((t) => {
                      const Icon = actionIcon(t.icon);
                      const selected = value.includes(t.code);
                      return (
                        <button
                          key={t.code}
                          type="button"
                          role="checkbox"
                          aria-checked={selected}
                          disabled={disabled}
                          onClick={() => toggle(t.code)}
                          title={t.description ?? undefined}
                          className={cn(
                            "flex items-start gap-2 rounded border px-2 py-1.5 text-left text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-60",
                            selected ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)]/10" : "border-[var(--color-bo-border)] hover:bg-slate-50",
                          )}
                        >
                          <Icon className={cn("mt-0.5 size-4 shrink-0", selected ? "text-[var(--color-bo-accent)]" : "text-[var(--color-bo-ink-2)]")} aria-hidden />
                          <span className="min-w-0 flex-1">
                            <span className="flex flex-wrap items-center gap-1.5">
                              <span className="font-medium">{t.name}</span>
                              {t.origin === "CUSTOM" ? <CustomPill /> : null}
                            </span>
                            <span className="block truncate font-mono text-xs text-[var(--color-bo-ink-2)]">{t.code}</span>
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </fieldset>
              ))}
            </div>
          )}
          {!disabled && groups.length > 0 ? (
            <Can capability="actiontype.custom">
              <p className="flex flex-wrap items-center gap-2 border-t border-[var(--color-bo-border)] pt-2 text-xs text-[var(--color-bo-ink-2)]">
                {T.notFound}
                <button type="button" onClick={() => openCreate(search.trim())} className="font-medium text-[var(--color-bo-accent)] underline">
                  {T.create}
                </button>
              </p>
            </Can>
          ) : null}
        </>
      )}
      {value.length === 0 ? <p className="text-xs text-red-700">{T.required}</p> : null}
      {sheet}
    </div>
  );
}

function CustomPill() {
  return <span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-semibold text-violet-800">{T.custom}</span>;
}

function SelectedChips({
  value,
  byCode,
  onRemove,
  disabled,
}: {
  value: string[];
  byCode: Map<string, EventType>;
  onRemove: (code: string) => void;
  disabled?: boolean;
}) {
  if (value.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label={T.chosen}>
      {value.map((code) => {
        const t = byCode.get(code);
        const Icon = actionIcon(t?.icon);
        return (
          <li
            key={code}
            className={cn(
              "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs",
              t?.enabled ? "border-[var(--color-bo-border)] bg-white" : "border-amber-300 bg-amber-50 text-amber-900",
            )}
          >
            <Icon className="size-3.5" aria-hidden />
            <span>{t?.name ?? code}</span>
            {t?.origin === "CUSTOM" ? <CustomPill /> : null}
            <button
              type="button"
              aria-label={T.remove(t?.name ?? code)}
              disabled={disabled}
              onClick={() => onRemove(code)}
              className="rounded-full p-0.5 hover:bg-slate-100 disabled:opacity-40"
            >
              <X className="size-3" aria-hidden />
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** Campo di testo di riserva: codici separati da virgola (stato degraded). */
function FallbackInput({ value, onChange, disabled }: { value: string[]; onChange: (next: string[]) => void; disabled?: boolean }) {
  const [text, setText] = useState(value.join(", "));
  return (
    <label className="block text-sm">
      <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">{T.fallbackLabel}</span>
      <input
        value={text}
        disabled={disabled}
        onChange={(e) => {
          setText(e.target.value);
          onChange(
            e.target.value
              .split(",")
              .map((t) => t.trim())
              .filter((t, i, all) => t && all.indexOf(t) === i),
          );
        }}
        placeholder="purchase.completed, review.submitted"
        className={cn(INPUT, "font-mono")}
      />
    </label>
  );
}
