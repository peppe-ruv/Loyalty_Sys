"use client";

import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { WalletView, MemberView } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { usePending } from "@/components/portal/PendingContext";
import { MemberCard } from "@/components/portal/MemberCard";
import { PendingBanner, ActivityRow, type ActivityItem } from "@/components/portal/parts";
import { QueryState } from "@/components/bo/QueryState";
import { formatPoints } from "@/lib/format/points";

// PT-01 Home (docs/09 §PT-01): tessera, saldo, avanzamento livello, ultimi movimenti, azioni rapide.
export default function PortalHome() {
  const memberId = useActiveMember();
  const { pending } = usePending();
  const wallet = useLhQuery<WalletView>("wallet", `/v1/portal/wallets/${memberId}`, undefined, {
    refetchInterval: pending ? 5000 : undefined,
  });
  const member = useLhQuery<MemberView>("member", `/v1/members/${memberId}`);
  const activity = useLhQuery<ActivityItem[]>("wallet", `/v1/portal/wallets/${memberId}/activity`, { size: 3 }, {
    refetchInterval: pending ? 5000 : undefined,
  });

  const name = member.data?.firstName ?? "";

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Ciao{name ? ` ${name}` : ""} 👋</h1>
      <PendingBanner />

      <QueryState query={wallet} service="wallet">
        {(w) => (
          <div className="space-y-3">
            <MemberCard
              programName="Club Aurora"
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
          </div>
        )}
      </QueryState>

      <div className="grid grid-cols-2 gap-2">
        <QuickLink href="/portal/earn" label="Come guadagnare" />
        <QuickLink href="/portal/activity" label="La mia attività" />
      </div>

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
