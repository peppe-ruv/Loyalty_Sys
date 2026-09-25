"use client";

import { cloneElement, isValidElement, type ReactElement, type ReactNode } from "react";
import Link from "next/link";
import { useBoPersona } from "./PersonaContext";
import { can, requiredRoleHint, type Capability } from "@/lib/persona/permissions";

// <Can capability>: nasconde (hide, default) o disabilita (mode="disable") in base al ruolo (docs/08 §2).
export function Can({
  capability,
  mode = "hide",
  children,
}: {
  capability: Capability;
  mode?: "hide" | "disable";
  children: ReactNode;
}) {
  const { role } = useBoPersona();
  const allowed = can(role, capability);
  if (allowed) return <>{children}</>;
  if (mode === "hide") return null;
  return (
    <span title={requiredRoleHint(capability)} className="cursor-not-allowed opacity-40">
      <span className="pointer-events-none">{disabled(children)}</span>
    </span>
  );
}

const CONTROLS = new Set(["button", "input", "select", "textarea"]);

/**
 * Disabilitazione reale (docs/07 §4, §6 "Forbidden"): non basta bloccare il mouse, il controllo non deve essere
 * attivabile da tastiera né da tecnologie assistive. Un controllo nativo diventa `disabled`; un link `aria-disabled` e
 * fuori dall'ordine di tabulazione; qualsiasi altro contenuto (sezioni, form, componenti) sta in un `<fieldset
 * disabled>` che disabilita tutti i controlli che contiene.
 */
function disabled(children: ReactNode): ReactNode {
  if (isValidElement(children)) {
    const el = children as ReactElement<Record<string, unknown>>;
    if (typeof el.type === "string" && CONTROLS.has(el.type)) {
      return cloneElement(el, { disabled: true, "aria-disabled": true });
    }
    if (el.type === "a" || el.type === Link) {
      return cloneElement(el, { "aria-disabled": true, tabIndex: -1, onClick: (e: Event) => e.preventDefault() });
    }
  }
  return (
    <fieldset disabled className="contents">
      {children}
    </fieldset>
  );
}

export function useCan(capability: Capability): boolean {
  const { role } = useBoPersona();
  return can(role, capability);
}
