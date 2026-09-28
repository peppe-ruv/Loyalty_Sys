"use client";

import { useState } from "react";
import { LogOut } from "lucide-react";
import { csrfHeaders } from "@/lib/auth/browser";
import { cn } from "@/lib/cn";

// «Esci» (profilo enterprise, docs/07 §4-bis): POST /api/auth/logout con il token CSRF, poi la pagina va all'URL di
// logout dell'IdP (RP-initiated logout) restituito dal BFF. Stati: in corso (pulsante disabilitato) ed errore in linea.
export function LogoutButton({ className }: { className?: string }) {
  const [state, setState] = useState<"idle" | "pending" | "error">("idle");

  async function logout() {
    setState("pending");
    try {
      const res = await fetch("/api/auth/logout", { method: "POST", headers: csrfHeaders("POST"), cache: "no-store" });
      const body = (await res.json().catch(() => null)) as { redirectTo?: unknown } | null;
      if (!res.ok || typeof body?.redirectTo !== "string") throw new Error(`logout ${res.status}`);
      window.location.assign(body.redirectTo);
    } catch {
      setState("error");
    }
  }

  return (
    <span className="inline-flex items-center gap-2">
      <button
        type="button"
        onClick={logout}
        disabled={state === "pending"}
        className={cn(
          "inline-flex items-center gap-1 rounded-md border border-[var(--color-bo-border)] px-2 py-1 text-xs font-semibold disabled:cursor-wait disabled:opacity-60",
          className,
        )}
      >
        <LogOut className="size-3.5" aria-hidden />
        {state === "pending" ? "Uscita…" : "Esci"}
      </button>
      {state === "error" ? (
        <span role="alert" className="text-xs text-red-700">
          Uscita non riuscita. Riprova.
        </span>
      ) : null}
    </span>
  );
}
