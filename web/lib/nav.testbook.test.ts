import { describe, it, expect } from "vitest";
import { visibleNav, activeHref } from "./nav";

describe("Testbook: Navigazione Milestone", () => {
  it("[TB-WEB-NAV-001] milestone 1", () => {
    // La Dashboard è M2, Membri è M1.
    const nav1 = visibleNav(1);
    const dashboard = nav1.flatMap(g => g.items).find(i => i.id === "BO-01");
    const members = nav1.flatMap(g => g.items).find(i => i.id === "BO-02");

    expect(dashboard).toBeUndefined();
    expect(members).toBeDefined();
  });

  it("[TB-WEB-NAV-002] activeHref su sottopagina /segments/SEG-1", () => {
    // Deve accendere BO-04 che ha href "/backoffice/segments"
    const href = activeHref("/backoffice/segments/SEG-1");
    expect(href).toBe("/backoffice/segments");
  });

  it("[TB-WEB-NAV-003] activeHref su /content/messages", () => {
    // Accende /backoffice/content/messages (BO-19) che è più specifico di /backoffice/content (BO-18)
    const href = activeHref("/backoffice/content/messages");
    expect(href).toBe("/backoffice/content/messages");
  });

  it("[TB-WEB-NAV-004] BO-27 (DLQ) in milestone 7", () => {
    const nav6 = visibleNav(6);
    const dlq6 = nav6.flatMap(g => g.items).find(i => i.id === "BO-27");
    expect(dlq6).toBeUndefined();

    const nav7 = visibleNav(7);
    const dlq7 = nav7.flatMap(g => g.items).find(i => i.id === "BO-27");
    expect(dlq7).toBeDefined();
  });

  it("[TB-WEB-NAV-005] activeHref percorso portale", () => {
    // Il portale non ha nav del backoffice
    const href = activeHref("/portal/rewards");
    expect(href).toBeNull();
  });
});
