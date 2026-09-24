import { describe, it, expect } from "vitest";
import { parsePersona, serializePersona, actorHeader, type Persona } from "./cookie";

describe("Testbook: Cookie Persona", () => {
  it("[TB-WEB-COOKIE-001] BO persona valida", () => {
    const p: Persona = { kind: "BO", username: "marta.admin", role: "ADMIN" };
    const raw = serializePersona(p);
    const parsed = parsePersona(raw);
    expect(parsed).toEqual(p);
  });

  it("[TB-WEB-COOKIE-002] MEMBER persona valida", () => {
    const p: Persona = { kind: "MEMBER", memberId: "MBR-1" };
    const raw = serializePersona(p);
    const parsed = parsePersona(raw);
    expect(parsed).toEqual(p);
  });

  it("[TB-WEB-COOKIE-003] payload non JSON", () => {
    expect(parsePersona("not-json")).toBeNull();
  });

  it("[TB-WEB-COOKIE-004] JSON valido ma campi BO mancanti", () => {
    // role mancante
    const badBo = encodeURIComponent(JSON.stringify({ kind: "BO", username: "marta.admin" }));
    expect(parsePersona(badBo)).toBeNull();
  });

  it("[TB-WEB-COOKIE-005] MEMBER senza memberId", () => {
    const badMember = encodeURIComponent(JSON.stringify({ kind: "MEMBER" }));
    expect(parsePersona(badMember)).toBeNull();
  });

  it("[TB-WEB-COOKIE-006] header per BO", () => {
    const p: Persona = { kind: "BO", username: "marta.admin", role: "ADMIN" };
    expect(actorHeader(p)).toBe("ADMIN:marta.admin");
  });

  it("[TB-WEB-COOKIE-007] header per MEMBER", () => {
    const p: Persona = { kind: "MEMBER", memberId: "MBR-1" };
    // Default fallback "ANALYST:anonymous" for Members calling services where X-LH-Actor is required
    expect(actorHeader(p)).toBe("ANALYST:anonymous");
  });
});
