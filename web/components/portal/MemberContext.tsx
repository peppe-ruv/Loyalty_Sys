"use client";

import { createContext, useContext, useMemo } from "react";
import { portalApi, type PortalApi } from "@/lib/portal/memberApi";

// Membro attivo del portale, disponibile ai componenti client. Demo: alimentato dal layout server (cookie lh_persona).
// Enterprise (F2-SEC-09, ADR-051): l'id arriva da `GET /v1/portal/me/profile` (EnterpriseMemberGate) e vale solo per
// mostrare e chiavare le cache; le chiamate ai servizi passano da `usePortalApi()` e non lo inviano mai.
interface MemberState {
  memberId: string;
  enterprise: boolean;
  /** Enterprise: il token non ha ancora un membro (404 MEMBER_NOT_REGISTERED); le viste dei servizi restano ferme. */
  unregistered: boolean;
}

const MemberContext = createContext<MemberState>({ memberId: "MBR-000002", enterprise: false, unregistered: false });

export function MemberProvider({
  memberId,
  enterprise = false,
  unregistered = false,
  children,
}: {
  memberId: string;
  enterprise?: boolean;
  unregistered?: boolean;
  children: React.ReactNode;
}) {
  const value = useMemo(() => ({ memberId, enterprise, unregistered }), [memberId, enterprise, unregistered]);
  return <MemberContext.Provider value={value}>{children}</MemberContext.Provider>;
}

export function useActiveMember(): string {
  return useContext(MemberContext).memberId;
}

/** Percorsi, query e corpi del portale per il profilo corrente (lib/portal/memberApi.ts): l'unico modo di parlare ai servizi. */
export function usePortalApi(): PortalApi {
  const { enterprise, memberId } = useContext(MemberContext);
  return useMemo(() => portalApi(enterprise, memberId), [enterprise, memberId]);
}

/** Enterprise e account senza membro: shell e campanella non chiamano i servizi finché non c'è la registrazione. */
export function useUnregistered(): boolean {
  return useContext(MemberContext).unregistered;
}

/** Cambia il membro attivo (cookie) e ricarica. Usato dal tray demo PT-14. */
export async function switchMember(memberId: string): Promise<void> {
  await fetch("/api/persona", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ kind: "MEMBER", memberId }),
  });
  if (typeof window !== "undefined") {
    window.location.reload();
  }
}
