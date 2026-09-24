import { describe, it, expect } from "vitest";
import { cn } from "./cn";

describe("cn", () => {
  it("concatena le classi ignorando i valori falsy", () => {
    expect(cn("a", "b")).toBe("a b");
    expect(cn("a", false, "b", null, undefined, "c")).toBe("a b c");
    expect(cn(undefined)).toBe("");
  });
});
