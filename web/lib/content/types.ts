// Contenuti del CMS del programma (docs/servizi/engagement-service.md §2-3; F-CNT-01/02/03/04; BO-18, PT-01/03/05).
// Tipi separati da lib/api/types.ts: sono condivisi tra portale e anteprima del backoffice.

export type ContentKind = "CARD" | "POPUP" | "BANNER";
export type ContentPlacement = "HOME_HERO" | "HOME_GRID" | "CATALOG_TOP" | "CONTEST" | "WIN";
export type ContentLinkType = "NONE" | "CONTEST" | "CAMPAIGN" | "REWARD" | "PRIZE";
export type ContentTone = "PRIMARY" | "SECONDARY" | "COIN" | "NIGHT";
export type ContentFrequency = "ONCE" | "ONCE_PER_DAY" | "ALWAYS";
export type ContentStatus = "DRAFT" | "LIVE" | "PAUSED" | "ENDED" | "ARCHIVED";
export type ExclusionReason = "NOT_IN_AUDIENCE" | "OUT_OF_SCHEDULE" | "NOT_LIVE" | "FREQUENCY";

/** Ciò che serve a disegnare un contenuto: identico nel portale e nell'anteprima di BO-18. */
export interface ContentDisplay {
  code: string;
  kind: ContentKind;
  placement: ContentPlacement | null;
  title: string;
  body: string | null;
  imageUrl: string | null;
  ctaLabel: string | null;
  ctaTarget: string | null;
  linkType: ContentLinkType;
  linkCode: string | null;
  style: { tone?: ContentTone; layout?: string } | null;
}

export interface ContentAudience {
  tiers: string[];
  segments: string[];
  statuses: string[];
}

/** Contenuto completo della gestione ({@code GET /v1/contents}). */
export interface ContentItem extends ContentDisplay {
  id: string;
  audience: ContentAudience;
  startAt: string | null;
  endAt: string | null;
  priority: number;
  frequency: ContentFrequency | null;
  dismissible: boolean;
  status: ContentStatus;
  version: number;
  updatedAt: string | null;
}

/** {@code GET /v1/portal/popups/next}: il pop-up da mostrare, con id per registrare la vista. */
export interface PopupView extends ContentDisplay {
  id: string;
  frequency: ContentFrequency;
  dismissible: boolean;
}

/** Posizionamenti dell'anteprima per membro: quelli dei contenuti più i pop-up. */
export type PreviewPlacement = ContentPlacement | "POPUP";

/** {@code GET /v1/contents/preview}: cosa vede un membro adesso in un posizionamento, e perché gli altri no. */
export interface ContentPreview {
  memberId: string;
  placement: PreviewPlacement;
  shown: ContentDisplay[];
  excluded: { code: string; title: string; reason: ExclusionReason }[];
}
