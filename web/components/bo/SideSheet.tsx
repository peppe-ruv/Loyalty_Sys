"use client";

import { createContext, useCallback, useContext, useEffect, useId, useRef, useState } from "react";
import { X } from "lucide-react";
import { it as t } from "@/lib/i18n/it";

// Foglio laterale del backoffice (docs/08 §1: "i dettagli che non meritano una pagina si aprono in un foglio
// laterale, 640 px"). Esc o clic sul fondo chiudono; il focus va al foglio all'apertura, resta dentro il foglio con Tab
// e Maiusc+Tab, e alla chiusura torna dov'era (o su `returnFocusRef` se quell'elemento non c'è più). Il titolo dà il
// nome al dialogo. Con `dirty` (per esempio un editor con modifiche non salvate) la chiusura chiede conferma dentro il
// foglio (BO-06 «2 · Quando», BO-09): la bozza della pagina sotto non si perde per un Esc di troppo, e il focus va
// sull'opzione meno distruttiva, «Continua a modificare». SPEC-GAP: Q-432.

interface SideSheetApi {
  /** Chiusura richiesta dal contenuto (es. *Annulla*): passa dalla stessa conferma di Esc e del fondo. */
  requestClose: () => void;
}

const SideSheetContext = createContext<SideSheetApi | null>(null);

/** API del foglio che contiene il componente, `null` fuori da un foglio. */
export function useSideSheet(): SideSheetApi | null {
  return useContext(SideSheetContext);
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

export function SideSheet({
  open,
  title,
  onClose,
  dirty = false,
  confirmText = t.actions.sheet.confirmText,
  returnFocusRef,
  children,
}: {
  open: boolean;
  title: React.ReactNode;
  onClose: () => void;
  /** Modifiche non salvate: chiudere chiede conferma. */
  dirty?: boolean;
  /** Frase della conferma: che cosa si perde chiudendo. */
  confirmText?: string;
  /** Dove riportare il focus se l'elemento che aveva il focus all'apertura non c'è più. */
  returnFocusRef?: React.RefObject<HTMLElement | null>;
  children: React.ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const stayButton = useRef<HTMLButtonElement>(null);
  const fallback = useRef(returnFocusRef);
  fallback.current = returnFocusRef;
  const [confirming, setConfirming] = useState(false);
  const titleId = useId();

  const requestClose = useCallback(() => {
    if (dirty) setConfirming(true);
    else onClose();
  }, [dirty, onClose]);

  useEffect(() => {
    if (!open) {
      setConfirming(false);
      return;
    }
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    return () => {
      if (previous && previous !== document.body && previous.isConnected) previous.focus();
      else fallback.current?.current?.focus();
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (confirming) setConfirming(false);
      else requestClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, confirming, requestClose]);

  useEffect(() => {
    if (confirming) stayButton.current?.focus();
  }, [confirming]);

  /** Tab e Maiusc+Tab restano dentro il foglio (dialogo modale). */
  function trapTab(e: React.KeyboardEvent<HTMLDivElement>) {
    if (e.key !== "Tab" || !panel.current) return;
    const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    if (items.length === 0) {
      e.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || active === panel.current)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && active === last) {
      e.preventDefault();
      first.focus();
    }
  }

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <button aria-label="Chiudi" tabIndex={-1} onClick={requestClose} className="absolute inset-0 bg-slate-900/30" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={trapTab}
        className="relative flex h-full w-full max-w-[640px] flex-col overflow-y-auto bg-[var(--color-bo-surface)] shadow-xl outline-none"
      >
        <div className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-5 py-4">
          {typeof title === "string" ? (
            <h2 id={titleId} className="min-w-0 text-base font-semibold">
              {title}
            </h2>
          ) : (
            <div id={titleId} className="min-w-0">
              {title}
            </div>
          )}
          <button onClick={requestClose} aria-label="Chiudi" className="rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-slate-100">
            <X className="size-4" />
          </button>
        </div>
        {confirming ? (
          <div
            role="alertdialog"
            aria-labelledby="sidesheet-confirm-title"
            aria-describedby="sidesheet-confirm-text"
            className="sticky top-[57px] z-10 border-b border-amber-300 bg-amber-50 px-5 py-3 text-sm text-amber-900"
          >
            <p id="sidesheet-confirm-title" className="font-medium">
              {t.actions.sheet.confirmTitle}
            </p>
            <p id="sidesheet-confirm-text" className="text-xs">
              {confirmText}
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              <button
                ref={stayButton}
                type="button"
                onClick={() => {
                  setConfirming(false);
                  panel.current?.focus();
                }}
                className="rounded border border-amber-400 px-3 py-1 text-xs font-medium"
              >
                {t.actions.sheet.confirmStay}
              </button>
              <button
                type="button"
                onClick={() => {
                  setConfirming(false);
                  onClose();
                }}
                className="rounded bg-amber-700 px-3 py-1 text-xs font-medium text-white"
              >
                {t.actions.sheet.confirmClose}
              </button>
            </div>
          </div>
        ) : null}
        <SideSheetContext.Provider value={{ requestClose }}>
          <div className="flex-1 px-5 py-4">{children}</div>
        </SideSheetContext.Provider>
      </div>
    </div>
  );
}
