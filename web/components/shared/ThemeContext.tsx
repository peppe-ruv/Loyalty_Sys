"use client";

import { createContext, useContext } from "react";
import { AURORA, type PortalTheme } from "@/lib/theme/theme";

// Tema del portale per i componenti client (nome del programma, testi hero, nomi delle valute). I colori arrivano
// come variabili CSS dal layout; qui c'è il resto del tema (docs/09 §1).
const ThemeContext = createContext<PortalTheme>(AURORA);

export function ThemeProvider({ theme, children }: { theme: PortalTheme; children: React.ReactNode }) {
  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function usePortalTheme(): PortalTheme {
  return useContext(ThemeContext);
}
