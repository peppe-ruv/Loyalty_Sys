import { describe, expect, it } from "vitest";
import { isVersionConflict, withVersion, withoutVersion } from "./version";

describe("versioni (M7.6)", () => {
  it("riconosce solo il 409 VERSION_CONFLICT", () => {
    expect(isVersionConflict({ status: 409, code: "VERSION_CONFLICT" })).toBe(
      true,
    );
    expect(
      isVersionConflict({ status: 409, code: "CAMPAIGN_LIVE_LOCKED" }),
    ).toBe(false);
    expect(isVersionConflict({ status: 422, code: "VERSION_CONFLICT" })).toBe(
      false,
    );
    expect(isVersionConflict(null)).toBe(false);
  });

  it("aggiunge e toglie la versione senza toccare il resto", () => {
    const body = withVersion({ name: "Nuovo" }, 3);
    expect(body).toEqual({ name: "Nuovo", version: 3 });
    expect(withVersion({ name: "x" }, undefined)).toEqual({ name: "x" });
    expect(withVersion({ name: "x" }, 0)).toEqual({ name: "x", version: 0 });
    expect(withoutVersion(body)).toEqual({ name: "Nuovo" });
  });
});
