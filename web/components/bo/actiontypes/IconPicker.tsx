"use client";

import { useId, useMemo, useRef, useState } from "react";
import { Search } from "lucide-react";
import { INPUT } from "@/components/bo/FormBits";
import { actionIcon, actionIconLabel, isKnownActionIcon, searchActionIcons } from "@/lib/icons/action-icons";
import { it as t } from "@/lib/i18n/it";
import { cn } from "@/lib/cn";

// Griglia visiva delle icone di un'azione (BO-09 sezione 1; Q-431): elenco chiuso con ricerca ed etichette in italiano.
// Accessibile come gruppo di opzioni (`role="radiogroup"`): Tab entra sull'icona scelta, le frecce si spostano e
// scelgono, Home/Fine vanno agli estremi. Un valore fuori elenco resta visibile con un avviso.

/** Colonne della griglia, uguali a ogni larghezza: Su e Giù si spostano di una riga esatta. */
const COLUMNS = 8;

export function IconPicker({
  value,
  onChange,
  disabled,
  label = t.actions.editor.s1.icon,
}: {
  value: string;
  onChange: (name: string) => void;
  disabled?: boolean;
  label?: string;
}) {
  const [query, setQuery] = useState("");
  const choices = useMemo(() => searchActionIcons(query), [query]);
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const labelId = useId();
  const gridHintId = useId();
  const unknown = !!value && !isKnownActionIcon(value);
  const Current = actionIcon(value);
  const selectedIndex = choices.findIndex((c) => c.name === value);
  const focusIndex = selectedIndex >= 0 ? selectedIndex : 0;

  function move(from: number, key: string) {
    const last = choices.length - 1;
    const next =
      key === "ArrowRight" || key === "ArrowDown"
        ? Math.min(last, from + (key === "ArrowDown" ? COLUMNS : 1))
        : key === "ArrowLeft" || key === "ArrowUp"
          ? Math.max(0, from - (key === "ArrowUp" ? COLUMNS : 1))
          : key === "Home"
            ? 0
            : key === "End"
              ? last
              : -1;
    if (next < 0) return false;
    onChange(choices[next].name);
    buttons.current[next]?.focus();
    return true;
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span id={labelId} className="text-xs font-medium text-[var(--color-bo-ink-2)]">
          {label}
        </span>
        <span className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-1.5 py-0.5 text-xs">
          <Current className="size-4" aria-hidden />
          <span>{actionIconLabel(value) ?? (value || "—")}</span>
        </span>
      </div>
      {unknown ? (
        <p role="status" className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-900">
          {t.actions.editor.unknownIcon}
        </p>
      ) : null}
      <label className="relative block">
        <span className="sr-only">{t.actions.editor.iconSearch}</span>
        <Search className="pointer-events-none absolute left-2 top-1/2 size-4 -translate-y-1/2 text-[var(--color-bo-ink-2)]" aria-hidden />
        <input
          type="search"
          value={query}
          disabled={disabled}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t.actions.editor.iconSearch}
          className={cn(INPUT, "pl-8")}
        />
      </label>
      <span id={gridHintId} className="sr-only">
        {t.actions.editor.iconGrid}
      </span>
      {choices.length === 0 ? (
        <p className="text-xs text-[var(--color-bo-ink-2)]">{t.actions.editor.iconNone}</p>
      ) : (
        <div
          role="radiogroup"
          aria-labelledby={labelId}
          aria-describedby={gridHintId}
          className="grid max-h-44 grid-cols-8 gap-1 overflow-y-auto p-0.5"
        >
          {choices.map((c, i) => {
            const selected = c.name === value;
            return (
              <button
                key={c.name}
                ref={(el) => {
                  buttons.current[i] = el;
                }}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={c.label}
                title={c.label}
                disabled={disabled}
                tabIndex={i === focusIndex ? 0 : -1}
                onClick={() => onChange(c.name)}
                onKeyDown={(e) => {
                  if (move(i, e.key)) e.preventDefault();
                }}
                className={cn(
                  "flex aspect-square items-center justify-center rounded border focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--color-bo-accent)] disabled:opacity-50",
                  selected
                    ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)]/10 text-[var(--color-bo-accent)]"
                    : "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)] hover:bg-slate-50",
                )}
              >
                <c.Icon className="size-4" aria-hidden />
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
