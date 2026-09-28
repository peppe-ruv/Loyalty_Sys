import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LhError, lhFetch } from "./client";

// Client del proxy con il BFF (docs/07 §4-bis): token CSRF sulle richieste che cambiano stato quando esiste il cookie
// `__Host-lh_csrf` (profilo enterprise); nel profilo demo il cookie non c'è e gli header restano quelli di sempre.

let calls: { url: string; init: RequestInit }[];
let cookie = "";

beforeEach(() => {
  calls = [];
  cookie = "";
  Object.defineProperty(document, "cookie", { configurable: true, get: () => cookie });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete (document as unknown as Record<string, unknown>).cookie;
});

it("demo: POST con i soli header di sempre", async () => {
  cookie = "lh_persona=%7B%7D";
  await lhFetch("wallet", "/v1/x", { method: "POST", body: "{}" });
  expect(calls[0].init.headers).toEqual({ "content-type": "application/json" });
});

it("enterprise: X-LH-CSRF su POST/PATCH/PUT/DELETE e sugli upload, mai sui GET", async () => {
  cookie = "__Host-lh_csrf=tok-123";
  for (const method of ["POST", "PATCH", "PUT", "DELETE"]) await lhFetch("wallet", "/v1/x", { method, body: "{}" });
  await lhFetch("ingestion", "/v1/imports", { method: "POST", body: new FormData() });
  await lhFetch("wallet", "/v1/x");
  expect(calls.slice(0, 4).map((c) => c.init.headers)).toEqual(Array(4).fill({ "content-type": "application/json", "X-LH-CSRF": "tok-123" }));
  expect(calls[4].init.headers).toEqual({ "X-LH-CSRF": "tok-123" });
  expect(calls[5].init.headers).toEqual({ "content-type": "application/json" });
});

it("401 UNAUTHENTICATED del BFF ⇒ errore «da riautenticare»; un 401 del servizio no", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: "UNAUTHENTICATED", title: "Accesso richiesto" }), { status: 401 })));
  const bff = await lhFetch("wallet", "/v1/x").catch((e: LhError) => e);
  expect(bff).toBeInstanceOf(LhError);
  expect((bff as LhError).unauthenticated).toBe(true);
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: "UNAUTHORIZED" }), { status: 401 })));
  const service = await lhFetch("wallet", "/v1/x").catch((e: LhError) => e);
  expect((service as LhError).unauthenticated).toBe(false);
});
