"use client";

import { useState, type FormEvent } from "react";
import { LogOut } from "lucide-react";
import { readCsrfToken } from "@/lib/auth/browser";
import type { Realm } from "@/lib/auth/realm";
import { cn } from "@/lib/cn";

// «Esci» (profilo enterprise, docs/07 §4-bis): modulo inviato a pagina intera a POST /api/auth/logout con il token
// CSRF in un campo; il server chiude la sessione e risponde 303 verso il logout dell'IdP. L'ID token non passa mai dal
// JavaScript (regola 20). Stato in corso: pulsante disabilitato; un rifiuto porta a /auth/error con il motivo.
// `realm`: con due realm (ADR-051) il portale chiude la sola sessione del membro.
export function LogoutButton({ className, realm = "operators" }: { className?: string; realm?: Realm }) {
  const [pending, setPending] = useState(false);
  const [token, setToken] = useState("");

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    // Il token si legge al momento dell'invio (il cookie può essere cambiato dopo il render).
    const current = readCsrfToken(undefined, realm) ?? "";
    const input = event.currentTarget.elements.namedItem("csrf");
    if (input instanceof HTMLInputElement) input.value = current;
    setToken(current);
    setPending(true);
  }

  return (
    <form method="post" action={realm === "members" ? "/api/auth/logout?realm=members" : "/api/auth/logout"} onSubmit={onSubmit} className="inline-flex">
      <input type="hidden" name="csrf" value={token} readOnly />
      <button
        type="submit"
        disabled={pending}
        className={cn(
          "inline-flex items-center gap-1 rounded-md border border-[var(--color-bo-border)] px-2 py-1 text-xs font-semibold disabled:cursor-wait disabled:opacity-60",
          className,
        )}
      >
        <LogOut className="size-3.5" aria-hidden />
        {pending ? "Uscita…" : "Esci"}
      </button>
    </form>
  );
}
