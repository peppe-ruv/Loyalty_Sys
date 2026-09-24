import { describe, it, expect } from "vitest";
import { can, requiredRoleHint, isReadOnly, canWrite, canApprove, canAdjustPoints, canRunDemoAdmin } from "./permissions";
import { Role } from "./personas";

describe("persona/permissions", () => {
  it("controlla i permessi in base alla matrice per ogni ruolo e capability", () => {
    const roles: Role[] = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"];

    // Matrice attesa in base a permissions.ts
    const expected: Record<string, Role[]> = {
      "member.write": ["ADMIN", "CARE"],
      "member.anonymize": ["ADMIN"],
      "points.adjust": ["ADMIN", "CARE"],
      "segment.write": ["ADMIN", "MARKETING"],
      "object.edit": ["ADMIN", "MARKETING"],
      "object.approve": ["ADMIN", "LEGAL"],
      "content.write": ["ADMIN", "MARKETING"],
      "webhook.write": ["ADMIN"],
      "program.config": ["ADMIN"],
      "actiontype.custom": ["ADMIN", "MARKETING"],
      "redemption.handle": ["ADMIN", "CARE"],
      "instants.view": ["ADMIN", "LEGAL"],
      "delivery.handle": ["ADMIN", "CARE"],
      "coupon.use": ["ADMIN", "MARKETING", "LEGAL", "CARE"],
      "coupon.void": ["ADMIN", "CARE"],
      "inbound.handle": ["ADMIN", "CARE"],
      "dlq.handle": ["ADMIN"],
      "demo.simulate": ["ADMIN", "MARKETING", "LEGAL", "CARE"],
      "demo.admin": ["ADMIN"],
    };

    for (const capability of Object.keys(expected) as any[]) {
      for (const role of roles) {
        expect(can(role, capability)).toBe(expected[capability].includes(role));
      }
    }
  });

  it("verifica che le helper functions espongano il permesso corretto", () => {
    // isReadOnly (solo ANALYST)
    expect(isReadOnly("ANALYST")).toBe(true);
    expect(isReadOnly("ADMIN")).toBe(false);

    // canWrite
    expect(canWrite("ADMIN")).toBe(true);
    expect(canWrite("ANALYST")).toBe(false);

    // canApprove
    expect(canApprove("LEGAL")).toBe(true);
    expect(canApprove("MARKETING")).toBe(false);

    // canAdjustPoints
    expect(canAdjustPoints("CARE")).toBe(true);
    expect(canAdjustPoints("LEGAL")).toBe(false);

    // canRunDemoAdmin
    expect(canRunDemoAdmin("ADMIN")).toBe(true);
    expect(canRunDemoAdmin("CARE")).toBe(false);
  });

  it("restituisce un suggerimento con i ruoli richiesti", () => {
    expect(requiredRoleHint("object.approve")).toMatch(/ADMIN/);
    expect(requiredRoleHint("object.approve")).toMatch(/LEGAL/);
  });
});
