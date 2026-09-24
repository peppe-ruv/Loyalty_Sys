"use client";

import { useLhQuery } from "@/lib/api/client";
import type { ContentDisplay, ContentPlacement } from "@/lib/content/types";
import { ContentCard, type ContentVariant } from "@/components/shared/content/ContentCard";
import { useActiveMember } from "./MemberContext";

// Contenuti di un posizionamento (docs/09 PT-01/PT-03/PT-05, docs/03 §9): li sceglie engagement (pubblico, calendario,
// priorità). I contenuti non sono essenziali: se il servizio dorme o risponde male lo slot sparisce e la pagina resta
// usabile (stato degraded "silenzioso", docs/07 §6); durante il caricamento mostra lo scheletro della forma finale.
const VARIANT: Record<ContentPlacement, ContentVariant> = {
  HOME_HERO: "hero",
  HOME_GRID: "grid",
  CATALOG_TOP: "banner",
  CONTEST: "inline",
  WIN: "inline",
};

export function ContentSlot({ placement }: { placement: Exclude<ContentPlacement, "WIN"> }) {
  const memberId = useActiveMember();
  const query = useLhQuery<ContentDisplay[]>("engagement", "/v1/portal/content", { memberId, placement });
  const variant = VARIANT[placement];

  if (query.isLoading) {
    return <div className={variant === "grid" ? "grid grid-cols-2 gap-2" : ""} aria-hidden>
      {Array.from({ length: variant === "grid" ? 2 : 1 }).map((_, i) => (
        <div key={i} className={`animate-pulse rounded-2xl bg-slate-200/70 ${variant === "hero" ? "h-36" : variant === "banner" ? "h-11" : "h-40"}`} />
      ))}
    </div>;
  }
  const items = query.data ?? [];
  if (query.isError || items.length === 0) return null;

  if (variant === "grid") {
    return (
      <section aria-label="Per te" className="grid grid-cols-2 gap-2">
        {items.map((c) => <ContentCard key={c.code} content={c} variant="grid" />)}
      </section>
    );
  }
  return (
    <div className="space-y-2">
      {items.map((c) => <ContentCard key={c.code} content={c} variant={variant} />)}
    </div>
  );
}
