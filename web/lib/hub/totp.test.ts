// @vitest-environment node
import { describe, expect, it } from "vitest";
import { base32Decode, hotp, totp } from "./totp";
import { OPERATORS_TOTP_SEED } from "./testUsers";

// HUB-02, ADR-051: TOTP RFC 6238 (SHA-1, 6 cifre, 30 s) contro i vettori di prova dell'appendice B (seme ASCII
// «12345678901234567890»; i vettori a 8 cifre valgono, troncati alle ultime 6, anche per 6).
const RFC_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

describe("totp (RFC 6238)", () => {
  it("base32 del seme di prova = ASCII «12345678901234567890»", () => {
    expect(base32Decode(RFC_SEED).toString("ascii")).toBe("12345678901234567890");
  });

  it.each([
    [59, "94287082"],
    [1111111109, "07081804"],
    [1111111111, "14050471"],
    [1234567890, "89005924"],
    [2000000000, "69279037"],
    [20000000000, "65353130"],
  ])("t=%i s → %s (8 cifre) e le ultime 6 a 6 cifre", (seconds, eight) => {
    expect(totp(RFC_SEED, seconds * 1000, { digits: 8 }).code).toBe(eight);
    expect(totp(RFC_SEED, seconds * 1000).code).toBe(eight.slice(2));
  });

  it("HOTP RFC 4226: vettori di prova del contatore 0..2", () => {
    const secret = Buffer.from("12345678901234567890");
    expect([0, 1, 2].map((c) => hotp(secret, c))).toEqual(["755224", "287082", "359152"]);
  });

  it("secondi restanti: da 30 all'inizio del periodo a 1 all'ultimo secondo", () => {
    expect(totp(RFC_SEED, 30_000).remainingSeconds).toBe(30);
    expect(totp(RFC_SEED, 59_999).remainingSeconds).toBe(1);
    expect(totp(RFC_SEED, 45_000).remainingSeconds).toBe(15);
  });

  it("il codice resta uguale dentro il periodo e cambia al successivo", () => {
    expect(totp(RFC_SEED, 30_000).code).toBe(totp(RFC_SEED, 59_999).code);
    expect(totp(RFC_SEED, 60_000).code).toBe(hotp(base32Decode(RFC_SEED), 2));
  });

  it("il seme di test dà 6 cifre; un seme non base32 è rifiutato", () => {
    expect(totp(OPERATORS_TOTP_SEED, 1_700_000_000_000).code).toMatch(/^\d{6}$/);
    expect(() => base32Decode("NON-BASE32!")).toThrow();
  });
});
