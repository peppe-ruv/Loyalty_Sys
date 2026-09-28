"use client";

import { createContext, useContext } from "react";
import type { Role } from "@/lib/persona/personas";

// Ruolo della persona corrente disponibile ai componenti client (docs/08 §2). Alimentato dal layout server
// che legge il cookie lh_persona. Nel PoC non c'è login: è solo per mostrare/disabilitare le azioni.
// `mode` è il profilo dell'installazione letto dal layout (`getViewer`): i testi che valgono solo per la demo (per
// esempio il ripristino dei dati) si mostrano solo nel profilo `demo`.

export interface BoPersona {
  username: string;
  displayName: string;
  role: Role;
  /** Profilo dell'installazione; assente = `demo`. */
  mode?: "demo" | "enterprise";
}

const PersonaContext = createContext<BoPersona>({
  username: "marta.admin",
  displayName: "Marta Villa",
  role: "ADMIN",
  mode: "demo",
});

export function PersonaProvider({ value, children }: { value: BoPersona; children: React.ReactNode }) {
  return <PersonaContext.Provider value={value}>{children}</PersonaContext.Provider>;
}

export function useBoPersona(): BoPersona {
  return useContext(PersonaContext);
}

/** Il backoffice gira nel profilo `demo` (dati fittizi, Console demo, ripristino dei dati). */
export function useIsDemo(): boolean {
  return (useContext(PersonaContext).mode ?? "demo") === "demo";
}
