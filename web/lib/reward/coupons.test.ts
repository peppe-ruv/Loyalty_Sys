import { describe, expect, it } from "vitest";
import { couponSegments, parseCodes } from "./coupons";

describe("couponSegments", () => {
  it("salta gli stati vuoti e mantiene l'ordine fisso", () => {
    const s = couponSegments({ USED: 141, AVAILABLE: 9, ISSUED: 0 });
    expect(s.map((x) => x.status)).toEqual(["AVAILABLE", "USED"]);
    expect(s[0].pct).toBeCloseTo(6);
  });
  it("nessun segmento per un pool vuoto", () => {
    expect(couponSegments({})).toEqual([]);
  });
});

describe("parseCodes", () => {
  it("accetta righe, virgole e punti e virgola", () => {
    expect(parseCodes("A-1\n B-2 ,C-3;;\n\n")).toEqual(["A-1", "B-2", "C-3"]);
  });
});
