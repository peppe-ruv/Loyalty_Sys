import { afterEach, expect, it, vi } from "vitest";
import { LhError, lhFetch } from "./client";

// Testbook TB-WEB §CLI: client del proxy (docs/07 §3: `SERVICE_ASLEEP` → stato degraded; docs/06 §2 errori RFC 9457 con
// `errors[]` → docs/07 §6 "Validation": errori di campo mappati sui campi del form).

let lastUrl = "";
function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      lastUrl = url;
      return new Response(body === undefined ? null : typeof body === "string" ? body : JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
    }),
  );
}
afterEach(() => vi.unstubAllGlobals());

const fail = (p: Promise<unknown>) => p.then(() => { throw new Error("atteso un errore"); }, (e: LhError) => e);

it("[TB-WEB-CLI-001] 503 {type: SERVICE_ASLEEP} dal proxy → errore «addormentato»", async () => {
  respond(503, { type: "SERVICE_ASLEEP", service: "wallet" });
  const e = await fail(lhFetch("wallet", "/v1/tiers"));
  expect([e.status, e.asleep]).toEqual([503, true]);
});

it("[TB-WEB-CLI-002] 503 del servizio senza SERVICE_ASLEEP → errore ordinario (non degraded)", async () => {
  respond(503, { title: "Manutenzione", code: "MAINTENANCE" });
  const e = await fail(lhFetch("wallet", "/v1/tiers"));
  expect([e.asleep, e.code]).toEqual([false, "MAINTENANCE"]);
});

it("[TB-WEB-CLI-003] 422 con errors[] → errori di campo {field, message}", async () => {
  respond(422, { title: "Dati non validi", code: "VALIDATION_FAILED", errors: [{ field: "note", message: "almeno 10 caratteri" }, { nope: 1 }] });
  const e = await fail(lhFetch("wallet", "/v1/x"));
  expect(e.errors).toEqual([{ field: "note", message: "almeno 10 caratteri" }]);
});

it("[TB-WEB-CLI-004] problema con code e detail → code e detail dell'errore", async () => {
  respond(409, { code: "VERSION_CONFLICT", detail: "Versione superata", title: "Conflitto" });
  const e = await fail(lhFetch("campaign", "/v1/campaigns/1"));
  expect([e.code, e.detail]).toEqual(["VERSION_CONFLICT", "Versione superata"]);
});

it("[TB-WEB-CLI-005] problema senza code né type → HTTP_<status>", async () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-CLI-005 — docs/06 §2 prevede sempre un problema RFC 9457; per un corpo non JSON il
  // codice sintetico HTTP_<status> è una scelta del client.
  respond(500, "boom");
  const e = await fail(lhFetch("campaign", "/v1/campaigns"));
  expect(e.code).toBe("HTTP_500");
});

it("[TB-WEB-CLI-006] 200 con JSON → corpo; 204 senza corpo → undefined", async () => {
  respond(200, { items: [], page: { number: 0 } });
  expect(await lhFetch("member", "/v1/members")).toEqual({ items: [], page: { number: 0 } });
  respond(204);
  expect(await lhFetch("member", "/v1/members/x/read")).toBeUndefined();
});

it("[TB-WEB-CLI-007] chiamata sempre via /api/lh/<service>/…, parametri vuoti o assenti omessi", async () => {
  respond(200, {});
  await lhFetch("member", "v1/members", { query: { q: "", page: 0, size: undefined, status: "ACTIVE" } });
  expect(lastUrl).toBe("/api/lh/member/v1/members?page=0&status=ACTIVE");
});
