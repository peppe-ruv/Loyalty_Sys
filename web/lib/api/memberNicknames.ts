import { serviceBaseUrl } from "@/lib/api/services";
import {
  chunk,
  distinctIds,
  mergePortalLeaderboard,
  mergeRanking,
  mergeWinners,
  mergeWinnersCsv,
  portalLeaderboardIds,
  winnersCsvIds,
  type NicknameMap,
  type PortalLeaderboardWithIds,
} from "@/lib/gamification/nicknames";
import type { ContestWinner, LeaderboardRanking } from "@/lib/api/types";

// BFF dei soprannomi (Q-368, ADR-032, F2-EVT-02). SOLO LATO SERVER: lo usa il proxy /api/lh (route handler).
// Gamification risponde con `resolve=ids` (memberId, nessun soprannome); qui si chiedono i soprannomi a member-service
// (`POST /v1/members/nicknames`, lotti da 200) e si inseriscono prima di rispondere al browser. Se member-service non
// risponde la pagina non fallisce: soprannome `Giocatore <rank>` nel portale, vuoto nel backoffice (degradato).

const NICKNAMES_TIMEOUT_MS = 5_000;

/** Percorsi di gamification che il BFF completa coi soprannomi. */
export type NicknameRoute = "portal-leaderboards" | "bo-ranking" | "winners" | "winners-csv";

/** Il percorso (solo GET su gamification) da completare coi soprannomi, oppure `null` per il proxy normale. */
export function nicknameRoute(service: string, method: string, path: readonly string[]): NicknameRoute | null {
  if (service !== "gamification" || method !== "GET" || path[0] !== "v1") return null;
  if (path[1] === "portal" && path[2] === "leaderboards" && (path.length === 3 || path.length === 4)) return "portal-leaderboards";
  if (path.length !== 4) return null;
  if (path[1] === "leaderboards" && path[3] === "ranking") return "bo-ranking";
  if (path[1] === "contests" && path[3] === "winners") return "winners";
  if (path[1] === "contests" && path[3] === "winners.csv") return "winners-csv";
  return null;
}

/**
 * Soprannomi da member-service per gli id dati, a lotti da 200. `null` se member-service non risponde, risponde con
 * errore o con un corpo inatteso (il chiamante degrada); mappa vuota se non c'è nulla da chiedere.
 */
export async function fetchNicknames(ids: readonly string[], headers: Record<string, string>): Promise<Map<string, string | null> | null> {
  const wanted = distinctIds(ids);
  const out = new Map<string, string | null>();
  if (wanted.length === 0) return out;
  for (const batch of chunk(wanted)) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), NICKNAMES_TIMEOUT_MS);
    try {
      const res = await fetch(`${serviceBaseUrl("member")}/v1/members/nicknames`, {
        method: "POST",
        headers: { ...headers, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ memberIds: batch }),
        cache: "no-store",
        signal: controller.signal,
      });
      if (!res.ok) return null;
      const body: unknown = await res.json();
      const items = (body as { items?: unknown } | null)?.items;
      if (!Array.isArray(items)) return null;
      for (const item of items as { memberId?: unknown; nickname?: unknown }[]) {
        if (typeof item?.memberId === "string") out.set(item.memberId, typeof item.nickname === "string" ? item.nickname : null);
      }
    } catch {
      return null;
    } finally {
      clearTimeout(timeout);
    }
  }
  return out;
}

export interface ResolvedBody {
  body: string;
  /** `true` se member-service non ha risposto e si sono usati i segnaposto. */
  degraded: boolean;
}

/**
 * Corpo per il browser: la risposta `resolve=ids` di gamification coi soprannomi inseriti. `null` se il corpo di
 * gamification non ha la forma attesa (il proxy risponde 502 invece di inoltrare dati che potrebbero contenere id).
 */
export async function resolveNicknames(
  route: NicknameRoute,
  upstreamBody: string,
  requesterId: string | null,
  lookup: (ids: string[]) => Promise<NicknameMap | null>,
): Promise<ResolvedBody | null> {
  if (route === "winners-csv") {
    const names = await lookup(winnersCsvIds(upstreamBody));
    return { body: mergeWinnersCsv(upstreamBody, names), degraded: names == null };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(upstreamBody);
  } catch {
    return null;
  }
  if (route === "portal-leaderboards") {
    const single = !Array.isArray(parsed);
    const boards = (single ? [parsed] : parsed) as PortalLeaderboardWithIds[];
    if (!boards.every((b) => b != null && typeof b === "object" && Array.isArray(b.top))) return null;
    const names = await lookup(portalLeaderboardIds(boards));
    const merged = boards.map((b) => mergePortalLeaderboard(b, requesterId, names));
    return { body: JSON.stringify(single ? merged[0] : merged), degraded: names == null };
  }
  if (route === "bo-ranking") {
    const ranking = parsed as LeaderboardRanking;
    if (ranking == null || typeof ranking !== "object" || !Array.isArray(ranking.items)) return null;
    const names = await lookup(distinctIds(ranking.items.map((i) => i.memberId)));
    return { body: JSON.stringify(mergeRanking(ranking, names)), degraded: names == null };
  }
  if (!Array.isArray(parsed)) return null;
  const winners = parsed as ContestWinner[];
  const names = await lookup(distinctIds(winners.map((w) => w?.memberId)));
  return { body: JSON.stringify(mergeWinners(winners, names)), degraded: names == null };
}
