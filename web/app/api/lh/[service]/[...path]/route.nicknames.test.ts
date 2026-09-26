// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET } from "./route";

// Proxy /api/lh con i soprannomi risolti dal BFF (Q-368, ADR-032, F2-EVT-02; docs/09 PT-10, docs/08 BO-14).

vi.mock("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));

let calls: string[];
let memberUp: boolean;

const board = {
  code: "LDB-MONTH-PTS",
  name: "Classifica del mese",
  metric: "PTS_EARNED",
  period: "MONTH",
  periodKey: "2026-09",
  topN: 10,
  top: [
    { rank: 1, memberId: "MBR-000005", score: 1840, isMe: false },
    { rank: 2, memberId: "MBR-000002", score: 900, isMe: true },
  ],
  me: { rank: 2, score: 900 },
  participants: 2,
};

beforeEach(() => {
  calls = [];
  memberUp = true;
  process.env.LH_SVC_GAMIFICATION_URL = "http://gam.test";
  process.env.LH_SVC_MEMBER_URL = "http://member.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      const u = String(url);
      calls.push(u);
      if (u.startsWith("http://member.test")) {
        if (!memberUp) throw new TypeError("fetch failed");
        const ids = (JSON.parse(String(init.body)) as { memberIds: string[] }).memberIds;
        const nick: Record<string, string> = { "MBR-000005": "fra_r", "MBR-000002": "marco_b", "MBR-000010": "matt_r" };
        return Response.json({ items: ids.map((id) => ({ memberId: id, nickname: nick[id] ?? null })) });
      }
      if (u.includes("/winners.csv")) {
        return new Response("playId,memberId,nickname,prizeCode\nP1,MBR-000010,,PTS-50\n", {
          status: 200,
          headers: { "content-type": "text/csv;charset=UTF-8", "content-disposition": 'attachment; filename="iw-autunno-vincitori.csv"' },
        });
      }
      if (u.includes("/v1/portal/leaderboards/NOPE")) return Response.json({ code: "NOT_FOUND" }, { status: 404 });
      return Response.json(board);
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.LH_SVC_GAMIFICATION_URL;
  delete process.env.LH_SVC_MEMBER_URL;
});

const ctx = (path: string[]) => ({ params: Promise.resolve({ service: "gamification", path }) });

it("classifica del portale: gamification con resolve=ids, soprannomi da member-service, nessun id altrui al browser", async () => {
  const res = await GET(new NextRequest("http://localhost/api/lh/gamification/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002"), ctx(["v1", "portal", "leaderboards", "LDB-MONTH-PTS"]));
  expect(res.status).toBe(200);
  expect(calls[0]).toBe("http://gam.test/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002&resolve=ids");
  expect(calls[1]).toBe("http://member.test/v1/members/nicknames");
  const text = await res.text();
  expect(text).not.toContain("MBR-000005");
  expect(text).not.toContain("memberId");
  expect(JSON.parse(text).top).toEqual([
    { rank: 1, nickname: "fra_r", score: 1840, isMe: false },
    { rank: 2, nickname: "marco_b", score: 900, isMe: true },
  ]);
  expect(res.headers.get("x-lh-degraded")).toBeNull();
});

it("member-service non disponibile → «Giocatore <rank>» invece di fallire", async () => {
  memberUp = false;
  const res = await GET(new NextRequest("http://localhost/api/lh/gamification/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002"), ctx(["v1", "portal", "leaderboards", "LDB-MONTH-PTS"]));
  expect(res.status).toBe(200);
  expect(res.headers.get("x-lh-degraded")).toBe("nicknames");
  const out = await res.json();
  expect(out.top.map((e: { nickname: string }) => e.nickname)).toEqual(["Giocatore 1", "Giocatore 2"]);
  expect(JSON.stringify(out)).not.toContain("MBR-");
});

it("il browser non ottiene gli id chiedendo resolve=ids da sé", async () => {
  const res = await GET(new NextRequest("http://localhost/api/lh/gamification/v1/portal/leaderboards?memberId=MBR-000002&resolve=ids"), ctx(["v1", "portal", "leaderboards"]));
  expect(calls[0]).toBe("http://gam.test/v1/portal/leaderboards?memberId=MBR-000002&resolve=ids");
  expect(await res.text()).not.toContain("MBR-000005");
});

it("errori di gamification inoltrati come prima", async () => {
  const res = await GET(new NextRequest("http://localhost/api/lh/gamification/v1/portal/leaderboards/NOPE?memberId=MBR-000002"), ctx(["v1", "portal", "leaderboards", "NOPE"]));
  expect(res.status).toBe(404);
  expect(calls).toHaveLength(1);
});

it("export CSV dei vincitori: soprannome inserito, nome del file conservato", async () => {
  const res = await GET(new NextRequest("http://localhost/api/lh/gamification/v1/contests/IW-AUTUNNO/winners.csv"), ctx(["v1", "contests", "IW-AUTUNNO", "winners.csv"]));
  expect(calls[0]).toBe("http://gam.test/v1/contests/IW-AUTUNNO/winners.csv?resolve=ids");
  expect(await res.text()).toBe("playId,memberId,nickname,prizeCode\nP1,MBR-000010,matt_r,PTS-50\n");
  expect(res.headers.get("content-disposition")).toBe('attachment; filename="iw-autunno-vincitori.csv"');
  expect(res.headers.get("content-type")).toContain("text/csv");
});
