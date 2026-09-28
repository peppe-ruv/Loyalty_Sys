"use client";

import { useEffect, useState } from "react";
import { loginHref } from "@/lib/auth/browser";

// Nessuna sessione (profilo enterprise): porta al login a pagina intera e poi di nuovo qui (docs/07 §4-bis).
// Stato loading con la forma della pagina; se il reindirizzamento non parte, resta il link «Accedi».
export function LoginRedirect({ area }: { area: "backoffice" | "portale" }) {
  const [href, setHref] = useState<string | null>(null);

  useEffect(() => {
    const target = loginHref(window.location);
    setHref(target);
    window.location.replace(target);
  }, []);

  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center" aria-busy="true">
      <div className="h-10 w-10 animate-pulse rounded-full bg-[var(--color-bo-border)]" aria-hidden />
      <h1 className="text-lg font-semibold">Accesso al {area} in corso…</h1>
      <p className="text-sm text-[var(--color-bo-ink-2)]">Ti stiamo portando alla pagina di accesso.</p>
      <a
        href={href ?? "/api/auth/login"}
        className="rounded-md border border-[var(--color-bo-border)] px-4 py-2 text-sm font-semibold"
      >
        Accedi
      </a>
    </div>
  );
}
