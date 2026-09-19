"use client";

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
  children: React.ReactNode;
}) {
  const { role } = useBoPersona();
  const allowed = can(role, capability);
  if (allowed) return <>{children}</>;
  if (mode === "hide") return null;
  return (
    <span title={requiredRoleHint(capability)} className="cursor-not-allowed opacity-40">
      <span className="pointer-events-none">{children}</span>
    </span>
  );
}

export function useCan(capability: Capability): boolean {
  const { role } = useBoPersona();
  return can(role, capability);
}
