"use client";

import { createContext, useContext } from "react";

// Membro attivo del portale, disponibile ai componenti client. Alimentato dal layout server (cookie lh_persona).
const MemberContext = createContext<string>("MBR-000002");

export function MemberProvider({ memberId, children }: { memberId: string; children: React.ReactNode }) {
  return <MemberContext.Provider value={memberId}>{children}</MemberContext.Provider>;
}

export function useActiveMember(): string {
  return useContext(MemberContext);
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
