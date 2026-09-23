import { describe, it, expect } from "vitest";
import { liabilityBuckets } from "./liability";

describe("liabilityBuckets", () => {
  it("riempie 12 mesi dal mese corrente, somma il resto in 'oltre' e separa 'mai'", () => {
    const b = liabilityBuckets(
      [
        { month: "2026-10", amount: 1900 },
        { month: "2027-09", amount: 500 },
        { month: "2027-10", amount: 300 },
        { month: null, amount: 40 },
      ],
      new Date(2026, 8, 23),
    );
    expect(b).toHaveLength(14);
    expect(b[0].key).toBe("2026-09");
    expect(b[0].amount).toBe(0);
    expect(b[1]).toMatchObject({ key: "2026-10", amount: 1900 });
    expect(b[11]).toMatchObject({ key: "2027-08", amount: 0 });
    expect(b[12]).toMatchObject({ key: "later", amount: 800 });
    expect(b[13]).toMatchObject({ key: "never", amount: 40 });
  });

  it("senza lotti che non scadono non aggiunge 'mai'", () => {
    expect(liabilityBuckets([], new Date(2026, 0, 31))).toHaveLength(13);
  });
});
