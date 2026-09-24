"use client";

import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { WalletView, MemberView, Tier } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { MemberCard } from "@/components/portal/MemberCard";
import { QueryState } from "@/components/bo/QueryState";
import { formatPoints } from "@/lib/format/points";

// PT-08 Profilo e livello (docs/09 §PT-08): carta, "Il tuo livello" (scala, vantaggi, moltiplicatore,
// punti status, regola di permanenza), "I tuoi dati". La registrazione (/join) e le modifiche sono M5.
export default function PortalProfile() {
  const memberId = useActiveMember();
  const wallet = useLhQuery<WalletView>("wallet", `/v1/portal/wallets/${memberId}`);
  const tiers = useLhQuery<Tier[]>("wallet", "/v1/portal/tiers");
  const member = useLhQuery<MemberView>("member", `/v1/members/${memberId}`);

  const fullName = member.data?.firstName
    ? `${member.data.firstName} ${member.data.lastName ?? ""}`.trim()
    : memberId;

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Io</h1>

      <QueryState query={wallet} service="wallet">
        {(w) => (
          <div className="space-y-4">
            <MemberCard
              programName="Club Aurora"
              memberName={fullName}
              memberId={memberId}
              pts={w.balances.PTS?.active ?? 0}
              tier={w.tier.code}
            />

            <section>
              <h2 className="mb-2 text-sm font-semibold text-[var(--color-pt-night)]">Il tuo livello</h2>
              <p className="mb-2 text-sm text-[var(--color-pt-night)]/80">
                Sei <strong>{w.tier.name}</strong> · guadagni <strong>×{w.tier.multiplier.toLocaleString("it-IT")}</strong> punti ·
                {" "}<strong>{formatPoints(w.tier.periodSts)}</strong> punti status quest&apos;edizione.
              </p>
              <QueryState query={tiers} service="wallet">
                {(scale) => (
                  <ul className="space-y-2">
                    {scale.map((t) => {
                      const isCurrent = t.code === w.tier.code;
                      return (
                        <li
                          key={t.code}
                          className={`rounded-xl border bg-white p-3 ${isCurrent ? "border-[var(--color-pt-primary)] ring-1 ring-[var(--color-pt-primary)]" : "border-[var(--color-bo-border)]"}`}
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <span className="inline-block h-3 w-3 rounded-full" style={{ background: t.color ?? "#94a3b8" }} />
                              <span className="text-sm font-semibold text-[var(--color-pt-night)]">{t.name}</span>
                              {isCurrent ? <span className="text-[10px] font-medium text-[var(--color-pt-primary)]">· attuale</span> : null}
                            </div>
                            <span className="font-mono text-xs text-[var(--color-pt-night)]/60">
                              {t.thresholdSts === 0 ? "da 0" : `da ${t.thresholdSts.toLocaleString("it-IT")}`} STS
                            </span>
                          </div>
                          <ul className="mt-1 space-y-0.5 text-xs text-[var(--color-pt-night)]/70">
                            {t.benefits.map((b, i) => <li key={i}>· {b}</li>)}
                          </ul>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </QueryState>
              <p className="mt-2 text-xs text-[var(--color-pt-night)]/60">
                A fine anno puoi scendere al massimo di un livello.
              </p>
            </section>

            <section>
              <h2 className="mb-2 text-sm font-semibold text-[var(--color-pt-night)]">I tuoi dati</h2>
              <dl className="rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-sm">
                <Row label="Nome" value={fullName} />
                <Row label="E-mail" value={member.data?.email ?? "—"} />
                <Row label="Soprannome" value={member.data?.nickname ?? "—"} />
                <Row label="Stato" value={member.data?.status ?? "—"} />
              </dl>
            </section>

            <section>
              <Link
                href="/portal/my-rewards"
                className="flex items-center justify-between rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-sm font-medium text-[var(--color-pt-night)]"
              >
                I miei premi e coupon <span aria-hidden>→</span>
              </Link>
              <Link
                href="/portal/achievements"
                className="mt-2 flex items-center justify-between rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-sm font-medium text-[var(--color-pt-night)]"
              >
                Obiettivi e badge <span aria-hidden>→</span>
              </Link>
            </section>
          </div>
        )}
      </QueryState>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between border-b border-slate-100 py-1.5 last:border-0">
      <dt className="text-[var(--color-pt-night)]/60">{label}</dt>
      <dd className="text-[var(--color-pt-night)]">{value}</dd>
    </div>
  );
}
