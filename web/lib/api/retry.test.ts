import { describe, expect, it } from "vitest";
import { LhError } from "./client";
import { retryDelay, shouldRetry } from "./retry";

const err = (status: number, code: string) => new LhError(status, code, "", false);

describe("regola di nuovo tentativo", () => {
  it("409 MEMBER_NOT_LINKED: fino a 5 tentativi, ogni 2 s", () => {
    const e = err(409, "MEMBER_NOT_LINKED");
    for (let i = 0; i < 5; i++) expect(shouldRetry(i, e)).toBe(true);
    expect(shouldRetry(5, e)).toBe(false);
    expect(retryDelay(0, e)).toBe(2000);
    expect(retryDelay(4, e)).toBe(2000);
  });
  it("un altro 409 e gli altri errori: una volta sola", () => {
    expect(shouldRetry(0, err(409, "CONFLICT"))).toBe(true);
    expect(shouldRetry(1, err(409, "CONFLICT"))).toBe(false);
    expect(shouldRetry(1, err(500, "X"))).toBe(false);
  });
  it("sessione scaduta: mai", () => {
    expect(shouldRetry(0, err(401, "UNAUTHENTICATED"))).toBe(false);
  });
});
