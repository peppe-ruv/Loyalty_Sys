import { describe, expect, it } from "vitest";
import { canSeeSampleProgram } from "./nav";

// V10 (ADR-051, BO-01): il riquadro «Carica il programma di esempio» si mostra solo a un ADMIN, in enterprise, nell'ambiente di test.
describe("canSeeSampleProgram", () => {
  it("vero solo con ADMIN + enterprise + ambiente di test", () => {
    const roles = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"] as const;
    for (const role of roles) {
      for (const mode of ["demo", "enterprise"] as const) {
        for (const test of [false, true]) {
          expect(canSeeSampleProgram(role, mode, test)).toBe(role === "ADMIN" && mode === "enterprise" && test);
        }
      }
    }
  });
});
