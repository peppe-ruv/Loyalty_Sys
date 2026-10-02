import { describe, expect, it } from "vitest";
import { formatSeconds, formatSecondsShort } from "./duration";

describe("formatSeconds (BO-25)", () => {
  it("mostra secondi con una cifra decimale, mai millisecondi", () => {
    expect(formatSeconds(6426)).toBe("6,4 secondi");
    expect(formatSeconds(850)).toBe("0,9 secondi");
    expect(formatSeconds(0)).toBe("0,0 secondi");
    expect(formatSeconds(12_049)).toBe("12,0 secondi");
  });

  it("tollera valori negativi o non numerici", () => {
    expect(formatSeconds(-5)).toBe("0,0 secondi");
    expect(formatSeconds(Number.NaN)).toBe("0,0 secondi");
  });

  it("ha una forma breve per la timeline", () => {
    expect(formatSecondsShort(2100)).toBe("2,1 s");
  });
});
