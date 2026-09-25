import { describe, it, expect } from "vitest";
import { parsePersona, serializePersona, actorHeader, DEFAULT_BO_PERSONA, backofficePersonaFromUsername } from "./cookie";

describe("persona/cookie", () => {
  it("fa il round-trip di una persona BO", () => {
    const raw = serializePersona(DEFAULT_BO_PERSONA);
    expect(parsePersona(raw)).toEqual(DEFAULT_BO_PERSONA);
  });
  it("fa il round-trip di una persona MEMBER", () => {
    const member = { kind: "MEMBER" as const, memberId: "MBR-1" };
    const raw = serializePersona(member);
    expect(parsePersona(raw)).toEqual(member);
  });
  it("rifiuta valori non validi", () => {
    expect(parsePersona(undefined)).toBeNull();
    expect(parsePersona(null)).toBeNull();
    expect(parsePersona("non-json")).toBeNull();
    expect(parsePersona(encodeURIComponent(JSON.stringify({ kind: "X" })))).toBeNull();
    expect(parsePersona(encodeURIComponent(JSON.stringify({ kind: "BO" })))).toBeNull();
    expect(parsePersona(encodeURIComponent(JSON.stringify({ kind: "BO", username: "x" })))).toBeNull();
    expect(parsePersona(encodeURIComponent(JSON.stringify({ kind: "MEMBER" })))).toBeNull();
  });
  it("costruisce l'header X-LH-Actor", () => {
    expect(actorHeader(DEFAULT_BO_PERSONA)).toBe("ADMIN:marta.admin");
    expect(actorHeader({ kind: "MEMBER", memberId: "MBR-1" })).toBe("ANALYST:anonymous");
    expect(actorHeader(null)).toBe("ANALYST:anonymous");
  });
  it("recupera una persona dal nome utente", () => {
    const p = backofficePersonaFromUsername("marta.admin");
    expect(p.kind).toBe("BO");
    if (p.kind === "BO") {
        expect(p.username).toBe("marta.admin");
    }

    // Fallback ad ANALYST
    const missing = backofficePersonaFromUsername("not.found");
    if (missing.kind === "BO") {
        expect(missing.role).toBe("ANALYST");
    }
  });
});
