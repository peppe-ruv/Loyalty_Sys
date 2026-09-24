import { describe, it, expect } from "vitest";
import { visibleNav, activeHref, NAV } from "./nav";

describe("nav", () => {
  it("filtra le voci in base alla milestone", () => {
    const navM1 = visibleNav(1);
    const hasM2 = navM1.some(g => g.items.some(i => i.milestone > 1));
    expect(hasM2).toBe(false);
    expect(navM1.find(g => g.label === "Panoramica")).toBeUndefined(); // Dashboard is M2
  });

  it("mantiene la struttura dei gruppi ma rimuove quelli vuoti", () => {
    const navM0 = visibleNav(0);
    expect(navM0.length).toBe(0);
  });

  it("trova l'href attivo con il match più lungo", () => {
    const mockGroups = [
      {
        label: "Group",
        items: [
          { id: "1", label: "Dashboard", href: "/backoffice", milestone: 1 },
          { id: "2", label: "Rewards", href: "/backoffice/rewards", milestone: 1 },
          { id: "3", label: "Bands", href: "/backoffice/rewards/bands", milestone: 1 },
        ]
      }
    ];

    expect(activeHref("/backoffice", mockGroups)).toBe("/backoffice");
    expect(activeHref("/backoffice/rewards", mockGroups)).toBe("/backoffice/rewards");
    expect(activeHref("/backoffice/rewards/bands", mockGroups)).toBe("/backoffice/rewards/bands");
    expect(activeHref("/backoffice/rewards/bands/123", mockGroups)).toBe("/backoffice/rewards/bands");
    // This one is tricky: /backoffice/rewards-something would match /backoffice if startsWith is literally + "/", but activeHref checks: pathname === item.href || pathname.startsWith(item.href + "/")
    expect(activeHref("/backoffice/rewards-other", mockGroups)).toBe("/backoffice");
  });

  it("restituisce null se non trova corrispondenze", () => {
    expect(activeHref("/portal", [])).toBeNull();
  });
});
