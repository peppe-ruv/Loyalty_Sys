"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { WalletView, MemberView } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { usePending } from "@/components/portal/PendingContext";
import { MemberCard } from "@/components/shared/content/MemberCard";
import { PendingBanner, ActivityRow, type ActivityItem } from "@/components/portal/parts";
import { QueryState } from "@/components/bo/QueryState";
import { ContentSlot } from "@/components/portal/ContentSlot";
import { PopupHost } from "@/components/portal/PopupHost";
import { usePortalTheme } from "@/components/shared/ThemeContext";
import { formatPoints } from "@/lib/format/points";
import { formatDate } from "@/lib/format/dates";

// PT-01 Home (docs/09 §PT-01): tessera, saldo, avanzamento livello, card hero (HOME_HERO), azioni rapide, griglia di
// card (HOME_GRID), ultimi movimenti. All'ingresso il pop-up del momento (M6.2; per i nuovi iscritti POP-WELCOME). Dopo l'iscrizione
// (/portal/join → ?welcome=1), se i punti di benvenuto non sono ancora sul saldo, "in arrivo…".
export default function PortalHome() {
  const memberId = useActiveMember();
  const theme = usePortalTheme();
  const { pending, markPending } = usePending();
  const wallet = useLhQuery<WalletView>("wallet", `/v1/portal/wallets/${memberId}`, undefined, {
    refetchInterval: pending ? 5000 : undefined,
  });
  const member = useLhQuery<MemberView>("member", `/v1/members/${memberId}`);
  const activity = useLhQuery<ActivityItem[]>("wallet", `/v1/portal/wallets/${memberId}/activity`, { size: 3 }, {
    refetchInterval: pending ? 5000 : undefined,
  });

  const name = member.data?.firstName ?? "";
  const [welcome, setWelcome] = useState(false);
  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("welcome") === "1") {
      setWelcome(true);
      window.history.replaceState(null, "", "/portal");
    }
  }, []);
  const welcomePtsMissing = welcome && wallet.isFetched && (wallet.data?.balances.PTS?.active ?? 0) === 0;
  useEffect(() => {
    if (welcomePtsMissing) markPending(["+100 punti di benvenuto in arrivo…"]);
  }, [welcomePtsMissing, markPending]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Ciao{name ? ` ${name}` : ""} 👋</h1>
        {theme.heroTitle ? <p className="text-sm text-[var(--color-pt-night)]/70">{theme.heroTitle}</p> : null}
      </div>
      <PendingBanner />
      <PopupHost />

      <QueryState query={wallet} service="wallet">
        {(w) => (
          <div className="space-y-3">
            <MemberCard
              memberName={member.data?.firstName ? `${member.data.firstName} ${member.data.lastName ?? ""}` : memberId}
              memberId={memberId}
              pts={w.balances.PTS?.active ?? 0}
              tier={w.tier.code}
            />
            <div className="rounded-xl border border-[var(--color-bo-border)] bg-white p-3">
              {w.tier.next ? (
                <p className="text-sm text-[var(--color-pt-night)]">
                  Ti mancano <strong>{formatPoints(w.tier.next.missing)}</strong> punti status per {w.tier.next.code}
                </p>
              ) : (
                <p className="text-sm text-[var(--color-pt-night)]">Hai raggiunto il livello più alto 🎉</p>
              )}
              <div className="mt-2 h-2 overflow-hidden rounded bg-slate-100">
                <div className="h-full bg-[var(--color-pt-primary)]" style={{ width: `${w.tier.progressPct}%` }} />
              </div>
            </div>
            {w.expiringSoon && w.expiringSoon.amount > 0 && w.expiringSoon.nextExpiryAt ? (
              <Link
                href="/portal/rewards"
                className="flex items-center justify-between gap-3 rounded-xl bg-[var(--color-pt-coin)]/20 px-3 py-2.5 text-sm text-[var(--color-pt-night)]"
              >
                <span>
                  <strong className="tabular-nums">{formatPoints(w.expiringSoon.amount)}</strong> punti scadono il{" "}
                  {formatDate(w.expiringSoon.nextExpiryAt)} — usali
                </span>
                <span aria-hidden>→</span>
              </Link>
            ) : null}
          </div>
        )}
      </QueryState>

      <ContentSlot placement="HOME_HERO" />

      <div className="grid grid-cols-2 gap-2">
        <QuickLink href="/portal/earn" label="Come guadagnare" />
        <QuickLink href="/portal/rewards" label="Premi" />
        <QuickLink href="/portal/my-rewards" label="I miei premi" />
        <QuickLink href="/portal/activity" label="La mia attività" />
        <QuickLink href="/portal/achievements" label="Obiettivi e badge" />
        <QuickLink href="/portal/invite" label="Porta un amico" />
      </div>

      <ContentSlot placement="HOME_GRID" />

      <section>
        <div className="mb-1 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-[var(--color-pt-night)]">Ultimi movimenti</h2>
          <Link href="/portal/activity" className="text-xs text-[var(--color-pt-primary)]">Vedi tutti</Link>
        </div>
        <QueryState
          query={activity}
          service="wallet"
          isEmpty={(d) => d.length === 0}
          emptyTitle="Nessun movimento ancora"
          emptyHint="Scopri come guadagnare punti."
        >
          {(d) => (
            <ul className="rounded-xl border border-[var(--color-bo-border)] bg-white px-3">
              {d.map((item) => (
                <ActivityRow key={item.id} item={item} />
              ))}
            </ul>
          )}
        </QueryState>
      </section>
    </div>
  );
}

function QuickLink({ href, label }: { href: string; label: string }) {
  return (
    <Link
      href={href}
      className="rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-center text-sm font-medium text-[var(--color-pt-night)] hover:border-[var(--color-pt-primary)]"
    >
      {label}
    </Link>
  );
}
