"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { NavLinks } from "./NavLinks";

// Cassetto di navigazione mobile (docs/08 §1: sotto i ~1024 px la sidebar diventa un cassetto).
// Bottone ☰ visibile solo sotto md; apre un pannello a sinistra con gli stessi gruppi della sidebar.
export function MobileNav() {
  const [open, setOpen] = useState(false);

  // Chiude con Esc e blocca lo scroll del body quando aperto.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prev;
    };
  }, [open]);

  return (
    <div className="md:hidden">
      <button
        type="button"
        aria-label="Apri la navigazione"
        aria-expanded={open}
        onClick={() => setOpen(true)}
        className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-[var(--color-bo-border)] text-[var(--color-bo-ink)]"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M4 6h16M4 12h16M4 18h16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      </button>

      {open ? (
        <div className="fixed inset-0 z-50 flex">
          <div className="absolute inset-0 bg-black/40" onClick={() => setOpen(false)} aria-hidden />
          <aside className="relative flex w-64 max-w-[80%] flex-col bg-[var(--color-bo-ink)] px-3 py-5 text-[#c9d3e0]">
            <div className="mb-6 flex items-center justify-between px-2">
              <Link href="/" onClick={() => setOpen(false)} className="text-lg font-semibold text-white">
                Loyalty Hub
              </Link>
              <button
                type="button"
                aria-label="Chiudi la navigazione"
                onClick={() => setOpen(false)}
                className="inline-flex h-8 w-8 items-center justify-center rounded-md text-[#8b98ad] hover:text-white"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
                  <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
              </button>
            </div>
            <NavLinks onNavigate={() => setOpen(false)} />
            <Link href="/" onClick={() => setOpen(false)} className="mt-4 px-2 text-xs text-[#8b98ad] hover:text-white">
              ← Demo Hub
            </Link>
          </aside>
        </div>
      ) : null}
    </div>
  );
}
