"use client";

import Link from "next/link";
import { Gift, Megaphone, Sparkles, Trophy } from "lucide-react";
import type { ContentDisplay, ContentTone } from "@/lib/content/types";
import { contentHref, isExternal } from "@/lib/content/links";
import { cn } from "@/lib/cn";

// Contenuto del CMS come lo vede il membro (docs/09 §1: componenti condivisi col backoffice, così l'anteprima di
// BO-18 è fedele). Varianti: "hero" (HOME_HERO), "grid" (HOME_GRID), "banner" (CATALOG_TOP), "inline" (CONTEST).
// In anteprima (`preview`) la CTA non naviga.
export type ContentVariant = "hero" | "grid" | "banner" | "inline";

const TONE: Record<ContentTone, { bg: string; ink: string; soft: string }> = {
  PRIMARY: { bg: "var(--color-pt-primary)", ink: "#ffffff", soft: "rgba(255,255,255,.18)" },
  SECONDARY: { bg: "var(--color-pt-secondary)", ink: "#ffffff", soft: "rgba(255,255,255,.18)" },
  COIN: { bg: "var(--color-pt-coin)", ink: "var(--color-pt-night)", soft: "rgba(14,27,44,.10)" },
  NIGHT: { bg: "var(--color-pt-night)", ink: "#ffffff", soft: "rgba(255,255,255,.14)" },
};

export function toneOf(c: Pick<ContentDisplay, "style">): ContentTone {
  const t = c.style?.tone;
  return t === "SECONDARY" || t === "COIN" || t === "NIGHT" ? t : "PRIMARY";
}

export function ContentCard({ content, variant, preview = false }: { content: ContentDisplay; variant: ContentVariant; preview?: boolean }) {
  const tone = TONE[toneOf(content)];
  const href = contentHref(content);
  const Icon = iconFor(content);

  const art = content.imageUrl ? (
    // eslint-disable-next-line @next/next/no-img-element -- immagini da public/demo o URL del contenuto
    <img src={content.imageUrl} alt="" className="h-full w-full object-cover" />
  ) : (
    <div className="flex h-full w-full items-center justify-center" style={{ background: tone.soft }}>
      <Icon className="size-8" aria-hidden style={{ color: tone.ink }} />
    </div>
  );

  const cta = content.ctaLabel && href ? (
    <Cta href={href} label={content.ctaLabel} preview={preview} ink={tone.ink} bg={tone.soft} />
  ) : null;

  if (variant === "banner") {
    return (
      <div className="flex items-center gap-3 rounded-2xl px-3 py-2.5" style={{ background: tone.bg, color: tone.ink }} data-content={content.code}>
        <Icon className="size-5 shrink-0" aria-hidden />
        <p className="flex-1 text-sm font-medium">{content.title}</p>
        {cta}
      </div>
    );
  }

  if (variant === "grid") {
    return (
      <article className="flex h-full flex-col overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-black/5" data-content={content.code}>
        <div className="h-20" style={{ background: tone.bg }}>{art}</div>
        <div className="flex flex-1 flex-col gap-1 p-3">
          <h3 className="text-sm font-semibold leading-snug text-[var(--color-pt-night)]">{content.title}</h3>
          {content.body ? <p className="line-clamp-3 text-xs text-[var(--color-pt-night)]/70">{content.body}</p> : null}
          {content.ctaLabel && href ? (
            <Cta href={href} label={content.ctaLabel} preview={preview} ink="var(--color-pt-primary)" className="mt-auto pt-1" plain />
          ) : null}
        </div>
      </article>
    );
  }

  // hero e inline: grande, colorato, testo sul colore del tono
  return (
    <article
      className={cn("relative overflow-hidden rounded-2xl p-4 shadow-sm", variant === "hero" ? "min-h-36" : "")}
      style={{ background: tone.bg, color: tone.ink }}
      data-content={content.code}
    >
      <div className="absolute -right-6 -top-6 size-28 rounded-full opacity-60" style={{ background: tone.soft }} aria-hidden />
      <div className="relative flex gap-3">
        <div className="min-w-0 flex-1">
          <h3 className={cn("font-semibold leading-snug", variant === "hero" ? "text-lg" : "text-base")}>{content.title}</h3>
          {content.body ? <p className="mt-1 text-sm opacity-90">{content.body}</p> : null}
          {cta ? <div className="mt-3">{cta}</div> : null}
        </div>
        {variant === "hero" ? <div className="size-20 shrink-0 overflow-hidden rounded-xl">{art}</div> : null}
      </div>
    </article>
  );
}

function Cta({
  href,
  label,
  preview,
  ink,
  bg,
  className,
  plain = false,
}: {
  href: string;
  label: string;
  preview: boolean;
  ink: string;
  bg?: string;
  className?: string;
  plain?: boolean;
}) {
  const cls = cn(
    plain ? "text-xs font-semibold" : "inline-flex items-center rounded-full px-3 py-1.5 text-xs font-semibold",
    className,
  );
  const style = plain ? { color: ink } : { color: ink, background: bg };
  if (preview) {
    return <span className={cls} style={style} title={`Destinazione: ${href}`}>{label} →</span>;
  }
  if (isExternal(href)) {
    return <a href={href} target="_blank" rel="noopener noreferrer" className={cls} style={style}>{label} →</a>;
  }
  return <Link href={href} className={cls} style={style}>{label} →</Link>;
}

function iconFor(c: ContentDisplay) {
  switch (c.linkType) {
    case "CONTEST":
      return Trophy;
    case "REWARD":
    case "PRIZE":
      return Gift;
    case "CAMPAIGN":
      return Megaphone;
    default:
      return Sparkles;
  }
}
