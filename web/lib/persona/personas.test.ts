import { describe, it, expect } from "vitest";
import { BACKOFFICE_PERSONAS, DEFAULT_BACKOFFICE_USERNAME, findBackofficePersona } from "./personas";

// Le cinque personas del backoffice sono quelle di docs/10 §2 (e docs/01): username, nome e ruolo.
describe("persona/personas", () => {
  it("coincidono con docs/10 §2", () => {
    expect(BACKOFFICE_PERSONAS.map((p) => [p.username, p.displayName, p.role])).toEqual([
      ["marta.admin", "Marta Villa", "ADMIN"],
      ["luca.marketing", "Luca Serra", "MARKETING"],
      ["elena.legal", "Elena Riva", "LEGAL"],
      ["paolo.care", "Paolo Neri", "CARE"],
      ["sara.analyst", "Sara Longo", "ANALYST"],
    ]);
  });
  it("la persona predefinita esiste ed è ADMIN", () => {
    expect(findBackofficePersona(DEFAULT_BACKOFFICE_USERNAME)?.role).toBe("ADMIN");
  });
});
