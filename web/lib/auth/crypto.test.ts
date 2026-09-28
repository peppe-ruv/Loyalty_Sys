// @vitest-environment node
import { describe, expect, it } from "vitest";
import { deriveKey, digest, open, randomId, safeEqual, seal } from "./crypto";

// Cifratura delle sessioni e dello stato del login (AES-256-GCM con AAD, chiavi derivate con HKDF).

const MASTER = Buffer.from(Array.from({ length: 32 }, (_, i) => 255 - i));
const KEY = deriveKey(MASTER, "session");

function flip(sealed: string, index: number): string {
  const raw = Buffer.from(sealed, "base64url");
  raw[index] ^= 0x01;
  return raw.toString("base64url");
}

describe("seal/open", () => {
  it("andata e ritorno; IV casuale: lo stesso testo cifrato due volte è diverso", () => {
    const a = seal('{"accessToken":"eyJ…"}', KEY, "sessione-1");
    const b = seal('{"accessToken":"eyJ…"}', KEY, "sessione-1");
    expect(a).not.toBe(b);
    expect(open(a, KEY, "sessione-1")).toBe('{"accessToken":"eyJ…"}');
  });

  it.each([
    ["IV", 0],
    ["tag di autenticazione", 12],
    ["testo cifrato", 30],
  ])("un bit alterato nel %s ⇒ null", (_, index) => {
    const sealed = seal("x".repeat(40), KEY, "aad");
    expect(open(flip(sealed, index), KEY, "aad")).toBeNull();
  });

  it("AAD diverso (blob copiato su un'altra sessione) ⇒ null", () => {
    expect(open(seal("dati", KEY, "sessione-1"), KEY, "sessione-2")).toBeNull();
  });

  it("chiave diversa ⇒ null; blob troncato o non base64url ⇒ null", () => {
    const sealed = seal("dati", KEY, "aad");
    expect(open(sealed, deriveKey(MASTER, "auth-flow"), "aad")).toBeNull();
    expect(open(sealed.slice(0, 20), KEY, "aad")).toBeNull();
    expect(open("", KEY, "aad")).toBeNull();
    expect(open("%%%", KEY, "aad")).toBeNull();
  });
});

describe("chiavi e identificatori", () => {
  it("una chiave diversa per ogni scopo, deterministica dalla chiave maestra", () => {
    const keys = (["session", "auth-flow", "csrf"] as const).map((p) => deriveKey(MASTER, p).toString("hex"));
    expect(new Set(keys).size).toBe(3);
    expect(deriveKey(MASTER, "csrf").equals(deriveKey(MASTER, "csrf"))).toBe(true);
  });

  it("id di sessione da 256 bit in base64url, mai ripetuti", () => {
    const ids = Array.from({ length: 200 }, () => randomId());
    expect(new Set(ids).size).toBe(200);
    for (const id of ids) expect(id).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it("impronta stabile e confronto a tempo costante", () => {
    expect(digest("abc")).toBe(digest("abc"));
    expect(digest("abc")).not.toBe(digest("abd"));
    expect(safeEqual("token", "token")).toBe(true);
    expect(safeEqual("token", "tokem")).toBe(false);
    expect(safeEqual("token", "token-più-lungo")).toBe(false);
  });
});
