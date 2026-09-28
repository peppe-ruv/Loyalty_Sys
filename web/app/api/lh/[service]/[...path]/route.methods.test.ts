// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, HEAD, POST } from "./route";

// Proxy /api/lh (docs/07 §3: «copia metodo, query, corpo»): HEAD, risposte senza corpo e file CSV.
// Il finto servizio si comporta come quelli veri su punti che un mock permissivo nasconderebbe:
// - la richiesta a valle passa da `new Request(...)`, che come `fetch` rifiuta un corpo su GET/HEAD;
// - `GET /v1/contests/{id}/winners.csv` è mappato con `produces = "text/csv"` (ContestController): Spring MVC risponde
//   406 se l'`Accept` non ammette `text/csv`, e gli errori tornano come `application/problem+json`;
// - HEAD risponde con le intestazioni del GET (content-length compreso) e senza corpo.

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let seen: Request[];

const CSV = "playId,memberId,nickname\nP1,MBR-000010,\n";
const WALLET = JSON.stringify({ memberId: "MBR-000001", balance: 10 });
const problem = (status: number) =>
  new Response(JSON.stringify({ code: status === 404 ? "NOT_FOUND" : "BAD_REQUEST", status }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });

function acceptsCsv(accept: string | null): boolean {
  if (!accept) return true;
  return accept.split(",").some((part) => {
    const type = part.split(";")[0].trim().toLowerCase();
    return type === "text/csv" || type === "text/*" || type === "*/*";
  });
}

/** Risposta come la darebbe il servizio: per HEAD stesse intestazioni del GET, senza corpo. */
function reply(req: Request, body: string, headers: Record<string, string>): Response {
  const length = String(new TextEncoder().encode(body).byteLength);
  return new Response(req.method === "HEAD" ? null : body, { status: 200, headers: { ...headers, "content-length": length } });
}

beforeEach(() => {
  seen = [];
  process.env.LH_SVC_GAMIFICATION_URL = "http://gam.test";
  process.env.LH_SVC_WALLET_URL = "http://wal.test";
  process.env.LH_SVC_MEMBER_URL = "http://member.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      const { signal: _signal, ...rest } = init;
      const req = new Request(url, rest); // TypeError con un corpo su GET/HEAD, come fetch
      seen.push(req);
      const u = new URL(req.url);
      if (u.pathname.endsWith("/winners.csv")) {
        if (!acceptsCsv(req.headers.get("accept"))) return problem(406);
        if (u.pathname.includes("/NOPE/")) return problem(404);
        return reply(req, CSV, { "content-type": "text/csv;charset=UTF-8", "content-disposition": 'attachment; filename="iw-autunno-vincitori.csv"' });
      }
      // Salvataggio della consegna di un premio (BO-14, ContestController): 204 senza corpo.
      if (u.pathname.endsWith("/delivery")) return new Response(null, { status: 204 });
      if (u.pathname.endsWith("/not-modified")) return new Response(null, { status: 304, headers: { etag: '"v1"' } });
      return reply(req, WALLET, { "content-type": "application/json" });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_GAMIFICATION_URL;
  delete process.env.LH_SVC_WALLET_URL;
  delete process.env.LH_SVC_MEMBER_URL;
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });

it("HEAD è esportato esplicitamente dal proxy, con lo stesso gestore del GET", () => {
  expect(HEAD).toBe(GET);
});

it("HEAD: inoltrato come HEAD, senza corpo, con stato, tipo e lunghezza del servizio (non 503)", async () => {
  const res = await HEAD(
    new NextRequest("http://web.test/api/lh/wallet/v1/wallets/MBR-000001", { method: "HEAD" }),
    ctx("wallet", ["v1", "wallets", "MBR-000001"]),
  );
  expect(res.status).toBe(200);
  expect(seen).toHaveLength(1);
  expect(seen[0].method).toBe("HEAD");
  expect(seen[0].body).toBeNull();
  expect(seen[0].url).toBe("http://wal.test/v1/wallets/MBR-000001");
  expect(res.headers.get("content-type")).toBe("application/json");
  expect(res.headers.get("content-length")).toBe(String(WALLET.length));
  expect(res.body).toBeNull();
});

it("HEAD sui vincitori: stessa richiesta del GET (resolve=ids), nessuna chiamata a member-service, niente content-length", async () => {
  const res = await HEAD(
    new NextRequest("http://web.test/api/lh/gamification/v1/contests/IW-AUTUNNO/winners.csv", { method: "HEAD" }),
    ctx("gamification", ["v1", "contests", "IW-AUTUNNO", "winners.csv"]),
  );
  expect(res.status).toBe(200);
  expect(seen).toHaveLength(1);
  expect(seen[0].method).toBe("HEAD");
  expect(seen[0].url).toBe("http://gam.test/v1/contests/IW-AUTUNNO/winners.csv?resolve=ids");
  // Il GET riscrive il CSV coi soprannomi: la lunghezza del servizio non sarebbe quella vista dal browser.
  expect(res.headers.get("content-length")).toBeNull();
  expect(res.headers.get("content-disposition")).toBe('attachment; filename="iw-autunno-vincitori.csv"');
  expect(res.body).toBeNull();
});

it("204 del servizio (salvataggio della consegna, BO-14): 204 al browser senza corpo, non un finto 503", async () => {
  const req = new NextRequest("http://web.test/api/lh/gamification/v1/plays/P1/delivery", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ status: "SHIPPED" }),
  });
  const res = await POST(req, ctx("gamification", ["v1", "plays", "P1", "delivery"]));
  expect(res.status).toBe(204);
  expect(res.body).toBeNull();
  expect(res.headers.get("x-correlation-id")).toBeTruthy();
});

it("304 del servizio: 304 al browser senza corpo", async () => {
  const res = await GET(new NextRequest("http://web.test/api/lh/wallet/v1/not-modified"), ctx("wallet", ["v1", "not-modified"]));
  expect(res.status).toBe(304);
  expect(res.body).toBeNull();
});

it("vincitori CSV: il servizio riceve un Accept che ammette text/csv e il file arriva al browser (non 406)", async () => {
  const res = await GET(
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

it("errore su un percorso CSV: il problem+json del servizio arriva al browser con il suo stato", async () => {
  const res = await GET(
    new NextRequest("http://web.test/api/lh/gamification/v1/contests/NOPE/winners.csv"),
    ctx("gamification", ["v1", "contests", "NOPE", "winners.csv"]),
  );
  expect(res.status).toBe(404);
  expect(res.headers.get("content-type")).toBe("application/problem+json");
  expect((await res.json()).code).toBe("NOT_FOUND");
  // Nessuna risoluzione dei soprannomi su un errore.
  expect(seen).toHaveLength(1);
});

it("le API JSON continuano a chiedere application/json", async () => {
  await GET(new NextRequest("http://web.test/api/lh/wallet/v1/wallets/MBR-000001"), ctx("wallet", ["v1", "wallets", "MBR-000001"]));
  expect(seen[0].headers.get("accept")).toBe("application/json");
});
