import type { ContestWinner, LeaderboardRanking, PortalLeaderboard } from "@/lib/api/types";

// Soprannomi risolti dal BFF (Q-368, ADR-032, F2-EVT-02; docs/servizi/gamification-service.md §3,
// docs/servizi/member-service.md §3). Da member.*:2 il soprannome non viaggia più sul bus: gamification, con
// `resolve=ids`, restituisce solo i memberId; il BFF (lato server) chiede i soprannomi a member-service
// (`POST /v1/members/nicknames`) e li inserisce qui. Funzioni pure: nessun accesso di rete.
// Regola del portale (PT-10, regola 12): al browser arrivano solo {rank, nickname, score, isMe}; mai i memberId
// degli altri membri. Le righe si ricostruiscono campo per campo (niente spread) perché un campo nuovo di
// gamification non passi al browser per sbaglio.

/** Soprannomi per memberId; `null` = member-service non disponibile (modalità degradata). */
export type NicknameMap = ReadonlyMap<string, string | null>;

/** Massimo di id per richiesta a member-service (`400 TOO_MANY_IDS` oltre). */
export const NICKNAMES_BATCH = 200;

/** Segnaposto del portale quando il soprannome manca o member-service non risponde. */
export function fallbackNickname(rank: number): string {
  return `Giocatore ${rank}`;
}

/** Voce della classifica del portale come la dà gamification con `resolve=ids`. */
export interface PortalEntryWithId {
  rank: number;
  memberId?: string | null;
  nickname?: string | null;
  score: number;
  isMe?: boolean;
}

export interface PortalLeaderboardWithIds extends Omit<PortalLeaderboard, "top"> {
  top: PortalEntryWithId[];
}

/** Id distinti, senza vuoti, nell'ordine di apparizione. */
export function distinctIds(ids: Iterable<string | null | undefined>): string[] {
  const out = new Set<string>();
  for (const id of ids) {
    if (typeof id === "string" && id.trim() !== "") out.add(id.trim());
  }
  return [...out];
}

/** Lotti da al massimo `size` id (limite dell'endpoint di member-service). */
export function chunk<T>(items: readonly T[], size = NICKNAMES_BATCH): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/** Id da risolvere nelle classifiche del portale (una o più). */
export function portalLeaderboardIds(boards: readonly PortalLeaderboardWithIds[]): string[] {
  return distinctIds(boards.flatMap((b) => (Array.isArray(b.top) ? b.top.map((e) => e.memberId) : [])));
}

function nicknameFor(names: NicknameMap | null, memberId: string | null | undefined): string | null {
  if (!names || !memberId) return null;
  const n = names.get(memberId);
  return typeof n === "string" && n.trim() !== "" ? n : null;
}

/**
 * Classifica del portale per il browser: `{rank, nickname, score, isMe}` per voce, `isMe` calcolato qui dal membro
 * che chiede (`requesterId`), soprannome da member-service o `Giocatore <rank>` se manca o se `names` è `null`.
 */
export function mergePortalLeaderboard(board: PortalLeaderboardWithIds, requesterId: string | null, names: NicknameMap | null): PortalLeaderboard {
  const top = (Array.isArray(board.top) ? board.top : []).map((e) => ({
    rank: e.rank,
    nickname: nicknameFor(names, e.memberId) ?? fallbackNickname(e.rank),
    score: e.score,
    isMe: requesterId != null && requesterId !== "" && e.memberId === requesterId,
  }));
  return {
    code: board.code,
    name: board.name,
    metric: board.metric,
    period: board.period,
    periodKey: board.periodKey,
    topN: board.topN,
    top,
    me: board.me ? { rank: board.me.rank, score: board.me.score } : null,
    participants: board.participants,
  };
}

/** Vincitori del backoffice (BO-14): il memberId resta (l'operatore lo vede), il soprannome viene da member-service. */
export function mergeWinners(winners: readonly ContestWinner[], names: NicknameMap | null): ContestWinner[] {
  return winners.map((w) => ({ ...w, nickname: nicknameFor(names, w.memberId) }));
}

/** Anteprima del ranking del backoffice (BO-16): come i vincitori, memberId più soprannome da member-service. */
export function mergeRanking(ranking: LeaderboardRanking, names: NicknameMap | null): LeaderboardRanking {
  return { ...ranking, items: ranking.items.map((i) => ({ ...i, nickname: nicknameFor(names, i.memberId) })) };
}

// ---------- CSV dei vincitori ----------

/** Righe e celle di un CSV RFC 4180 (virgolette doppie, a capo dentro le virgolette). */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        cell += c;
      }
    } else if (c === '"') {
      quoted = true;
    } else if (c === ",") {
      row.push(cell);
      cell = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += c;
    }
  }
  if (cell !== "" || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

/**
 * Cella CSV come la scrive gamification (`ContestController.cell`): un valore che inizia con `= + - @` prende un
 * apostrofo davanti (niente formule nei fogli di calcolo: i soprannomi li sceglie il membro), virgolette se serve.
 */
export function csvCell(value: string | null | undefined): string {
  if (value == null) return "";
  const safe = /^[=+\-@]/.test(value) ? `'${value}` : value;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

/** Id nella colonna `memberId` del CSV dei vincitori. */
export function winnersCsvIds(csv: string): string[] {
  const [header, ...rows] = parseCsv(csv);
  const col = header ? header.indexOf("memberId") : -1;
  if (col < 0) return [];
  return distinctIds(rows.map((r) => r[col]));
}

/** CSV dei vincitori con la colonna `nickname` riempita da member-service (vuota se manca o se `names` è `null`). */
export function mergeWinnersCsv(csv: string, names: NicknameMap | null): string {
  const [header, ...rows] = parseCsv(csv);
  if (!header) return csv;
  const idCol = header.indexOf("memberId");
  const nickCol = header.indexOf("nickname");
  if (idCol < 0 || nickCol < 0) return csv;
  const lines = [header.map(csvCell).join(",")];
  for (const r of rows) {
    if (r.length === 1 && r[0] === "") continue;
    const cells = r.map((v, i) => (i === nickCol ? csvCell(nicknameFor(names, r[idCol])) : csvCell(v)));
    lines.push(cells.join(","));
  }
  return lines.join("\n") + "\n";
}
