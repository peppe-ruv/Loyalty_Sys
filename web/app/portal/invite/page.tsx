"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { PortalCampaign, PortalReferral } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/shared/QueryState";
import { actionLabel } from "@/lib/campaign/describe";
import { completedThisEdition, inviteLink, inviteeStatusLabel } from "@/lib/member/referral";
import { formatDate } from "@/lib/format/dates";
import { cn } from "@/lib/cn";
import { usePortalTheme } from "@/components/shared/ThemeContext";

// PT-11 Porta un amico (docs/09 §PT-11, F-REF-01/02): 3 passi coi valori reali delle campagne CMP-REFERRAL-*, codice
// amico grande con Copia/Condividi (Web Share API, altrimenti copia del link /portal/join?ref=), invitati con stato e
// contatore degli inviti premiati nell'edizione.
const REFERRER = "CMP-REFERRAL-REFERRER";
const REFEREE = "CMP-REFERRAL-REFEREE";

export default function InvitePage() {
  const memberId = useActiveMember();
  const referral = useLhQuery<PortalReferral>("member", `/v1/portal/members/${memberId}/referral`);
  const campaigns = useLhQuery<PortalCampaign[]>("campaign", "/v1/portal/campaigns", { memberId, codes: `${REFERRER},${REFEREE}` });
  const forReferrer = campaigns.data?.find((c) => c.code === REFERRER);
  const forReferee = campaigns.data?.find((c) => c.code === REFEREE);

  return (
    <div className="space-y-4">
      <div>
        <Link href="/portal/profile" className="text-xs text-[var(--color-pt-night)]/60">← Io</Link>
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Porta un amico</h1>
      </div>

      <QueryState query={referral} service="member">
        {(r) => (
          <div className="space-y-4">
            <ol className="space-y-2">
              <Step n={1} title="Invita" text="Condividi il tuo codice amico o il link di iscrizione." />
              <Step n={2} title="L'amico si iscrive" text="Inserisce il tuo codice quando si registra al Club." />
              <Step
                n={3}
                title={`Al suo primo ${actionLabel(r.qualifyingActionType).toLowerCase()}, premio per entrambi`}
                text={
                  forReferrer || forReferee
                    ? [forReferrer ? `tu ricevi ${forReferrer.rewardSummary}` : null, forReferee ? `l'amico ${forReferee.rewardSummary}` : null]
                        .filter(Boolean)
                        .join(", ")
                    : "Il premio è definito dalle promozioni attive."
                }
              />
            </ol>

            <CodeCard code={r.code} shareUrl={r.shareUrl} />

            {forReferrer?.memberLimit?.period === "EDITION" ? (
              <p className="rounded-xl bg-[var(--color-pt-primary)]/10 px-3 py-2 text-sm text-[var(--color-pt-night)]">
                <strong>{Math.min(completedThisEdition(r.invited), forReferrer.memberLimit.max)}</strong> di {forReferrer.memberLimit.max} inviti premiati in questa edizione
              </p>
            ) : null}

            <section>
              <h2 className="mb-2 text-sm font-semibold text-[var(--color-pt-night)]">I tuoi invitati</h2>
              {r.invited.length === 0 ? (
                <p className="rounded-xl border border-dashed border-[var(--color-bo-border)] bg-white p-4 text-center text-sm text-[var(--color-pt-night)]/60">
                  Nessun amico ancora: il primo invito è a un messaggio di distanza.
                </p>
              ) : (
                <ul className="divide-y divide-slate-100 rounded-xl border border-[var(--color-bo-border)] bg-white">
                  {r.invited.map((i, idx) => (
                    <li key={`${i.nickname}-${idx}`} className="flex items-center justify-between px-3 py-2.5 text-sm">
                      <div>
                        <p className="font-medium text-[var(--color-pt-night)]">{i.nickname}</p>
                        <p className="text-xs text-[var(--color-pt-night)]/60">
                          iscritto il {formatDate(i.registeredAt)}
                          {i.completedAt ? ` · premiato il ${formatDate(i.completedAt)}` : ""}
                        </p>
                      </div>
                      <span
                        className={cn(
                          "rounded-full px-2 py-0.5 text-xs font-medium",
                          i.status === "COMPLETED" ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-700",
                        )}
                      >
                        {inviteeStatusLabel(i.status)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        )}
      </QueryState>
    </div>
  );
}

function Step({ n, title, text }: { n: number; title: string; text: string }) {
  return (
    <li className="flex gap-3 rounded-xl border border-[var(--color-bo-border)] bg-white p-3">
      <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-[var(--color-pt-primary)] text-sm font-semibold text-white">{n}</span>
      <div>
        <p className="text-sm font-medium text-[var(--color-pt-night)]">{title}</p>
        <p className="text-xs text-[var(--color-pt-night)]/70">{text}</p>
      </div>
    </li>
  );
}

function CodeCard({ code, shareUrl }: { code: string; shareUrl: string }) {
  const { programName } = usePortalTheme();
  const [done, setDone] = useState<string | null>(null);
  const link = () => inviteLink(window.location.origin, shareUrl);

  async function copy(text: string, message: string) {
    try {
      await navigator.clipboard.writeText(text);
      setDone(message);
    } catch {
      setDone("Copia non riuscita: seleziona il codice a mano");
    }
  }

  async function share() {
    const url = link();
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: programName, text: `Iscriviti a ${programName} col mio codice amico ${code}`, url });
        return;
      } catch {
        // condivisione annullata o non disponibile: si ripiega sulla copia del link
      }
    }
    await copy(url, "Link di invito copiato");
  }

  return (
    <div className="rounded-2xl bg-[var(--color-pt-night)] p-4 text-center text-white">
      <p className="text-xs uppercase tracking-wide text-white/60">Il tuo codice amico</p>
      <p className="my-2 select-all font-mono text-3xl font-semibold tracking-[0.3em]">{code}</p>
      <div className="flex justify-center gap-2">
        <button onClick={() => copy(code, "Codice copiato")} className="rounded-full border border-white/40 px-4 py-1.5 text-sm">
          Copia
        </button>
        <button onClick={share} className="rounded-full bg-white px-4 py-1.5 text-sm font-medium text-[var(--color-pt-night)]">
          Condividi
        </button>
      </div>
      <p className="mt-2 min-h-4 text-xs text-white/70" aria-live="polite">{done}</p>
    </div>
  );
}
