"use client";

import Link from "next/link";
import type { ContentDisplay } from "@/lib/content/types";
import { contentHref, isExternal, safeTarget } from "@/lib/content/links";
import { toneOf } from "./ContentCard";

// Card vincita (docs/09 PT-06, F-CNT-03): quella configurata in BO-18 per il premio vinto (`placement=WIN`), oppure
// il riquadro generico. Condivisa con l'anteprima di BO-18. Sotto il titolo, `children` porta lo stato della consegna
// (punti in arrivo, codice del coupon, contatto per il premio fisico).
const TONE = {
  PRIMARY: { bg: "var(--color-pt-primary)", ink: "#ffffff" },
  SECONDARY: { bg: "var(--color-pt-secondary)", ink: "#ffffff" },
  COIN: { bg: "var(--color-pt-coin)", ink: "var(--color-pt-night)" },
  NIGHT: { bg: "var(--color-pt-night)", ink: "#ffffff" },
} as const;

export function WinCard({
  content,
  prizeLabel,
  followUp,
  preview = false,
  children,
}: {
  content: ContentDisplay | null;
  prizeLabel: string;
  followUp: string;
  preview?: boolean;
  children?: React.ReactNode;
}) {
  const tone = TONE[content ? toneOf(content) : "PRIMARY"];
  // Le card WIN hanno il premio come collegamento: la destinazione del pulsante è la pagina indicata in ctaTarget.
  const href = content ? (content.linkType === "PRIZE" ? safeTarget(content.ctaTarget) : contentHref(content)) : null;
  return (
    <div className="rounded-2xl p-5 text-center shadow-md" style={{ background: tone.bg, color: tone.ink }} data-content={content?.code}>
      {content?.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- immagini da public/demo o URL del contenuto
        <img src={content.imageUrl} alt="" className="mx-auto mb-3 h-24 w-full rounded-xl object-cover" />
      ) : null}
      <p className="text-sm opacity-80">Hai vinto!</p>
      <p className="text-2xl font-bold leading-tight">{content?.title ?? prizeLabel}</p>
      {content ? <p className="mt-0.5 text-sm font-medium opacity-80">{prizeLabel}</p> : null}
      <p className="mt-1 text-sm opacity-90">{content?.body ?? followUp}</p>
      {children}
      {content?.ctaLabel && href ? (
        preview ? (
          <span className="mt-3 inline-block rounded-full bg-white/20 px-4 py-1.5 text-xs font-semibold">{content.ctaLabel} →</span>
        ) : isExternal(href) ? (
          <a href={href} target="_blank" rel="noopener noreferrer" className="mt-3 inline-block rounded-full bg-white/20 px-4 py-1.5 text-xs font-semibold">
            {content.ctaLabel} →
          </a>
        ) : (
          <Link href={href} className="mt-3 inline-block rounded-full bg-white/20 px-4 py-1.5 text-xs font-semibold">
            {content.ctaLabel} →
          </Link>
        )
      ) : null}
    </div>
  );
}
