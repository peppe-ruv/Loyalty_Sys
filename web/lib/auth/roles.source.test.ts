import { describe, expect, it } from "vitest";
import { effectiveRole, sessionKind } from "./roles";
import { BACKOFFICE_PERSONAS } from "@/lib/persona/personas";
import { parsePersona, serializePersona } from "@/lib/persona/cookie";

// Q-492: `SOURCE` è l'utenza di integrazione di una fonte di ingestion, non una persona del backoffice.
describe("ruolo SOURCE nel web", () => {
  it("non è tra le persone del backoffice", () => {
    expect(BACKOFFICE_PERSONAS.map((p) => p.role as string)).not.toContain("SOURCE");
  });

  it("un token con il solo SOURCE vale ANALYST (sola lettura), mai un ruolo operatore", () => {
    expect(effectiveRole(["SOURCE"])).toBe("ANALYST");
    expect(sessionKind(["SOURCE"])).toBe("operator");
  });

  it("con un ruolo operatore valgono i ruoli operatore: SOURCE non aumenta i poteri", () => {
    expect(effectiveRole(["SOURCE", "MARKETING"])).toBe("MARKETING");
    expect(effectiveRole(["SOURCE", "ADMIN"])).toBe("ADMIN");
    expect(effectiveRole(["SOURCE", "MARKETING", "LEGAL"])).toBe("ANALYST");
  });

  it("un cookie di persona con ruolo SOURCE vale ANALYST: il browser non può presentarsi come fonte", () => {
    const forged = serializePersona({ kind: "BO", username: "src-crm", role: "SOURCE" as never });
    expect(parsePersona(forged)).toEqual({ kind: "BO", username: "src-crm", role: "ANALYST" });
  });
});
