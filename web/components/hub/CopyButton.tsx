"use client";

import { useRef, useState } from "react";
import { it } from "@/lib/i18n/it";

// HUB-02 — valore pubblico di test con pulsante «Copia» (ADR-051, Q-676). La copia avviene DENTRO il gestore del clic
// (`navigator.clipboard.writeText`, richiesta dai browser come gesto dell'utente); se manca o fallisce, il testo viene
// selezionato perché lo si copi a mano. L'esito è annunciato in una regione `aria-live`. Nessun valore viaggia nei link.

const t = it.testUsers;

export function CopyValue({
  value,
  what,
  display,
  className,
}: {
  value: string;
  /** Cosa si copia, per il nome accessibile del pulsante («Copia la password degli operatori»). */
  what: string;
  /** Testo mostrato se diverso dal valore copiato (es. il codice OTP con lo spazio in mezzo). */
  display?: string;
  className?: string;
}) {
  const textRef = useRef<HTMLSpanElement>(null);
  const [status, setStatus] = useState<"idle" | "done" | "failed">("idle");
  const timer = useRef<number | undefined>(undefined);

  function selectText() {
    const el = textRef.current;
    if (!el) return;
    const range = document.createRange();
    range.selectNodeContents(el);
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(range);
  }

  function announce(next: "done" | "failed") {
    setStatus(next);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setStatus("idle"), 2000);
  }

  function copy() {
    // Il gesto dell'utente è ancora valido: la chiamata parte in modo sincrono dal gestore.
    const write = typeof navigator !== "undefined" && navigator.clipboard ? navigator.clipboard.writeText(value) : Promise.reject(new Error("nessuna clipboard"));
    write.then(
      () => announce("done"),
      () => {
        selectText();
        announce("failed");
      },
    );
  }

  return (
    <span className={className}>
      <span ref={textRef} className="font-mono">
        {display ?? value}
      </span>{" "}
      <button
        type="button"
        onClick={copy}
        aria-label={t.copyLabel(what)}
        className="ml-1 rounded border border-[var(--color-bo-border)] px-2 py-0.5 text-xs font-medium hover:bg-[var(--color-bo-bg)]"
      >
        {status === "done" ? t.copied : t.copy}
      </button>
      <span role="status" aria-live="polite" className="sr-only">
        {status === "done" ? t.copied : status === "failed" ? t.copyFailed : ""}
      </span>
      {status === "failed" ? (
        <span className="ml-2 text-xs text-[var(--color-state-waking)]" aria-hidden>
          {t.copyFailed}
        </span>
      ) : null}
    </span>
  );
}
