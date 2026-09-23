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

// Milestone realizzate finora: M1 completa + fette di M2 già realizzate (BO-24 in M2.2). Le voci M2 non
// ancora costruite semplicemente non sono ancora in NAV, quindi non compaiono (mai pagine "in arrivo").
export const REALIZED_MILESTONE = 3;

export const NAV: NavGroup[] = [
  {
    label: "Panoramica",
    items: [
      { id: "BO-01", label: "Dashboard", href: "/backoffice", milestone: 2 },
    ],
  },
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
      { id: "BO-07", label: "Livelli", href: "/backoffice/program/tiers", milestone: 3 },
      { id: "BO-08", label: "Valute ed edizioni", href: "/backoffice/program/currencies", milestone: 3 },
      { id: "BO-09", label: "Azioni e fonti", href: "/backoffice/program/actions", milestone: 1 },
    ],
  },
  {
    label: "Governance",
    items: [
      { id: "BO-22", label: "Audit", href: "/backoffice/governance/audit", milestone: 2 },
    ],
  },
  {
    label: "Osservabilità",
    items: [
      { id: "BO-24", label: "Flusso eventi live", href: "/backoffice/observe/live", milestone: 2 },
      { id: "BO-25", label: "Tracciati", href: "/backoffice/observe/traces", milestone: 2 },
      { id: "BO-26", label: "Monitor ingressi", href: "/backoffice/observe/inbound", milestone: 1 },
    ],
  },
  {
    label: "Demo",
    items: [
      { id: "BO-28", label: "Simulatore eventi", href: "/backoffice/demo/simulator", milestone: 1 },
      { id: "BO-29", label: "Scenari", href: "/backoffice/demo/scenarios", milestone: 2 },
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
