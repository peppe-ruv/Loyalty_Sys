import { describe, expect, it } from "vitest";
import type { ContestWinner, LeaderboardRanking } from "@/lib/api/types";
import {
  chunk,
  csvCell,
  distinctIds,
  fallbackNickname,
  mergePortalLeaderboard,
  mergeRanking,
  mergeWinners,
  mergeWinnersCsv,
  parseCsv,
  portalLeaderboardIds,
  winnersCsvIds,
  type PortalLeaderboardWithIds,
} from "./nicknames";

// Soprannomi risolti dal BFF (Q-368, ADR-032, F2-EVT-02; docs/09 PT-10).

const board = (): PortalLeaderboardWithIds => ({
  code: "LDB-MONTH-PTS",
  name: "Classifica del mese",
  metric: "PTS_EARNED",
  period: "MONTH",
  periodKey: "2026-09",
  topN: 10,
  top: [
    { rank: 1, memberId: "MBR-000005", score: 1840, isMe: false },
    { rank: 2, memberId: "MBR-000003", score: 1200, isMe: false },
    { rank: 3, memberId: "MBR-000002", score: 900, isMe: true },
    { rank: 4, memberId: "MBR-000011", score: 500, isMe: false },
  ],
  me: { rank: 3, score: 900 },
  participants: 9,
});

const names = new Map<string, string | null>([
  ["MBR-000005", "fra_r"],
  ["MBR-000003", "giu_f"],
  ["MBR-000002", "marco_b"],
  ["MBR-000011", null],
]);

describe("mergePortalLeaderboard", () => {
  it("restituisce solo {rank, nickname, score, isMe}: nessun memberId altrui nell'uscita", () => {
    const out = mergePortalLeaderboard(board(), "MBR-000002", names);
    const json = JSON.stringify(out);
    for (const id of ["MBR-000005", "MBR-000003", "MBR-000011"]) expect(json).not.toContain(id);
    expect(json).not.toContain("memberId");
    for (const e of out.top) expect(Object.keys(e).sort()).toEqual(["isMe", "nickname", "rank", "score"]);
    expect(Object.keys(out.me ?? {}).sort()).toEqual(["rank", "score"]);
    expect(out.top.map((e) => e.nickname)).toEqual(["fra_r", "giu_f", "marco_b", "Giocatore 4"]);
    expect(out.participants).toBe(9);
    expect(out.periodKey).toBe("2026-09");
  });

  it("isMe calcolato lato server dal membro che chiede, non dal campo di gamification", () => {
    const b = board();
    b.top[0].isMe = true; // valore a monte ignorato
    const out = mergePortalLeaderboard(b, "MBR-000002", names);
    expect(out.top.map((e) => e.isMe)).toEqual([false, false, true, false]);
    expect(mergePortalLeaderboard(board(), null, names).top.every((e) => !e.isMe)).toBe(true);
    expect(mergePortalLeaderboard(board(), "", names).top.every((e) => !e.isMe)).toBe(true);
  });

  it("member-service non disponibile → «Giocatore <rank>», la classifica resta", () => {
    const out = mergePortalLeaderboard(board(), "MBR-000002", null);
    expect(out.top.map((e) => e.nickname)).toEqual(["Giocatore 1", "Giocatore 2", "Giocatore 3", "Giocatore 4"]);
    expect(out.top[2].isMe).toBe(true);
    expect(JSON.stringify(out)).not.toContain("MBR-");
  });

  it("campi sconosciuti di gamification non passano al browser", () => {
    const b = { ...board(), secret: "x", top: board().top.map((e) => ({ ...e, email: "a@b.c" })) } as PortalLeaderboardWithIds;
    const json = JSON.stringify(mergePortalLeaderboard(b, "MBR-000002", names));
    expect(json).not.toContain("secret");
    expect(json).not.toContain("a@b.c");
  });

  it("classifica vuota e me assente", () => {
    const out = mergePortalLeaderboard({ ...board(), top: [], me: null, participants: 0 }, "MBR-000002", names);
    expect(out.top).toEqual([]);
    expect(out.me).toBeNull();
  });

  it("fallbackNickname", () => {
    expect(fallbackNickname(7)).toBe("Giocatore 7");
  });
});

describe("id da risolvere", () => {
  it("distinti, senza vuoti, nell'ordine", () => {
    expect(distinctIds(["MBR-2", null, " ", "MBR-1", "MBR-2", undefined])).toEqual(["MBR-2", "MBR-1"]);
    expect(portalLeaderboardIds([board(), { ...board(), top: [{ rank: 1, memberId: "MBR-000099", score: 1 }] }])).toEqual([
      "MBR-000005",
      "MBR-000003",
      "MBR-000002",
      "MBR-000011",
      "MBR-000099",
    ]);
  });

  it("lotti da 200", () => {
    const ids = Array.from({ length: 401 }, (_, i) => `MBR-${i}`);
    expect(chunk(ids).map((c) => c.length)).toEqual([200, 200, 1]);
    expect(chunk([])).toEqual([]);
  });
});

describe("backoffice: vincitori e ranking", () => {
  const winner = (memberId: string): ContestWinner => ({
    playId: `PLAY-${memberId}`,
    memberId,
    nickname: null,
    prizeCode: "PTS-50",
    prizeName: "50 punti",
    prizeType: "POINTS",
    playedAt: "2026-09-18T13:10:00Z",
    deliveryStatus: "NA",
    deliveryNote: null,
  });

  it("vincitori: memberId resta (operatori), soprannome da member-service o null", () => {
    const out = mergeWinners([winner("MBR-000005"), winner("MBR-000404")], names);
    expect(out.map((w) => [w.memberId, w.nickname])).toEqual([
      ["MBR-000005", "fra_r"],
      ["MBR-000404", null],
    ]);
    expect(mergeWinners([winner("MBR-000005")], null)[0].nickname).toBeNull();
  });

  it("ranking BO-16: soprannome da member-service", () => {
    const ranking: LeaderboardRanking = {
      code: "LDB-MONTH-PTS",
      name: "x",
      metric: "PTS_EARNED",
      period: "MONTH",
      periodKey: "2026-09",
      currentPeriodKey: "2026-09",
      periods: ["2026-09"],
      topN: 10,
      items: [{ rank: 1, memberId: "MBR-000005", nickname: null, score: 1840, reachedAt: "2026-09-01T00:00:00Z" }],
    };
    expect(mergeRanking(ranking, names).items[0].nickname).toBe("fra_r");
    expect(mergeRanking(ranking, null).items[0].nickname).toBeNull();
  });
});

describe("CSV dei vincitori", () => {
  const csv =
    "playId,memberId,nickname,prizeCode,prizeName,prizeType,playedAt,deliveryStatus,deliveryNote\n" +
    'P1,MBR-000005,,PTS-50,"Buono, 50 punti",POINTS,2026-09-18T13:10:00Z,NA,\n' +
    "P2,MBR-000666,,GADGET,Borraccia,PHYSICAL,2026-09-19T10:00:00Z,PENDING,'=nota\n";

  it("riempie la colonna nickname e conserva le altre celle", () => {
    const withEvil = new Map<string, string | null>([...names, ["MBR-000666", '=HYPERLINK("x")']]);
    const out = mergeWinnersCsv(csv, withEvil);
    const rows = parseCsv(out);
    expect(rows[0][2]).toBe("nickname");
    expect(rows[1][2]).toBe("fra_r");
    expect(rows[1][4]).toBe("Buono, 50 punti");
    expect(rows[2][2]).toBe(`'=HYPERLINK("x")`); // niente formule nei fogli di calcolo
    expect(rows[2][8]).toBe("'=nota");
    expect(out.endsWith("\n")).toBe(true);
  });

  it("member-service non disponibile → colonna vuota, export comunque scaricabile", () => {
    const rows = parseCsv(mergeWinnersCsv(csv, null));
    expect(rows[1][2]).toBe("");
    expect(rows[2][1]).toBe("MBR-000666");
  });

  it("id dalla colonna memberId", () => {
    expect(winnersCsvIds(csv)).toEqual(["MBR-000005", "MBR-000666"]);
    expect(winnersCsvIds("a,b\n1,2\n")).toEqual([]);
  });

  it("csvCell come ContestController.cell", () => {
    expect(csvCell(null)).toBe("");
    expect(csvCell("abc")).toBe("abc");
    expect(csvCell("-1")).toBe("'-1");
    expect(csvCell('a"b')).toBe('"a""b"');
    expect(csvCell("a,b")).toBe('"a,b"');
  });

  it("parseCsv: virgolette e a capo tra virgolette", () => {
    expect(parseCsv('a,"b ""c""","d\ne"\r\nf,,\n')).toEqual([["a", 'b "c"', "d\ne"], ["f", "", ""]]);
  });
});
