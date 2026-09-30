// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";

// F2-SEC-03, ADR-042, docs/07 §3: il percorso a valle è validato anche nel profilo demo. Next decodifica i segmenti
// (`%2F` → `/`, `%2e%2e` → `..`) e `new URL()` li normalizza: un percorso ostile non deve raggiungere i servizi, né
// ottenere un'identità decisa su un percorso diverso (es. `v1/portal/../members` non riceve `x-lh-member`).

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let seen: { url: string; headers: Headers }[];

beforeEach(() => {
  seen = [];
  delete process.env.LH_MODE;
  process.env.LH_SVC_WALLET_URL = "http://wal.test";
  process.env.LH_SVC_MEMBER_URL = "http://member.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      seen.push({ url: String(url), headers: new Headers(init.headers) });
      return Response.json({ ok: true });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_WALLET_URL;
  delete process.env.LH_SVC_MEMBER_URL;
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });
const get = (service: string, path: string[]) =>
  GET(new NextRequest(`http://web.test/api/lh/${service}/${path.map(encodeURIComponent).join("/")}`), ctx(service, path));

// Next passa i segmenti già decodificati: si provano i valori decodificati.
const HOSTILE: [string, string[]][] = [
  ["punto-punto dopo portal", ["v1", "portal", "..", "members", "MBR-000009"]],
  ["punto-punto in mezzo", ["v1", "wallets", "x", "..", "..", "members"]],
  ["punto singolo", ["v1", ".", "wallets"]],
  ["%2F decodificato dentro un segmento", ["v1", "portal", "x/../members", "MBR-000009"]],
  ["%2e%2e decodificato", ["v1", "%2e%2e", "members"]],
  ["punto e virgola (parametro di matrice)", ["v1", "wallets;jsessionid=1", "MBR-000001"]],
  ["segmento vuoto", ["v1", "", "wallets"]],
  ["barra rovesciata", ["v1", "wallets\\..\\members"]],
  ["byte NUL", ["v1", "wallets", "MBR-000001\u0000"]],
];

for (const [name, path] of HOSTILE) {
  it(`profilo demo: ${name} ⇒ 400 INVALID_PATH e nessuna chiamata a valle`, async () => {
    const res = await get("wallet", path);
    expect(res.status).toBe(400);
    expect((await res.json()).code).toBe("INVALID_PATH");
    expect(seen).toHaveLength(0);
  });
}

it("profilo demo: anche un POST con percorso ostile ⇒ 400 e nessuna chiamata a valle", async () => {
  const req = new NextRequest("http://web.test/api/lh/wallet/v1/x", { method: "POST", body: "{}", headers: { "content-type": "application/json" } });
  const res = await POST(req, ctx("wallet", ["v1", "portal", "..", "members"]));
  expect(res.status).toBe(400);
  expect(seen).toHaveLength(0);
});

it("profilo demo: percorso normale invariato (URL a valle, x-lh-actor, nessun x-lh-member fuori dal portale)", async () => {
  const res = await get("wallet", ["v1", "wallets", "MBR-000001"]);
  expect(res.status).toBe(200);
  expect(seen).toHaveLength(1);
  expect(seen[0].url).toBe("http://wal.test/v1/wallets/MBR-000001");
  expect(seen[0].headers.get("x-lh-actor")).toBeTruthy();
  expect(seen[0].headers.get("x-lh-member")).toBeNull();
});

it("profilo demo: sulle API del portale il membro attivo viaggia come prima (x-lh-member)", async () => {
  const res = await get("member", ["v1", "portal", "campaigns"]);
  expect(res.status).toBe(200);
  expect(seen).toHaveLength(1);
  expect(seen[0].url).toBe("http://member.test/v1/portal/campaigns");
  expect(seen[0].headers.get("x-lh-member")).toBe("MBR-000002");
});

it("profilo demo: la query string è conservata e non altera il percorso validato", async () => {
  const res = await GET(new NextRequest("http://web.test/api/lh/wallet/v1/wallets?limit=5&page=1"), ctx("wallet", ["v1", "wallets"]));
  expect(res.status).toBe(200);
  expect(seen[0].url).toBe("http://wal.test/v1/wallets?limit=5&page=1");
});
