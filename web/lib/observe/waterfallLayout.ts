// Disposizione dei marcatori della cascata (docs/08 §BO-25): nodi vicini nel tempo vanno su righe diverse, così i punti
// da 14 px non si coprono. Funzione pura, testabile senza DOM.

/** Distanza minima tra due marcatori sulla stessa riga, in % dell'asse. */
export const MIN_GAP_PCT = 4;
/** Altezza di una corsia con una sola riga e incremento per ogni riga in più, in px. */
export const LANE_BASE_PX = 28;
export const LANE_ROW_PX = 16;

export interface MarkerSlot {
  id: string;
  pct: number;
  row: number;
}

export interface LaneLayout {
  slots: MarkerSlot[];
  rows: number;
  heightPx: number;
}

/** Assegna a ogni marcatore la prima riga libera (greedy, per posizione crescente). */
export function layoutLane(items: { id: string; pct: number }[], minGapPct: number = MIN_GAP_PCT): LaneLayout {
  const sorted = [...items].sort((a, b) => a.pct - b.pct);
  const lastOnRow: number[] = [];
  const slots: MarkerSlot[] = sorted.map((it) => {
    let row = lastOnRow.findIndex((last) => it.pct - last >= minGapPct);
    if (row < 0) row = lastOnRow.length;
    lastOnRow[row] = it.pct;
    return { id: it.id, pct: it.pct, row };
  });
  const rows = Math.max(1, lastOnRow.length);
  return { slots, rows, heightPx: LANE_BASE_PX + LANE_ROW_PX * (rows - 1) };
}

/** Centro verticale di una riga, in px dal bordo alto della corsia. */
export function rowCenterPx(row: number): number {
  return LANE_BASE_PX / 2 + LANE_ROW_PX * row;
}
