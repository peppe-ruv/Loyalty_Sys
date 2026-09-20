import { describe, it, expect } from "vitest";
import { niceMax, ticks, linearScale, areaPath } from "./scale";

describe("charts/scale", () => {
  it("niceMax arrotonda verso l'alto a valori tondi", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(180)).toBe(200);
    expect(niceMax(4200)).toBe(5000);
    expect(niceMax(2300)).toBe(2500);
  });

  it("ticks include 0 e il massimo", () => {
    const t = ticks(200, 4);
    expect(t[0]).toBe(0);
    expect(t[t.length - 1]).toBe(200);
    expect(t).toHaveLength(5);
  });

  it("linearScale mappa 0 sul fondo e max in cima (y invertita)", () => {
    const s = linearScale(10, 100, 200, 100, { top: 0, right: 0, bottom: 0, left: 0 });
    expect(s.y(0)).toBe(100);
    expect(s.y(100)).toBe(0);
    expect(s.x(0)).toBe(0);
  });

  it("areaPath chiude il poligono (Z)", () => {
    const top: Array<[number, number]> = [
      [0, 0],
      [10, 5],
    ];
    const base: Array<[number, number]> = [
      [0, 10],
      [10, 10],
    ];
    expect(areaPath(top, base)).toMatch(/Z$/);
  });
});
