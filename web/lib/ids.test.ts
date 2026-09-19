import { describe, it, expect } from "vitest";
import { ulid } from "./ids";

describe("ids/ulid", () => {
  it("genera 26 caratteri Crockford", () => {
    const value = ulid();
    expect(value).toHaveLength(26);
    expect(value).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  });
});
