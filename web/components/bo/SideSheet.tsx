"use client";

import { useEffect, useRef } from "react";
import { X } from "lucide-react";

// Foglio laterale del backoffice (docs/08 §1: "i dettagli che non meritano una pagina si aprono in un foglio
// laterale, 640 px"). Esc o clic sul fondo chiudono; il focus va al foglio all'apertura.
export function SideSheet({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <button aria-label="Chiudi" onClick={onClose} className="absolute inset-0 bg-slate-900/30" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        className="relative flex h-full w-full max-w-[640px] flex-col overflow-y-auto bg-[var(--color-bo-surface)] shadow-xl outline-none"
      >
        <div className="sticky top-0 flex items-start justify-between gap-3 border-b border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-5 py-4">
          <div className="min-w-0">{title}</div>
          <button onClick={onClose} aria-label="Chiudi" className="rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-slate-100">
            <X className="size-4" />
          </button>
        </div>
        <div className="flex-1 px-5 py-4">{children}</div>
      </div>
    </div>
  );
}
