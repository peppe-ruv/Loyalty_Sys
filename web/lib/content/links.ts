import type { ContentDisplay, ContentPlacement, ExclusionReason } from "./types";

// Destinazione della CTA di un contenuto (docs/09 PT-01 "Note"): concorso, premio, campagna → PT-02 con ancora,
// pagina del portale, URL. Il backend conserva il riferimento, il portale conosce i propri percorsi.
export function contentHref(c: Pick<ContentDisplay, "linkType" | "linkCode" | "ctaTarget">): string | null {
  switch (c.linkType) {
    case "CONTEST":
      return c.linkCode ? `/portal/play/${encodeURIComponent(c.linkCode)}` : null;
    case "CAMPAIGN":
      return c.linkCode ? `/portal/earn#${encodeURIComponent(c.linkCode)}` : "/portal/earn";
    case "REWARD":
      return c.linkCode ? `/portal/rewards/${encodeURIComponent(c.linkCode)}` : "/portal/rewards";
    case "PRIZE":
      return null; // card vincita: nessuna destinazione propria (M6.3)
    default:
      return safeTarget(c.ctaTarget);
  }
}

/** Solo percorsi del portale o URL https (niente javascript:, niente altri schemi). */
export function safeTarget(target: string | null): string | null {
  if (!target) return null;
  const t = target.trim();
  if (t.startsWith("/portal")) return t;
  if (/^https:\/\/[^\s]+$/i.test(t)) return t;
  return null;
}

export function isExternal(href: string): boolean {
  return /^https?:\/\//i.test(href);
}

export const PLACEMENT_LABEL: Record<ContentPlacement, string> = {
  HOME_HERO: "Home · in evidenza",
  HOME_GRID: "Home · griglia",
  CATALOG_TOP: "Catalogo premi · in alto",
  CONTEST: "Pagina concorsi",
  WIN: "Card vincita",
};

/** Quanti contenuti mostra ogni posizionamento (docs/03 §9): HERO 1, GRID fino a 6. SPEC-GAP: Q-72 per gli altri. */
export const PLACEMENT_LIMIT: Record<ContentPlacement, number> = {
  HOME_HERO: 1,
  HOME_GRID: 6,
  CATALOG_TOP: 1,
  CONTEST: 3,
  WIN: 1,
};

export const EXCLUSION_LABEL: Record<ExclusionReason, string> = {
  NOT_IN_AUDIENCE: "fuori dal pubblico",
  OUT_OF_SCHEDULE: "fuori calendario",
  NOT_LIVE: "non pubblicato",
  FREQUENCY: "già visto (frequenza)",
};
