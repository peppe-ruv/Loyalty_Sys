import { describe, it, expect } from "vitest";
import { can, type Capability } from "./permissions";
import type { Role } from "./personas";

describe("Testbook: Permissions Matrix", () => {
  const allRoles: Role[] = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"];

  // Definisci le capabilities e i ruoli autorizzati attesi dalla Spec
  const specs: Array<{ capability: Capability; allowedRoles: Role[] }> = [
    { capability: "member.write", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "member.anonymize", allowedRoles: ["ADMIN"] },
    { capability: "points.adjust", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "segment.write", allowedRoles: ["ADMIN", "MARKETING"] },
    { capability: "object.edit", allowedRoles: ["ADMIN", "MARKETING"] },
    { capability: "object.approve", allowedRoles: ["ADMIN", "LEGAL"] },
    { capability: "content.write", allowedRoles: ["ADMIN", "MARKETING"] },
    { capability: "webhook.write", allowedRoles: ["ADMIN"] },
    { capability: "program.config", allowedRoles: ["ADMIN"] },
    { capability: "actiontype.custom", allowedRoles: ["ADMIN", "MARKETING"] },
    { capability: "redemption.handle", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "instants.view", allowedRoles: ["ADMIN", "LEGAL"] },
    { capability: "delivery.handle", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "coupon.use", allowedRoles: ["ADMIN", "MARKETING", "LEGAL", "CARE"] },
    { capability: "coupon.void", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "inbound.handle", allowedRoles: ["ADMIN", "CARE"] },
    { capability: "dlq.handle", allowedRoles: ["ADMIN"] },
    { capability: "demo.simulate", allowedRoles: ["ADMIN", "MARKETING", "LEGAL", "CARE"] },
    { capability: "demo.admin", allowedRoles: ["ADMIN"] },
  ];

  let idCounter = 1;

  for (const spec of specs) {
    it.each(allRoles)(
      `[TB-WEB-PERM-${String(idCounter++).padStart(3, "0")}] capability = ${spec.capability}, role = %s`,
      (role) => {
        const isAllowed = spec.allowedRoles.includes(role);
        expect(can(role, spec.capability)).toBe(isAllowed);
      }
    );
  }

  it(`[TB-WEB-PERM-040] ruolo non riconosciuto`, () => {
    // @ts-expect-error test
    expect(can("FAKE_ROLE", "member.write")).toBe(false);
  });
});
