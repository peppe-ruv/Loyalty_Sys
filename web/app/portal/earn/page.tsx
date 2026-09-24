"use client";

import { useLhQuery } from "@/lib/api/client";
import { useActiveMember } from "@/components/portal/MemberContext";
import Link from "next/link";
import type { PortalCampaign } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";

// PT-02 Guadagna (docs/09 §PT-02): campagne attive in forma leggibile, con riepilogo premio; collegamento a PT-11.

export default function EarnPage() {
  const memberId = useActiveMember();
  const query = useLhQuery<PortalCampaign[]>("campaign", "/v1/portal/campaigns", { memberId });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Come guadagnare</h1>
      <QueryState
        query={query}
        service="campaign"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessuna promozione attiva"
        emptyHint="Torna presto: nuove occasioni per guadagnare punti sono in arrivo."
      >
        {(d) => (
          <ul className="space-y-3">
            {d.map((c) => (
              <li key={c.code} className="rounded-xl border border-[var(--color-bo-border)] bg-white p-4">
                <p className="font-medium text-[var(--color-pt-night)]">{c.name}</p>
                {c.memberDescription ? (
                  <p className="mt-0.5 text-sm text-[var(--color-pt-night)]/70">{c.memberDescription}</p>
                ) : null}
                <div className="mt-2 flex items-center justify-between">
                  <span className="rounded-full bg-[var(--color-pt-primary)]/10 px-2 py-0.5 text-xs font-semibold text-[var(--color-pt-primary)]">
                    {c.rewardSummary}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
      <Link
        href="/portal/invite"
        className="flex items-center justify-between rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-sm font-medium text-[var(--color-pt-night)]"
      >
        Porta un amico nel Club <span aria-hidden>→</span>
      </Link>
      <p className="text-center text-xs text-[var(--color-pt-night)]/50">
        Per provare un&apos;azione usa il pannello «Demo».
      </p>
    </div>
  );
}
