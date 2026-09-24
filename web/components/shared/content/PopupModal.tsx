"use client";

import { useEffect, useRef } from "react";
import { X } from "lucide-react";
import type { ContentDisplay } from "@/lib/content/types";
import { contentHref } from "@/lib/content/links";
import { toneOf } from "./ContentCard";
import { cn } from "@/lib/cn";

// Pop-up del portale (docs/09 §1, F-CNT-02), condiviso con l'anteprima di BO-18. Nel portale copre la pagina
// (`fixed`); in anteprima (`preview`) resta dentro la cornice del telefono e i pulsanti non fanno nulla.
const TONE = {
  PRIMARY: { bg: "var(--color-pt-primary)", ink: "#ffffff" },
  SECONDARY: { bg: "var(--color-pt-secondary)", ink: "#ffffff" },
  COIN: { bg: "var(--color-pt-coin)", ink: "var(--color-pt-night)" },
  NIGHT: { bg: "var(--color-pt-night)", ink: "#ffffff" },
} as const;

export function PopupModal({
  content,
  dismissible,
  preview = false,
  onClose,
  onCta,
}: {
  content: ContentDisplay;
  dismissible: boolean;
  preview?: boolean;
  onClose?: () => void;
  onCta?: (href: string) => void;
}) {
  const tone = TONE[toneOf(content)];
  const href = contentHref(content);
  const dialog = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (preview) return;
    dialog.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && dismissible) onClose?.();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [preview, dismissible, onClose]);

  return (
    <div
      className={cn("z-50 flex items-center justify-center bg-black/40 p-5", preview ? "absolute inset-0 rounded-[1.4rem]" : "fixed inset-0")}
      onClick={() => !preview && dismissible && onClose?.()}
    >
      <div
        ref={dialog}
        role="dialog"
        aria-modal={!preview}
        aria-label={content.title}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-sm overflow-hidden rounded-2xl bg-white shadow-2xl outline-none"
        data-content={content.code}
      >
        <div className="px-5 pb-4 pt-6" style={{ background: tone.bg, color: tone.ink }}>
          {dismissible ? (
            <button
              type="button"
              onClick={() => !preview && onClose?.()}
              className="absolute right-3 top-3 rounded-full p-1 opacity-80 hover:opacity-100"
              aria-label="Chiudi"
              style={{ color: tone.ink }}
            >
              <X className="size-5" aria-hidden />
            </button>
          ) : null}
          {content.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- immagini da public/demo o URL del contenuto
            <img src={content.imageUrl} alt="" className="mb-3 h-28 w-full rounded-xl object-cover" />
          ) : null}
          <h2 className="pr-6 text-lg font-semibold leading-snug">{content.title}</h2>
        </div>
        <div className="space-y-4 px-5 py-4">
          {content.body ? <p className="text-sm text-[var(--color-pt-night)]/80">{content.body}</p> : null}
          <div className="flex flex-col gap-2">
            {content.ctaLabel && href ? (
              <button
                type="button"
                onClick={() => !preview && onCta?.(href)}
                className="rounded-xl py-2.5 text-sm font-semibold"
                style={{ background: tone.bg, color: tone.ink }}
              >
                {content.ctaLabel}
              </button>
            ) : null}
            {/* Non chiudibile con una CTA: si esce solo da lì; senza CTA serve comunque un modo per proseguire. */}
            {dismissible || !(content.ctaLabel && href) ? (
              <button
                type="button"
                onClick={() => !preview && onClose?.()}
                className="rounded-xl py-2 text-sm font-medium text-[var(--color-pt-night)]/70 hover:bg-slate-50"
              >
                {content.ctaLabel && href ? "Non ora" : "Ho capito"}
              </button>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
