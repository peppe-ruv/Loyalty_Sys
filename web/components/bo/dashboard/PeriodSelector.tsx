"use client";

import { PERIODS, type Period } from "@/lib/api/insight";

// Selettore periodo BO-01 (7/30/90 giorni, default 30). Filtro in una sola riga sopra i grafici (dataviz).
export function PeriodSelector({ value, onChange }: { value: Period; onChange: (p: Period) => void }) {
  return (
    <div className="inline-flex rounded-md border border-[var(--color-bo-border)] bg-white p-0.5" role="group" aria-label="Periodo">
      {PERIODS.map((p) => (
        <button
          key={p}
          type="button"
          aria-pressed={p === value}
          onClick={() => onChange(p)}
          className={`rounded px-2.5 py-1 text-xs font-medium transition ${
            p === value
              ? "bg-[var(--color-bo-accent)] text-white"
              : "text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]"
          }`}
        >
          {p} giorni
        </button>
      ))}
    </div>
  );
}
