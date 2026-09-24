"use client";

import Link from "next/link";
import { Gift, Sparkles, Ticket, Trophy } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import type { PortalContest } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { QueryState } from "@/components/shared/QueryState";
import { ContentSlot } from "@/components/portal/ContentSlot";
import { endsIn, freePlayLine, shortPrize } from "@/lib/gamification/play";

// PT-05 Gioca (docs/09 §PT-05): una scheda per concorso in corso con giocate disponibili in evidenza, stato della
// giocata gratuita, premi in palio (mai quantità) e *Gioca ora*. Sotto: come ottenere altre giocate.
const MECHANIC_WORD: Record<string, string> = { WHEEL: "Gira la ruota", SCRATCH: "Gratta e scopri", BOX: "Scegli un pacco" };

export default function PlayPage() {
  const memberId = useActiveMember();
  const contests = useLhQuery<PortalContest[]>("gamification", "/v1/portal/contests", { memberId });
  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Gioca</h1>
      <ContentSlot placement="CONTEST" />
      <QueryState
        query={contests}
        service="gamification"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessun concorso in corso"
        emptyHint="Tra poco ne arriva uno nuovo: intanto accumula punti con le promozioni."
      >
        {(d) => (
          <ul className="space-y-4">
            {d.map((c) => (
              <ContestCard key={c.code} contest={c} />
            ))}
          </ul>
        )}
      </QueryState>
      <Link
        href="/portal/earn"
        className="flex items-center justify-between gap-3 rounded-2xl bg-white p-4 text-sm shadow-sm ring-1 ring-black/5"
      >
        <span className="flex items-center gap-3">
          <Ticket className="size-5 text-[var(--color-pt-secondary)]" aria-hidden />
          <span>
            <span className="block font-medium text-[var(--color-pt-night)]">Come ottenere altre giocate</span>
            <span className="text-xs text-[var(--color-pt-night)]/60">Sondaggi e acquisti delle promozioni ti regalano giocate extra.</span>
          </span>
        </span>
        <span aria-hidden>→</span>
      </Link>
      <Link
        href="/portal/leaderboard"
        className="flex items-center justify-between gap-3 rounded-2xl bg-white p-4 text-sm shadow-sm ring-1 ring-black/5"
      >
        <span className="flex items-center gap-3">
          <Trophy className="size-5 text-[var(--color-pt-coin)]" aria-hidden />
          <span>
            <span className="block font-medium text-[var(--color-pt-night)]">Classifica</span>
            <span className="text-xs text-[var(--color-pt-night)]/60">Chi ha guadagnato più punti questo mese.</span>
          </span>
        </span>
        <span aria-hidden>→</span>
      </Link>
    </div>
  );
}

function ContestCard({ contest: c }: { contest: PortalContest }) {
  const free = freePlayLine(c);
  return (
    <li className="overflow-hidden rounded-3xl bg-[var(--color-pt-night)] text-white shadow-md">
      <div className="relative p-5">
        <div className="absolute -right-6 -top-6 size-32 rounded-full bg-[var(--color-pt-secondary)]/40 blur-2xl" aria-hidden />
        <p className="text-xs uppercase tracking-wider text-white/60">{MECHANIC_WORD[c.mechanic] ?? "Gioca"} · {endsIn(c.endAt)}</p>
        <h2 className="mt-1 text-xl font-semibold">{c.name}</h2>
        {c.description ? <p className="mt-1 text-sm text-white/75">{c.description}</p> : null}
        <div className="mt-4 flex items-end justify-between gap-3">
          <div>
            <p className="text-4xl font-bold tabular-nums" aria-label={`${c.playsAvailable} giocate disponibili`}>
              {c.playsAvailable}
            </p>
            <p className="text-xs text-white/70">{c.playsAvailable === 1 ? "giocata disponibile" : "giocate disponibili"}</p>
            {free ? <p className="mt-1 text-xs text-[var(--color-pt-coin)]">{free}</p> : null}
          </div>
          <Link
            href={`/portal/play/${c.code}`}
            className="rounded-full bg-[var(--color-pt-coin)] px-5 py-2.5 text-sm font-semibold text-[var(--color-pt-night)] shadow"
          >
            Gioca ora
          </Link>
        </div>
      </div>
      <div className="flex flex-wrap gap-2 bg-white/5 px-5 py-3">
        <span className="sr-only">Premi in palio:</span>
        {c.prizes.map((p) => (
          <span key={p.code} className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2.5 py-1 text-xs">
            {p.type === "PHYSICAL" ? <Gift className="size-3.5" aria-hidden /> : <Sparkles className="size-3.5" aria-hidden />}
            {shortPrize(p)}
          </span>
        ))}
      </div>
    </li>
  );
}
