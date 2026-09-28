// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchNicknames, nicknameRoute, resolveNicknames } from "./memberNicknames";

// BFF dei soprannomi (Q-368, ADR-032, F2-EVT-02): percorsi intercettati, chiamata a member-service, degradato.

describe("nicknameRoute", () => {
  it("solo GET o HEAD su gamification, sui percorsi di classifiche e vincitori", () => {
    expect(nicknameRoute("gamification", "GET", ["v1", "portal", "leaderboards"])).toBe("portal-leaderboards");
    expect(nicknameRoute("gamification", "GET", ["v1", "portal", "leaderboards", "LDB-MONTH-PTS"])).toBe("portal-leaderboards");
    expect(nicknameRoute("gamification", "GET", ["v1", "leaderboards", "LDB-MONTH-PTS", "ranking"])).toBe("bo-ranking");
    expect(nicknameRoute("gamification", "GET", ["v1", "contests", "CNT-1", "winners"])).toBe("winners");
    expect(nicknameRoute("gamification", "GET", ["v1", "contests", "CNT-1", "winners.csv"])).toBe("winners-csv");
    // HEAD chiede al servizio la stessa cosa del GET (resolve=ids); il corpo non c'è e non si riscrive.
    expect(nicknameRoute("gamification", "HEAD", ["v1", "contests", "CNT-1", "winners.csv"])).toBe("winners-csv");
    expect(nicknameRoute("gamification", "HEAD", ["v1", "leaderboards", "LDB-MONTH-PTS", "ranking"])).toBe("bo-ranking");
    expect(nicknameRoute("gamification", "POST", ["v1", "portal", "leaderboards"])).toBeNull();
    expect(nicknameRoute("wallet", "GET", ["v1", "portal", "leaderboards"])).toBeNull();
    expect(nicknameRoute("gamification", "GET", ["v1", "leaderboards"])).toBeNull();
    expect(nicknameRoute("gamification", "GET", ["v1", "contests", "CNT-1", "stats"])).toBeNull();
    expect(nicknameRoute("gamification", "GET", ["v1", "portal", "contests"])).toBeNull();
  });
});

describe("fetchNicknames", () => {
  let bodies: { memberIds: string[] }[];
  let respond: () => Response;

  beforeEach(() => {
    bodies = [];
    process.env.LH_SVC_MEMBER_URL = "http://member.test";
    respond = () => new Response("{}", { status: 200 });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init: RequestInit) => {
        expect(url).toBe("http://member.test/v1/members/nicknames");
        expect(init.method).toBe("POST");
        const body = JSON.parse(String(init.body)) as { memberIds: string[] };
        bodies.push(body);
        const r = respond();
        if (r.status !== 200 || (await r.clone().text()) !== "{}") return r;
        return new Response(JSON.stringify({ items: body.memberIds.map((id) => ({ memberId: id, nickname: `nick-${id}` })) }), { status: 200 });
      }),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    delete process.env.LH_SVC_MEMBER_URL;
  });

  it("lotti da 200 id distinti, mappa completa", async () => {
    const ids = Array.from({ length: 250 }, (_, i) => `MBR-${String(i).padStart(6, "0")}`);
    const out = await fetchNicknames([...ids, ids[0]], { "x-lh-actor": "ANALYST:anonymous" });
    expect(bodies.map((b) => b.memberIds.length)).toEqual([200, 50]);
    expect(out?.size).toBe(250);
    expect(out?.get("MBR-000000")).toBe("nick-MBR-000000");
  });

  it("nessun id → nessuna chiamata", async () => {
    expect((await fetchNicknames([], {}))?.size).toBe(0);
    expect(bodies).toHaveLength(0);
  });

  it("member-service in errore, irraggiungibile o con corpo inatteso → null (degradato)", async () => {
    respond = () => new Response("{}", { status: 503 });
    expect(await fetchNicknames(["MBR-000001"], {})).toBeNull();
    respond = () => new Response(JSON.stringify({ nope: true }), { status: 200 });
    expect(await fetchNicknames(["MBR-000001"], {})).toBeNull();
    respond = () => {
      throw new TypeError("fetch failed");
    };
    expect(await fetchNicknames(["MBR-000001"], {})).toBeNull();
  });
});

describe("resolveNicknames", () => {
  const upstream = JSON.stringify({
    code: "LDB-MONTH-PTS",
    name: "Mese",
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
  });

  it("portale: soprannomi inseriti, nessun id altrui", async () => {
    const lookup = vi.fn(async (ids: string[]) => new Map(ids.map((id) => [id, id === "MBR-000005" ? "fra_r" : "marco_b"])));
    const out = await resolveNicknames("portal-leaderboards", upstream, "MBR-000002", lookup);
    expect(lookup).toHaveBeenCalledWith(["MBR-000005", "MBR-000002"]);
    expect(out?.degraded).toBe(false);
    expect(out?.body).not.toContain("MBR-");
    expect(JSON.parse(out!.body).top).toEqual([
      { rank: 1, nickname: "fra_r", score: 1840, isMe: false },
      { rank: 2, nickname: "marco_b", score: 900, isMe: true },
    ]);
  });

  it("portale, elenco di classifiche e member-service giù → segnaposto e degradato", async () => {
    const out = await resolveNicknames("portal-leaderboards", `[${upstream}]`, "MBR-000002", async () => null);
    expect(out?.degraded).toBe(true);
    const list = JSON.parse(out!.body);
    expect(list[0].top.map((e: { nickname: string }) => e.nickname)).toEqual(["Giocatore 1", "Giocatore 2"]);
    expect(out?.body).not.toContain("MBR-");
  });

  it("corpo inatteso → null (il proxy non inoltra)", async () => {
    expect(await resolveNicknames("portal-leaderboards", "non json", "MBR-000002", async () => null)).toBeNull();
    expect(await resolveNicknames("portal-leaderboards", '{"top":null}', "MBR-000002", async () => null)).toBeNull();
    expect(await resolveNicknames("winners", "{}", null, async () => null)).toBeNull();
    expect(await resolveNicknames("bo-ranking", "[]", null, async () => null)).toBeNull();
  });

  it("vincitori e CSV", async () => {
    const lookup = async () => new Map([["MBR-000010", "matt_r"]]);
    const winners = await resolveNicknames("winners", JSON.stringify([{ playId: "P1", memberId: "MBR-000010", prizeCode: "PTS-50" }]), null, lookup);
    expect(JSON.parse(winners!.body)[0]).toMatchObject({ memberId: "MBR-000010", nickname: "matt_r" });
    const csv = await resolveNicknames("winners-csv", "playId,memberId,nickname\nP1,MBR-000010,\n", null, lookup);
    expect(csv?.body).toBe("playId,memberId,nickname\nP1,MBR-000010,matt_r\n");
  });
});
