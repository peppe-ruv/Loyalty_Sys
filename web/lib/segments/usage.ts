import type { RefreshResult } from "./types";

// "Usato da" di BO-04 (docs/08 §BO-04) e testi di esito: logica pura, testata a parte. I segmenti li possiede member;
// chi li usa lo sanno campaign (pubblico), reward (visibilità) ed engagement (pubblico dei contenuti).

export interface CampaignLike {
  code: string;
  name?: string;
  audience?: { segments?: string[] } | null;
}
export interface RewardLike {
  code: string;
  name?: string;
  eligibleSegments?: string[] | null;
}
export interface ContentLike {
  code: string;
  title?: string;
  audience?: { segments?: string[] } | null;
}

export interface SegmentUsage {
  campaigns: string[];
  rewards: string[];
  contents: string[];
}

export function segmentUsage(
  code: string,
  sources: { campaigns?: CampaignLike[]; rewards?: RewardLike[]; contents?: ContentLike[] },
): SegmentUsage {
  return {
    campaigns: (sources.campaigns ?? []).filter((c) => c.audience?.segments?.includes(code)).map((c) => c.code),
    rewards: (sources.rewards ?? []).filter((r) => r.eligibleSegments?.includes(code)).map((r) => r.code),
    contents: (sources.contents ?? []).filter((c) => c.audience?.segments?.includes(code)).map((c) => c.code),
  };
}

export function usageCount(u: SegmentUsage): number {
  return u.campaigns.length + u.rewards.length + u.contents.length;
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** "2 campagne · 1 premio · 1 contenuto" oppure "nessuno". */
export function usageLabel(u: SegmentUsage): string {
  const parts: string[] = [];
  if (u.campaigns.length) parts.push(plural(u.campaigns.length, "campagna", "campagne"));
  if (u.rewards.length) parts.push(plural(u.rewards.length, "premio", "premi"));
  if (u.contents.length) parts.push(plural(u.contents.length, "contenuto", "contenuti"));
  return parts.length ? parts.join(" · ") : "nessuno";
}

/** Esito di "Ricalcola ora" (docs/08 §BO-04: `{entered, left, total}` in toast). */
export function refreshMessage(r: RefreshResult): string {
  if (r.entered === 0 && r.left === 0) return `${r.code}: nessuna variazione · ${plural(r.total, "membro", "membri")}`;
  return `${r.code}: ${plural(r.entered, "entrato", "entrati")}, ${plural(r.left, "uscito", "usciti")} · ${plural(r.total, "membro", "membri")}`;
}

/** Elenco manuale di un segmento statico: id separati da virgole, spazi o a capo; accetta anche le sole cifre. */
export function parseMemberIds(text: string): { ids: string[]; invalid: string[] } {
  const ids: string[] = [];
  const invalid: string[] = [];
  for (const raw of text.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean)) {
    const up = raw.toUpperCase();
    const id = /^\d{1,6}$/.test(up) ? `MBR-${up.padStart(6, "0")}` : up;
    if (/^MBR-\d{6}$/.test(id)) {
      if (!ids.includes(id)) ids.push(id);
    } else {
      invalid.push(raw);
    }
  }
  return { ids, invalid };
}
