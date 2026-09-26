"use client";

import { useLhQuery } from "@/lib/api/client";
import { INPUT } from "@/components/bo/FormBits";
import { DegradedBox } from "@/components/bo/primitives";
import { cn } from "@/lib/cn";

// Fonti ammesse della campagna (docs/08 §BO-06 sezione "2 Quando", default tutte; Q-208): multi-scelta fra le fonti di
// `ingestion GET /v1/sources`. Nessuna scelta = tutte le fonti. Il valore diventa la regola `context.source` alla
// radice delle condizioni (`lib/campaign/sources.ts`). Se ingestion dorme si scrivono i codici separati da virgola.

interface SourceRow {
  code: string;
  name: string;
  enabled: boolean;
}

export function SourcesPicker({ value, onChange, disabled }: { value: string[]; onChange: (v: string[]) => void; disabled?: boolean }) {
  const sources = useLhQuery<SourceRow[]>("ingestion", "/v1/sources");
  const toggle = (code: string) => onChange(value.includes(code) ? value.filter((c) => c !== code) : [...value, code]);

  if (sources.isError) {
    return (
      <div className="space-y-2">
        <DegradedBox service="ingestion" onRetry={() => sources.refetch()} />
        <input
          aria-label="Fonti ammesse (codici separati da virgola)"
          className={INPUT}
          disabled={disabled}
          placeholder="Tutte le fonti (es. ecommerce, app)"
          value={value.join(", ")}
          onChange={(e) => onChange(e.target.value.split(",").map((s) => s.trim()).filter(Boolean))}
        />
      </div>
    );
  }

  const known = new Set((sources.data ?? []).map((s) => s.code));
  const unknown = value.filter((c) => !known.has(c));
  return (
    <div className="space-y-1.5">
      <div role="group" aria-label="Fonti ammesse" className="flex flex-wrap gap-1.5">
        {(sources.data ?? []).map((s) => {
          const on = value.includes(s.code);
          return (
            <button
              key={s.code}
              type="button"
              aria-pressed={on}
              disabled={disabled}
              onClick={() => toggle(s.code)}
              title={s.enabled ? s.code : `${s.code} · fonte spenta: i suoi eventi sono rifiutati`}
              className={cn(
                "rounded-full border px-2.5 py-0.5 text-xs",
                on ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)] text-white" : "border-[var(--color-bo-border)]",
                !s.enabled && "opacity-60",
              )}
            >
              {s.name}
            </button>
          );
        })}
        {unknown.map((c) => (
          <button key={c} type="button" aria-pressed disabled={disabled} onClick={() => toggle(c)}
            className="rounded-full border border-amber-400 bg-amber-50 px-2.5 py-0.5 text-xs" title="Fonte non più presente in ingestion">
            {c}
          </button>
        ))}
      </div>
      <p className="text-xs text-[var(--color-bo-ink-2)]">
        {value.length === 0 ? "Tutte le fonti (nessuna scelta)." : `Solo le azioni che arrivano da: ${value.join(", ")}.`}
      </p>
    </div>
  );
}
