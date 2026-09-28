"use client";

import { useEffect, useState } from "react";
import { ArrowRight, ChevronDown, ChevronRight } from "lucide-react";
import { useIsDemo } from "@/components/bo/PersonaContext";
import { it } from "@/lib/i18n/it";

const t = it.actions.how;

/** Chiave della preferenza «Come funziona» aperto/chiuso: solo comodità dell'operatore (Q-438). */
export const HOW_IT_WORKS_KEY = "lh.bo09.howItWorks";

function readOpen(): boolean {
  try {
    return window.localStorage.getItem(HOW_IT_WORKS_KEY) !== "closed";
  } catch {
    return true;
  }
}

function writeOpen(open: boolean) {
  try {
    window.localStorage.setItem(HOW_IT_WORKS_KEY, open ? "open" : "closed");
  } catch {
    // Archiviazione non disponibile (navigazione privata, dati bloccati): la pagina funziona lo stesso.
  }
}

/**
 * Riquadro richiudibile «Come funziona» di BO-09 (docs/08 §BO-09): Fonte → Azione → Campagna (Quando · Se · Allora) →
 * Punti e premi. Aperto la prima volta, poi come l'ha lasciato l'operatore (`localStorage` in `try/catch`). Il corpo
 * resta nel DOM (attributo `hidden`), così `aria-controls` punta sempre a un elemento. La nota sul ripristino dei dati
 * compare solo nel profilo `demo`. SPEC-GAP: Q-438.
 */
export function HowItWorks() {
  const isDemo = useIsDemo();
  const [open, setOpen] = useState(true);

  useEffect(() => {
    // Letto dopo il montaggio: il server non conosce la preferenza e il primo disegno deve coincidere.
    setOpen(readOpen());
  }, []);

  function toggle() {
    const next = !open;
    setOpen(next);
    writeOpen(next);
  }

  return (
    <section aria-labelledby="bo09-how" className="mb-4 rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] p-3">
      <h2 id="bo09-how" className="text-sm font-semibold">
        <button type="button" onClick={toggle} aria-expanded={open} aria-controls="bo09-how-body" className="inline-flex items-center gap-1">
          {open ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
          {t.title}
          <span className="sr-only">: {open ? t.hide : t.show}</span>
        </button>
      </h2>
      <div id="bo09-how-body" hidden={!open} className="mt-2 space-y-3 text-sm">
        <p className="text-[var(--color-bo-ink-2)]">{t.intro}</p>
        <ol aria-label={t.diagramLabel} className="grid gap-2 sm:grid-cols-4">
          {t.steps.map((s, i) => (
            <li key={s.title} className="relative rounded border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] p-2 text-xs">
              <p className="font-semibold text-[var(--color-bo-ink)]">
                {i + 1}. {s.title}
              </p>
              <p className="text-[var(--color-bo-ink-2)]">{s.text}</p>
              {i < t.steps.length - 1 ? (
                <ArrowRight className="absolute -right-3 top-1/2 hidden size-4 -translate-y-1/2 text-[var(--color-bo-ink-2)] sm:block" aria-hidden />
              ) : null}
            </li>
          ))}
        </ol>
        {isDemo ? <p className="text-xs text-[var(--color-bo-ink-2)]">{t.demo}</p> : null}
      </div>
    </section>
  );
}
