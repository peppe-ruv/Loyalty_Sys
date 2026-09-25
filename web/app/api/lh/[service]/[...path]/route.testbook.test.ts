// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PRX: proxy verso i microservizi (docs/07 §3; docs/06 §3 per X-LH-Actor).

let cookieValue: string | undefined;
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "lh_persona" && cookieValue !== undefined ? { name, value: cookieValue } : undefined) }),
}));


type Call = { url: string; init: RequestInit & { headers: Headers } };
let calls: Call[];
let upstream: (url: string, init: RequestInit) => Promise<Response>;

beforeEach(() => {
  cookieValue = undefined;
  calls = [];
  upstream = async () => new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      calls.push({ url: String(url), init: init as Call["init"] });
      return upstream(String(url), init);
    }),
  );
  process.env.LH_SVC_WALLET_URL = "http://wallet.test";
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  delete process.env.LH_SVC_WALLET_URL;
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });
const get = (url: string, headers: Record<string, string> = {}) => new NextRequest(url, { headers });
const enc = (v: unknown) => encodeURIComponent(JSON.stringify(v));

it("[TB-WEB-PRX-001] GET inoltrato a LH_SVC_<SERVICE>_URL con percorso e query copiati", async () => {
  const res = await GET(get("http://localhost/api/lh/wallet/v1/wallets/MBR-000002?size=3&page=1"), ctx("wallet", ["v1", "wallets", "MBR-000002"]));
  expect(res.status).toBe(200);
  expect(calls[0].url).toBe("http://wallet.test/v1/wallets/MBR-000002?size=3&page=1");
  expect(calls[0].init.method).toBe("GET");
  expect(calls[0].init.body).toBeUndefined();
});

it("[TB-WEB-PRX-002] POST: metodo e corpo copiati", async () => {
  const body = JSON.stringify({ currency: "PTS", amount: 10 });
  const req = new NextRequest("http://localhost/api/lh/wallet/v1/wallets/MBR-000002/adjustments", { method: "POST", body, headers: { "content-type": "application/json" } });
  await POST(req, ctx("wallet", ["v1", "wallets", "MBR-000002", "adjustments"]));
  expect(calls[0].init.method).toBe("POST");
  expect(calls[0].init.body).toBe(body);
  expect(calls[0].init.headers.get("content-type")).toBe("application/json");
});

it("[TB-WEB-PRX-003] X-LH-Actor dal cookie persona BO", async () => {
  cookieValue = enc({ kind: "BO", username: "elena.legal", role: "LEGAL" });
  await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(calls[0].init.headers.get("x-lh-actor")).toBe("LEGAL:elena.legal");
});

it("[TB-WEB-PRX-004] cookie assente → X-LH-Actor ANALYST:anonymous", async () => {
  await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(calls[0].init.headers.get("x-lh-actor")).toBe("ANALYST:anonymous");
});

it("[TB-WEB-PRX-005] cookie non valido → X-LH-Actor ANALYST:anonymous", async () => {
  cookieValue = "non-json";
  await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(calls[0].init.headers.get("x-lh-actor")).toBe("ANALYST:anonymous");
});

it("[TB-WEB-PRX-006] X-Correlation-Id presente nella richiesta → inoltrato e restituito uguale", async () => {
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers", { "x-correlation-id": "01JCORRELATION000000000000" }), ctx("wallet", ["v1", "tiers"]));
  expect(calls[0].init.headers.get("x-correlation-id")).toBe("01JCORRELATION000000000000");
  expect(res.headers.get("x-correlation-id")).toBe("01JCORRELATION000000000000");
});

it("[TB-WEB-PRX-007] X-Correlation-Id assente → nuovo ULID (26 caratteri Crockford)", async () => {
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  const sent = calls[0].init.headers.get("x-correlation-id") ?? "";
  expect(sent).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  expect(res.headers.get("x-correlation-id")).toBe(sent);
});

it.each(rows([
  { id: "TB-WEB-PRX-008", desc: "502", status: 502 },
  { id: "TB-WEB-PRX-009", desc: "503", status: 503 },
  { id: "TB-WEB-PRX-010", desc: "504", status: 504 },
]))("[%s] risposta %s dal servizio → 503 {type: SERVICE_ASLEEP, service}", async (_id, _desc, { status }) => {
  upstream = async () => new Response("bad gateway", { status });
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(res.status).toBe(503);
  expect(await res.json()).toEqual({ type: "SERVICE_ASLEEP", service: "wallet" });
});

it("[TB-WEB-PRX-011] errore di rete → 503 SERVICE_ASLEEP", async () => {
  upstream = async () => {
    throw new TypeError("fetch failed");
  };
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(res.status).toBe(503);
  expect(await res.json()).toEqual({ type: "SERVICE_ASLEEP", service: "wallet" });
});

it("[TB-WEB-PRX-012] nessuna risposta entro 25 s → richiesta interrotta, 503 SERVICE_ASLEEP", async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | undefined;
  upstream = (_url, init) =>
    new Promise<Response>((_resolve, reject) => {
      signal = init.signal ?? undefined;
      signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    });
  const pending = GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  await vi.advanceTimersByTimeAsync(24_999);
  expect(signal?.aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(1);
  const res = await pending;
  expect(signal?.aborted).toBe(true);
  expect(res.status).toBe(503);
});

it("[TB-WEB-PRX-013] errore applicativo 422 (RFC 9457) → inoltrato tale e quale", async () => {
  const problem = { type: "about:blank", title: "Nota troppo corta", status: 422, code: "NOTE_TOO_SHORT" };
  upstream = async () => new Response(JSON.stringify(problem), { status: 422, headers: { "content-type": "application/problem+json" } });
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(res.status).toBe(422);
  expect(res.headers.get("content-type")).toBe("application/problem+json");
  expect(await res.json()).toEqual(problem);
});

it("[TB-WEB-PRX-014] errore 500 del servizio sveglio → inoltrato come 500 (non degraded)", async () => {
  upstream = async () => new Response(JSON.stringify({ title: "Errore interno" }), { status: 500 });
  const res = await GET(get("http://localhost/api/lh/wallet/v1/tiers"), ctx("wallet", ["v1", "tiers"]));
  expect(res.status).toBe(500);
});

it("[TB-WEB-PRX-015] servizio sconosciuto → 404 UNKNOWN_SERVICE, nessuna chiamata a valle", async () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-PRX-015 — docs/07 §3 non dice cosa risponde il proxy per un servizio inesistente.
  const res = await GET(get("http://localhost/api/lh/payments/v1/x"), ctx("payments", ["v1", "x"]));
  expect(res.status).toBe(404);
  expect(await res.json()).toEqual({ type: "UNKNOWN_SERVICE", service: "payments" });
  expect(calls).toHaveLength(0);
});
