// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "./route";

// Q-492 (M8.2f): nel profilo demo l'ingresso delle azioni vuole il ruolo SOURCE; il proxy presenta `SOURCE:src-<codice>`
// con la fonte dichiarata dall'evento. Le altre chiamate POST restano con l'identità della persona.

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let seen: { url: string; init: RequestInit }[];

beforeEach(() => {
  seen = [];
  process.env.LH_SVC_INGESTION_URL = "http://ing.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      seen.push({ url: String(url), init });
      return Response.json({ status: "ACCEPTED" }, { status: 202 });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_INGESTION_URL;
});

const ctx = (path: string[]) => ({ params: Promise.resolve({ service: "ingestion", path }) });

function post(path: string[], body: unknown) {
  return POST(
    new NextRequest(`http://web.test/api/lh/ingestion/${path.join("/")}`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-lh-actor": "ADMIN:evil" },
      body: JSON.stringify(body),
    }),
    ctx(path),
  );
}

it("POST /v1/events: X-LH-Actor = SOURCE:src-<fonte dichiarata>, mai l'header del browser", async () => {
  const res = await post(["v1", "events"], { source: "ecommerce", type: "purchase.completed" });
  expect(res.status).toBe(202);
  expect((seen[0].init.headers as Headers).get("x-lh-actor")).toBe("SOURCE:src-ecommerce");
});

it("POST /v1/transactions e batch della stessa fonte", async () => {
  await post(["v1", "transactions"], { source: "urn:loyaltyhub:source:ecommerce", orderId: "O-1" });
  await post(["v1", "events", "batch"], [{ source: "app" }, { source: "app" }]);
  expect((seen[0].init.headers as Headers).get("x-lh-actor")).toBe("SOURCE:src-ecommerce");
  expect((seen[1].init.headers as Headers).get("x-lh-actor")).toBe("SOURCE:src-app");
});

it("un altro POST (registro fonti) e un batch con fonti miste restano con l'identità della persona", async () => {
  await post(["v1", "sources"], { source: "crm" });
  await post(["v1", "events", "batch"], [{ source: "app" }, { source: "crm" }]);
  expect((seen[0].init.headers as Headers).get("x-lh-actor")).toBe("ANALYST:anonymous");
  expect((seen[1].init.headers as Headers).get("x-lh-actor")).toBe("ANALYST:anonymous");
});
