"use client";

import { createContext, useContext } from "react";
import type { Role } from "@/lib/persona/personas";

// Ruolo della persona corrente disponibile ai componenti client (docs/08 §2). Alimentato dal layout server
// che legge il cookie lh_persona. Nel PoC non c'è login: è solo per mostrare/disabilitare le azioni.

export interface BoPersona {
  username: string;
  displayName: string;
  role: Role;
}

const PersonaContext = createContext<BoPersona>({
  username: "marta.admin",
  displayName: "Marta Villa",
  role: "ADMIN",
});

export function PersonaProvider({ value, children }: { value: BoPersona; children: React.ReactNode }) {
  return <PersonaContext.Provider value={value}>{children}</PersonaContext.Provider>;
}

export function useBoPersona(): BoPersona {
  return useContext(PersonaContext);
}
