import { afterEach, describe, it, expect, vi } from "vitest";
import { ulid } from "./ids";

const ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ids/ulid", () => {
  it("genera 26 caratteri Crockford", () => {
    const value = ulid();
    expect(value).toHaveLength(26);
    expect(value).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });

  it("la parte casuale viene da crypto.getRandomValues (16 byte) e non da Math.random", () => {
    const random = vi.spyOn(Math, "random");
    const getRandomValues = vi.spyOn(globalThis.crypto, "getRandomValues");
    ulid();
    expect(getRandomValues).toHaveBeenCalledTimes(1);
    const arg = getRandomValues.mock.calls[0][0] as Uint8Array;
    expect(arg).toBeInstanceOf(Uint8Array);
    expect(arg.length).toBe(16);
    expect(random).not.toHaveBeenCalled();
  });

  it("mappa ogni byte casuale su ENCODING[byte % 32]", () => {
    const bytes = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
    const special = [31, 32, 255];
    const all = [...bytes.slice(0, 13), ...special];
    vi.spyOn(globalThis.crypto, "getRandomValues").mockImplementation(((
      array: Uint8Array,
    ) => {
      all.forEach((b, i) => {
        array[i] = b;
      });
      return array;
    }) as typeof crypto.getRandomValues);
    const suffix = ulid(0).slice(10);
    expect(suffix).toBe(all.map((b) => ENCODING[b % 32]).join(""));
  });

  it("codifica il tempo nei primi 10 caratteri", () => {
    expect(ulid(0).slice(0, 10)).toBe("0000000000");
  });

  it("a parità di istante il prefisso è uguale e il suffisso casuale differisce", () => {
    const a = ulid(1_700_000_000_000);
    const b = ulid(1_700_000_000_000);
    expect(a.slice(0, 10)).toBe(b.slice(0, 10));
    expect(a.slice(10)).not.toBe(b.slice(10));
  });
});
