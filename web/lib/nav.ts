// Navigazione del backoffice (docs/08 §1). Ogni voce ha la milestone in cui compare: una voce la cui
// milestone non è ancora realizzata NON compare nella sidebar (mai pagine "in arrivo").

export interface NavItem {
  id: string; // BO-xx
  label: string;
  href: string;
  milestone: number; // milestone in cui la voce diventa reale
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

// Milestone realizzate finora: fino a M1 (fette M1.5). Alza la soglia man mano che le fette avanzano.
export const REALIZED_MILESTONE = 1;

export const NAV: NavGroup[] = [
  {
    label: "Clienti",
    items: [
      { id: "BO-02", label: "Membri", href: "/backoffice/members", milestone: 1 },
    ],
  },
  {
    label: "Programma",
    items: [
      { id: "BO-05", label: "Campagne", href: "/backoffice/campaigns", milestone: 1 },
      { id: "BO-09", label: "Azioni e fonti", href: "/backoffice/program/actions", milestone: 1 },
    ],
  },
  {
    label: "Osservabilità",
    items: [
      { id: "BO-26", label: "Monitor ingressi", href: "/backoffice/observe/inbound", milestone: 1 },
    ],
  },
  {
    label: "Demo",
    items: [
      { id: "BO-28", label: "Simulatore eventi", href: "/backoffice/demo/simulator", milestone: 1 },
      { id: "BO-30", label: "Console demo", href: "/backoffice/demo/console", milestone: 1 },
    ],
  },
];

/** Gruppi con solo le voci la cui milestone è già realizzata. */
export function visibleNav(realized: number = REALIZED_MILESTONE): NavGroup[] {
  return NAV.map((g) => ({ ...g, items: g.items.filter((i) => i.milestone <= realized) })).filter(
    (g) => g.items.length > 0,
  );
}
