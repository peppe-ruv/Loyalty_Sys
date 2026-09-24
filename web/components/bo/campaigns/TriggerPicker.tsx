"use client";

import { useMemo, useState } from "react";
import {
  Award,
  Cake,
  ClipboardList,
  CreditCard,
  FileText,
  Gauge,
  Gift,
  HelpCircle,
  Mail,
  PackageCheck,
  RotateCcw,
  Search,
  ShoppingCart,
  Smartphone,
  Star,
  Target,
  TrendingUp,
  UserCheck,
  UserPlus,
  Users,
  X,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { EventType } from "@/lib/api/types";
import { INPUT } from "@/components/bo/FormBits";
import { DegradedBox, EmptyState } from "@/components/bo/primitives";
import { cn } from "@/lib/cn";

// Scelta dei trigger di una campagna (docs/08 §BO-06 sezione "2 Quando", F-ING-06): tipi azione abilitati da
// `ingestion GET /v1/event-types`, raggruppati per categoria, multi-scelta con icona, ricerca e pill "custom" sui tipi
// creati in BO-09. Se ingestion dorme o risponde con errore la pagina resta usabile: si torna al campo di testo con i
// codici separati da virgola (stato degraded, docs/07 §6).

const ICONS: Record<string, LucideIcon> = {
  "shopping-cart": ShoppingCart,
  "rotate-ccw": RotateCcw,
  "file-text": FileText,
  "credit-card": CreditCard,
  gauge: Gauge,
  smartphone: Smartphone,
  "clipboard-list": ClipboardList,
  "help-circle": HelpCircle,
  star: Star,
  mail: Mail,
  "user-plus": UserPlus,
  "user-check": UserCheck,
  cake: Cake,
  "trending-up": TrendingUp,
  gift: Gift,
  target: Target,
  award: Award,
  users: Users,
  "package-check": PackageCheck,
};

const CATEGORY_LABEL: Record<string, string> = {
  TRANSACTION: "Transazioni",
  SERVICE: "Servizi",
  ENGAGEMENT: "Coinvolgimento",
  INTERNAL: "Interni (dal programma)",
  CUSTOM: "Personalizzati",
};

const CATEGORY_ORDER = ["TRANSACTION", "SERVICE", "ENGAGEMENT", "CUSTOM", "INTERNAL"];

export function eventTypeIcon(icon: string | null | undefined): LucideIcon {
  return (icon && ICONS[icon]) || Zap;
}

function categoryLabel(c: string): string {
  return CATEGORY_LABEL[c] ?? (c === "" ? "Altro" : c.charAt(0) + c.slice(1).toLowerCase());
}

/** Tipi abilitati filtrati dal testo e raggruppati per categoria (ordine fisso, poi alfabetico). */
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
  const rank = (c: string) => {
    const i = CATEGORY_ORDER.indexOf(c);
    return i < 0 ? CATEGORY_ORDER.length - 0.5 : i;
  };
  return [...by.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([category, list]) => ({ category, types: list }));
}

export function TriggerPicker({
  value,
  onChange,
  disabled,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
}) {
  const query = useLhQuery<EventType[]>("ingestion", "/v1/event-types");
  const [search, setSearch] = useState("");
  const types = useMemo(() => query.data ?? [], [query.data]);
  const byCode = useMemo(() => new Map(types.map((t) => [t.code, t])), [types]);
  const groups = useMemo(() => groupEventTypes(types, search), [types, search]);

  const toggle = (code: string) =>
    onChange(value.includes(code) ? value.filter((c) => c !== code) : [...value, code]);

  if (query.isLoading) {
    return (
      <div className="space-y-2" aria-busy="true" aria-label="Caricamento dei tipi azione">
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
          <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <p className="font-medium">Tipi azione non disponibili: {query.error.code}</p>
            {query.error.detail ? <p className="mt-1 text-xs">{query.error.detail}</p> : null}
            <button
              type="button"
              onClick={() => query.refetch()}
              className="mt-2 rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100"
            >
              Riprova
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
      {unknown.length > 0 ? (
        <p className="text-xs text-amber-800">
          {unknown.length === 1 ? "Il tipo" : "I tipi"} {unknown.join(", ")} non {unknown.length === 1 ? "è" : "sono"} tra
          quelli abilitati: la campagna non scatterà finché non {unknown.length === 1 ? "viene abilitato" : "vengono abilitati"} in
          Azioni e fonti.
        </p>
      ) : null}
      {enabledCount === 0 ? (
        <>
          <EmptyState
            title="Nessun tipo azione abilitato"
            hint="Abilita un tipo di sistema o creane uno personalizzato in Azioni e fonti; intanto puoi scrivere i codici a mano."
          />
          <FallbackInput value={value} onChange={onChange} disabled={disabled} />
        </>
      ) : (
        <>
          <label className="relative block">
            <span className="sr-only">Cerca un tipo azione</span>
            <Search className="pointer-events-none absolute left-2 top-1/2 size-4 -translate-y-1/2 text-[var(--color-bo-ink-2)]" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Cerca per nome o codice…"
              className={cn(INPUT, "pl-8")}
            />
          </label>
          {groups.length === 0 ? (
            <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun tipo azione corrisponde a «{search}».</p>
          ) : (
            <div className="max-h-80 space-y-3 overflow-y-auto pr-1">
              {groups.map((g) => (
                <fieldset key={g.category} className="space-y-1.5">
                  <legend className="mb-1 text-xs font-medium uppercase tracking-wide text-[var(--color-bo-ink-2)]">
                    {categoryLabel(g.category)}
                  </legend>
                  <div className="grid gap-1.5 sm:grid-cols-2">
                    {g.types.map((t) => {
                      const Icon = eventTypeIcon(t.icon);
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
                            selected
                              ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)]/10"
                              : "border-[var(--color-bo-border)] hover:bg-slate-50",
                          )}
                        >
                          <Icon className={cn("mt-0.5 size-4 shrink-0", selected ? "text-[var(--color-bo-accent)]" : "text-[var(--color-bo-ink-2)]")} />
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
        </>
      )}
      {value.length === 0 ? <p className="text-xs text-red-700">Scegli almeno un tipo azione.</p> : null}
    </div>
  );
}

function CustomPill() {
  return <span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-violet-800">custom</span>;
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
    <ul className="flex flex-wrap gap-1.5" aria-label="Trigger scelti">
      {value.map((code) => {
        const t = byCode.get(code);
        const Icon = eventTypeIcon(t?.icon);
        return (
          <li
            key={code}
            className={cn(
              "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs",
              t?.enabled ? "border-[var(--color-bo-border)] bg-white" : "border-amber-300 bg-amber-50 text-amber-900",
            )}
          >
            <Icon className="size-3.5" />
            <span>{t?.name ?? code}</span>
            {t?.origin === "CUSTOM" ? <CustomPill /> : null}
            <button
              type="button"
              aria-label={`Togli ${t?.name ?? code}`}
              disabled={disabled}
              onClick={() => onRemove(code)}
              className="rounded-full p-0.5 hover:bg-slate-100 disabled:opacity-40"
            >
              <X className="size-3" />
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
      <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">Tipi azione trigger (codici separati da virgola)</span>
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
