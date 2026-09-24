import { describe, expect, it } from "vitest";
import { isResetConfirmed } from "./reset";

describe("conferma del reset (BO-30)", () => {
  it.each([
    ["RESET", true],
    ["  RESET ", true],
    ["reset", false],
    ["RESE", false],
    ["RESET!", false],
    ["", false],
    [null, false],
    [undefined, false],
  ])("%j → %s", (typed, expected) => {
    expect(isResetConfirmed(typed)).toBe(expected);
  });
});
