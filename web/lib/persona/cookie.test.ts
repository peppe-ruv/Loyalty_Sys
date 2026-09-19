import { describe, it, expect } from "vitest";
import { parsePersona, serializePersona, actorHeader, DEFAULT_BO_PERSONA } from "./cookie";

describe("persona/cookie", () => {
  it("fa il round-trip di una persona BO", () => {
    const raw = serializePersona(DEFAULT_BO_PERSONA);
    expect(parsePersona(raw)).toEqual(DEFAULT_BO_PERSONA);
  });
  it("rifiuta valori non validi", () => {
    expect(parsePersona(undefined)).toBeNull();
    expect(parsePersona("non-json")).toBeNull();
    expect(parsePersona(encodeURIComponent(JSON.stringify({ kind: "X" })))).toBeNull();
  });
  it("costruisce l'header X-LH-Actor", () => {
    expect(actorHeader(DEFAULT_BO_PERSONA)).toBe("ADMIN:marta.admin");
    expect(actorHeader({ kind: "MEMBER", memberId: "MBR-1" })).toBe("ANALYST:anonymous");
  });
});
