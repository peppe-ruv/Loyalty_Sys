// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import * as route from "./route";

// Proxy /api/lh (docs/07 §3: «copia metodo, query, corpo»): HEAD e file CSV.
// Il finto servizio si comporta come quelli veri su due punti che un mock permissivo nasconderebbe:
// - la richiesta a valle passa da `new Request(...)`, che come `fetch` rifiuta un corpo su GET/HEAD;
// - `GET /v1/contests/{id}/winners.csv` è mappato con `produces = "text/csv"` (ContestController): Spring MVC risponde
//   406 se l'`Accept` non ammette `text/csv`, e gli errori tornano come `application/problem+json`.

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let seen: Request[];

function acceptsCsv(accept: string | null): boolean {
  if (!accept) return true;
  return accept.split(",").some((part) => {
    const type = part.split(";")[0].trim().toLowerCase();
    return type === "text/csv" || type === "text/*" || type === "*/*";
  });
}

beforeEach(() => {
  seen = [];
  process.env.LH_SVC_GAMIFICATION_URL = "http://gam.test";
  process.env.LH_SVC_WALLET_URL = "http://wal.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      const { signal: _signal, ...rest } = init;
      const req = new Request(url, rest); // TypeError con un corpo su GET/HEAD, come fetch
      seen.push(req);
      const u = new URL(req.url);
      if (u.pathname.endsWith("/winners.csv")) {
        if (!acceptsCsv(req.headers.get("accept"))) {
          return Response.json({ code: "BAD_REQUEST", status: 406 }, { status: 406, headers: { "content-type": "application/problem+json" } });
        }
        return new Response(req.method === "HEAD" ? null : "playId,memberId,nickname\nP1,MBR-000010,\n", {
          status: 200,
          headers: { "content-type": "text/csv;charset=UTF-8", "content-disposition": 'attachment; filename="iw-autunno-vincitori.csv"' },
        });
      }
      return new Response(req.method === "HEAD" ? null : JSON.stringify({ memberId: "MBR-000001", balance: 10 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_GAMIFICATION_URL;
  delete process.env.LH_SVC_WALLET_URL;
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });

it("HEAD: inoltrato come HEAD, senza corpo, con lo stato del servizio (non 503)", async () => {
  // Senza un HEAD esportato Next.js usa il gestore GET (auto-implement-methods): stesso percorso di codice.
  const head = (route as Record<string, unknown>).HEAD ?? route.GET;
  const res = await (head as typeof route.GET)(
    new NextRequest("http://web.test/api/lh/wallet/v1/wallets/MBR-000001", { method: "HEAD" }),
    ctx("wallet", ["v1", "wallets", "MBR-000001"]),
  );
  expect(res.status).toBe(200);
  expect(seen).toHaveLength(1);
  expect(seen[0].method).toBe("HEAD");
  expect(seen[0].body).toBeNull();
  expect(seen[0].url).toBe("http://wal.test/v1/wallets/MBR-000001");
  expect(res.headers.get("content-type")).toBe("application/json");
});

it("HEAD è esportato esplicitamente dal proxy", () => {
  expect((route as Record<string, unknown>).HEAD).toBe(route.GET);
});

it("vincitori CSV: il servizio riceve un Accept che ammette text/csv e il file arriva al browser (non 406)", async () => {
  const res = await route.GET(
    new NextRequest("http://web.test/api/lh/gamification/v1/contests/IW-AUTUNNO/winners.csv", { headers: { accept: "text/html" } }),
    ctx("gamification", ["v1", "contests", "IW-AUTUNNO", "winners.csv"]),
  );
  expect(res.status).toBe(200);
  expect(res.headers.get("content-type")).toBe("text/csv;charset=UTF-8");
  expect(res.headers.get("content-disposition")).toBe('attachment; filename="iw-autunno-vincitori.csv"');
  const accept = seen[0].headers.get("accept") ?? "";
  expect(accept).toContain("text/csv");
  // Gli errori del servizio (RFC 9457) restano accettabili anche su un percorso CSV.
  expect(accept).toContain("application/problem+json");
  expect(await res.text()).toContain("playId,memberId,nickname");
});

it("le API JSON continuano a chiedere application/json", async () => {
  await route.GET(new NextRequest("http://web.test/api/lh/wallet/v1/wallets/MBR-000001"), ctx("wallet", ["v1", "wallets", "MBR-000001"]));
  expect(seen[0].headers.get("accept")).toBe("application/json");
});
