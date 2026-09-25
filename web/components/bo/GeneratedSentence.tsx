"use client";

import { useLhQuery } from "@/lib/api/client";
import type { Contest } from "@/lib/api/types";
import { describeCampaign, type CampaignDraft } from "@/lib/campaign/describe";

// Rende la frase generata da describeCampaign() convertendo i marcatori **…** in <strong> (docs/08 §BO-06). Con un
// effetto GRANT_PLAYS legge i concorsi (gamification) per nominare il concorso ("1 giocata a Ruota d'Autunno"); se il
// servizio dorme resta il codice.
// SPEC-GAP: Q-208 — le fonti ammesse ("da ecommerce o app") entrano nella frase solo se la bozza le porta: campaign
// oggi non modella le fonti ammesse della campagna.
export function GeneratedSentence({ draft }: { draft: CampaignDraft }) {
  const needsContests = (draft.effects ?? []).some((e) => e.type === "GRANT_PLAYS" && e.contestCode);
  const contests = useLhQuery<Contest[]>("gamification", "/v1/contests", undefined, { enabled: needsContests });
  const contestNames =
    draft.contestNames ?? Object.fromEntries((contests.data ?? []).map((c) => [c.code, c.name] as const));
  return <GeneratedText text={describeCampaign({ ...draft, contestNames })} />;
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
