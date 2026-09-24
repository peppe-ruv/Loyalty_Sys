"use client";

import { describeCampaign, type CampaignDraft } from "@/lib/campaign/describe";

// Rende la frase generata da describeCampaign() convertendo i marcatori **…** in <strong> (docs/08 §BO-06).
export function GeneratedSentence({ draft }: { draft: CampaignDraft }) {
  return <GeneratedText text={describeCampaign(draft)} />;
}

/** Una frase generata qualsiasi (campagne, obiettivi): i tratti **…** in grassetto. */
export function GeneratedText({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  return (
    <p className="rounded-md bg-slate-50 p-3 text-sm leading-relaxed text-[var(--color-bo-ink)]">
      {parts.map((p, i) =>
        p.startsWith("**") && p.endsWith("**") ? (
          <strong key={i} className="font-semibold text-[var(--color-bo-accent)]">
            {p.slice(2, -2)}
          </strong>
        ) : (
          <span key={i}>{p}</span>
        ),
      )}
    </p>
  );
}
