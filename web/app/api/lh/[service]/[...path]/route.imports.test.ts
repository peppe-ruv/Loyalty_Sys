// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "./route";

// Proxy /api/lh per l'import file (BO-32, F2-ING-02): il corpo multipart passa byte per byte e l'Idempotency-Key
// arriva al servizio (Q-353); il JSON resta testo come prima.

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let seen: { url: string; init: RequestInit }[];

beforeEach(() => {
  seen = [];
  process.env.LH_SVC_INGESTION_URL = "http://ing.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      seen.push({ url: String(url), init });
      return Response.json({ id: "01JOB", status: "QUEUED" }, { status: 202 });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_INGESTION_URL;
});

const ctx = (path: string[]) => ({ params: Promise.resolve({ service: "ingestion", path }) });

it("inoltra il multipart come byte, con content-type e Idempotency-Key", async () => {
  const bytes = new Uint8Array([0x69, 0x64, 0x2c, 0xc3, 0xa8, 0x0a]); // "id,è\n" in UTF-8
  const boundary = "----lhtest";
  const head = `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.csv"\r\nContent-Type: text/csv\r\n\r\n`;
  const tail = `\r\n--${boundary}--\r\n`;
  const body = new Uint8Array([...new TextEncoder().encode(head), ...bytes, ...new TextEncoder().encode(tail)]);
  const req = new NextRequest("http://web.test/api/lh/ingestion/v1/imports", {
    method: "POST",
    headers: { "content-type": `multipart/form-data; boundary=${boundary}`, "idempotency-key": "bo32-k1" },
    body,
  });
  const res = await POST(req, ctx(["v1", "imports"]));
  expect(res.status).toBe(202);
  expect(seen).toHaveLength(1);
  expect(seen[0].url).toBe("http://ing.test/v1/imports");
  const headers = seen[0].init.headers as Headers;
  expect(headers.get("content-type")).toBe(`multipart/form-data; boundary=${boundary}`);
  expect(headers.get("idempotency-key")).toBe("bo32-k1");
  expect(new Uint8Array(seen[0].init.body as ArrayBuffer)).toEqual(body);
});

it("il JSON resta testo e senza Idempotency-Key non la inventa", async () => {
  const req = new NextRequest("http://web.test/api/lh/ingestion/v1/events/batch", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify([{ id: "e-1" }]),
  });
  await POST(req, ctx(["v1", "events", "batch"]));
  expect(seen[0].init.body).toBe('[{"id":"e-1"}]');
  expect((seen[0].init.headers as Headers).has("idempotency-key")).toBe(false);
});
