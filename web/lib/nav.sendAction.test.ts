import { describe, expect, it } from "vitest";
import { canSendAction, navForProfile, NAV, visibleNav } from "./nav";

// V11 (ADR-051, BO-32): «Invia un'azione» solo per ADMIN e CARE, in enterprise, nell'ambiente di test; il gruppo «Demo»
// della sidebar solo nel profilo demo (docs/18 V11).
describe("canSendAction", () => {
  it("vero solo con ADMIN o CARE + enterprise + ambiente di test", () => {
    const roles = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"] as const;
    for (const role of roles) {
      for (const mode of ["demo", "enterprise"] as const) {
        for (const test of [false, true]) {
          expect(canSendAction(role, mode, test)).toBe((role === "ADMIN" || role === "CARE") && mode === "enterprise" && test);
        }
      }
    }
  });
});

describe("gruppo «Demo»", () => {
  it("è marcato solo-demo; in enterprise non compare, in demo sì", () => {
    expect(NAV.find((g) => g.label === "Demo")?.demoOnly).toBe(true);
    expect(NAV.filter((g) => g.label !== "Demo").every((g) => !g.demoOnly)).toBe(true);
    const labels = (mode: "demo" | "enterprise") => navForProfile(visibleNav(), mode).map((g) => g.label);
    expect(labels("demo")).toContain("Demo");
    expect(labels("enterprise")).not.toContain("Demo");
    // Le altre voci restano tutte.
    expect(labels("enterprise")).toEqual(labels("demo").filter((l) => l !== "Demo"));
  });

  it("in enterprise non resta nessun percorso /backoffice/demo/ nella navigazione", () => {
    const hrefs = navForProfile(visibleNav(), "enterprise").flatMap((g) => g.items.map((i) => i.href));
    expect(hrefs.filter((h) => h.startsWith("/backoffice/demo"))).toEqual([]);
  });
});
