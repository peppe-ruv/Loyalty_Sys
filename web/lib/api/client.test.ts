import { describe, it, expect, vi, afterEach } from "vitest";
import { LhError, lhFetch } from "./client";

describe("api/client", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("crea un LhError con i campi previsti", () => {
    const err = new LhError(404, "NOT_FOUND", "Member not found", false, [{ field: "id", message: "invalid" }]);
    expect(err.status).toBe(404);
    expect(err.code).toBe("NOT_FOUND");
    expect(err.detail).toBe("Member not found");
    expect(err.asleep).toBe(false);
    expect(err.errors).toHaveLength(1);
    expect(err.errors[0].field).toBe("id");
  });

  it("costruisce la querystring omettendo i valori vuoti o undefined", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: async () => JSON.stringify({ ok: true })
    });
    vi.stubGlobal("fetch", fetchMock);

    await lhFetch("member", "/v1/members", {
      query: { q: "test", page: 1, empty: "", missing: undefined, zero: 0 }
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/lh/member/v1/members?q=test&page=1&zero=0", expect.any(Object));
  });

  it("gestisce correttamente la paginazione in query e gestisce errori RFC 9457", async () => {
    expect.assertions(4);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => JSON.stringify({
        type: "VALIDATION",
        detail: "Bad request",
        errors: [{ field: "name", message: "required" }]
      })
    });
    vi.stubGlobal("fetch", fetchMock);

    try {
      await lhFetch("campaign", "v1/campaigns", { query: { size: 20 } });
    } catch (e: any) {
      expect(fetchMock).toHaveBeenCalledWith("/api/lh/campaign/v1/campaigns?size=20", expect.any(Object));
      expect(e).toBeInstanceOf(LhError);
      expect(e.status).toBe(400);
      expect(e.errors[0].field).toBe("name");
    }
  });
});
