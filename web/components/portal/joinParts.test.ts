import { afterEach, describe, expect, it, vi } from "vitest";
import { waitForWallet } from "./joinParts";

const { redirectToLogin } = vi.hoisted(() => ({ redirectToLogin: vi.fn() }));
vi.mock("@/lib/auth/browser", async (orig) => ({ ...(await orig<typeof import("@/lib/auth/browser")>()), redirectToLogin: () => redirectToLogin() }));

afterEach(() => {
  vi.unstubAllGlobals();
  redirectToLogin.mockReset();
});

const reply = (status: number, code = "X") => new Response(JSON.stringify({ code }), { status });

describe("waitForWallet", () => {
  it("ready appena il wallet risponde, dopo i 404 iniziali", async () => {
    const f = vi.fn().mockResolvedValueOnce(reply(404)).mockResolvedValue(Response.json({}));
    vi.stubGlobal("fetch", f);
    expect(await waitForWallet("/v1/portal/me/wallet", 1000, 5)).toBe("ready");
    expect(f).toHaveBeenCalledTimes(2);
  });
  it("timeout se il wallet non arriva", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => reply(404)));
    expect(await waitForWallet("/v1/portal/me/wallet", 40, 5)).toBe("timeout");
  });
  it("401: login e stop subito, senza altri tentativi", async () => {
    const f = vi.fn(async () => reply(401, "UNAUTHENTICATED"));
    vi.stubGlobal("fetch", f);
    expect(await waitForWallet("/v1/portal/me/wallet", 1000, 5)).toBe("unauthenticated");
    expect(f).toHaveBeenCalledTimes(1);
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
  });
});
