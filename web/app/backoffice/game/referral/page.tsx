"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { CampaignSummary, PortalCampaign, ReferralLink, ReferralOverview } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { SideSheet } from "@/components/bo/SideSheet";
import { Card, CardBody } from "@/components/ui/card";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { actionLabel } from "@/lib/campaign/describe";
import { formatRate, referralFunnel } from "@/lib/member/referral";
import { formatDate } from "@/lib/format/dates";
import { cn } from "@/lib/cn";

// BO-17 Referral (docs/08 §BO-17, F-REF-01/02): KPI (inviti, completati, tasso), imbuto, top presentatori, tabella dei
// legami (invitante, invitato, stato PENDING/COMPLETED, date), regola di completamento in chiaro con le campagne
// CMP-REFERRAL-*. Dettaglio di un presentatore: GET /v1/members/{id}/referrals.
const REFERRAL_CAMPAIGNS = ["CMP-REFERRAL-REFERRER", "CMP-REFERRAL-REFEREE"];

export default function ReferralPage() {
  const overview = useLhQuery<ReferralOverview>("member", "/v1/referral/overview", undefined, { refetchInterval: 30_000 });
  const [referrer, setReferrer] = useState<{ id: string; name: string } | null>(null);

  return (
    <div>
      <PageHeader title="Referral" subtitle="Codice amico alla registrazione, premio a entrambi alla prima azione qualificante dell'invitato." />
      <QueryState query={overview} service="member">
        {(o) => (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Kpi label="Inviti registrati" value={o.invited} />
              <Kpi label="Completati" value={o.completed} />
              <Kpi label="In attesa" value={o.pending} />
              <Kpi label="Tasso di completamento" value={formatRate(o.rate)} />
            </div>

            <div className="grid gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
              <Card>
                <CardBody>
                  <h2 className="mb-3 text-sm font-semibold">Imbuto</h2>
                  <Funnel steps={referralFunnel(o)} />
                  <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">
                    Gli inviti inviati non sono tracciati: l&apos;imbuto parte dalle registrazioni con codice amico.
                  </p>
                </CardBody>
              </Card>
              <RuleCard qualifyingActionType={o.qualifyingActionType} />
            </div>

            <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
              <Card>
                <CardBody>
                  <h2 className="mb-2 text-sm font-semibold">Top presentatori</h2>
                  {o.topReferrers.length === 0 ? (
                    <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun invito ancora.</p>
                  ) : (
                    <ol className="space-y-1">
                      {o.topReferrers.map((t, i) => (
                        <li key={t.memberId}>
                          <button
                            onClick={() => setReferrer({ id: t.memberId, name: t.name })}
                            className="flex w-full items-center justify-between rounded px-2 py-1.5 text-left text-sm hover:bg-slate-50"
                          >
                            <span>
                              <span className="mr-2 font-mono text-xs text-[var(--color-bo-ink-2)]">{i + 1}.</span>
                              {t.name} <CodeText>{t.memberId}</CodeText>
                            </span>
                            <span className="tabular-nums text-xs text-[var(--color-bo-ink-2)]">
                              <strong className="text-[var(--color-bo-ink)]">{t.completed}</strong> / {t.invited} completati
                            </span>
                          </button>
                        </li>
                      ))}
                    </ol>
                  )}
                </CardBody>
              </Card>
              <div>
                <h2 className="mb-2 text-sm font-semibold">Legami</h2>
                {o.links.length === 0 ? (
                  <p className="rounded-md border border-dashed border-[var(--color-bo-border)] p-4 text-sm text-[var(--color-bo-ink-2)]">
                    Nessun legame: un membro si iscrive con un codice amico da /portal/join.
                  </p>
                ) : (
                  <DataTable columns={LINK_COLUMNS} rows={o.links} rowKey={(l) => l.refereeId} onRowClick={(l) => setReferrer({ id: l.referrerId, name: l.referrerName })} />
                )}
              </div>
            </div>
          </div>
        )}
      </QueryState>
      <SideSheet open={referrer != null} title={referrer ? `Invitati da ${referrer.name}` : ""} onClose={() => setReferrer(null)}>
        {referrer ? <ReferrerDetail memberId={referrer.id} /> : null}
      </SideSheet>
    </div>
  );
}

const LINK_COLUMNS: Column<ReferralLink>[] = [
  { key: "referrer", header: "Invitante", render: (l) => <MemberCell id={l.referrerId} name={l.referrerName} /> },
  { key: "referee", header: "Invitato", render: (l) => <MemberCell id={l.refereeId} name={l.refereeName} /> },
  { key: "status", header: "Stato", render: (l) => <ReferralPill status={l.status} /> },
  { key: "registered", header: "Iscritto", render: (l) => formatDate(l.registeredAt) },
  { key: "completed", header: "Completato", render: (l) => (l.completedAt ? formatDate(l.completedAt) : "—") },
];

function MemberCell({ id, name }: { id: string; name: string }) {
  return (
    <Link href={`/backoffice/members/${id}`} onClick={(e) => e.stopPropagation()} className="hover:underline">
      {name} <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{id}</span>
    </Link>
  );
}

function ReferralPill({ status }: { status: ReferralLink["status"] }) {
  return (
    <span
      className={cn(
        "rounded-full px-2 py-0.5 text-xs font-medium",
        status === "COMPLETED" ? "bg-emerald-100 text-emerald-800" : "bg-amber-100 text-amber-800",
      )}
    >
      {status}
    </span>
  );
}

function Kpi({ label, value }: { label: string; value: number | string }) {
  return (
    <Card>
      <CardBody>
        <p className="text-xs text-[var(--color-bo-ink-2)]">{label}</p>
        <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      </CardBody>
    </Card>
  );
}

function Funnel({ steps }: { steps: { label: string; value: number }[] }) {
  const max = Math.max(1, ...steps.map((s) => s.value));
  return (
    <ul className="space-y-2">
      {steps.map((s) => (
        <li key={s.label} className="grid grid-cols-[10rem_minmax(0,1fr)_2.5rem] items-center gap-2 text-sm">
          <span className="text-[var(--color-bo-ink-2)]">{s.label}</span>
          <span className="h-3 rounded-r bg-slate-100">
            <span className="block h-3 rounded-r bg-[var(--color-bo-accent)]" style={{ width: `${(s.value / max) * 100}%` }} />
          </span>
          <span className="text-right tabular-nums">{s.value}</span>
        </li>
      ))}
    </ul>
  );
}

function RuleCard({ qualifyingActionType }: { qualifyingActionType: string }) {
  const campaigns = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns");
  const rewards = useLhQuery<PortalCampaign[]>("campaign", "/v1/portal/campaigns", { codes: REFERRAL_CAMPAIGNS.join(",") });
  return (
    <Card>
      <CardBody>
        <h2 className="mb-2 text-sm font-semibold">Regola di completamento</h2>
        <p className="text-sm">
          Alla <strong>prima azione «{actionLabel(qualifyingActionType)}»</strong> di un invitato il referral si completa: due fatti{" "}
          <CodeText>referral.completed</CodeText>, uno per l&apos;invitante (<CodeText>REFERRER</CodeText>) e uno per l&apos;invitato (
          <CodeText>REFEREE</CodeText>). Le azioni successive non contano.
        </p>
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">Il premio lo decidono le campagne:</p>
        <ul className="mt-1 space-y-1 text-sm">
          {REFERRAL_CAMPAIGNS.map((code) => {
            const c = campaigns.data?.find((x) => x.code === code);
            const r = rewards.data?.find((x) => x.code === code);
            return (
              <li key={code} className="flex flex-wrap items-center gap-2">
                {c ? (
                  <Link href={`/backoffice/campaigns/${c.id}`} className="font-medium hover:underline">{c.name}</Link>
                ) : (
                  <span className="font-medium">{code}</span>
                )}
                {c ? <StatusPill status={c.status} /> : null}
                {r ? <span className="text-xs text-[var(--color-bo-ink-2)]">{r.rewardSummary}{r.memberLimit ? ` · max ${r.memberLimit.max} ${r.memberLimit.period === "EDITION" ? "per edizione" : "in totale"}` : ""}</span> : null}
              </li>
            );
          })}
        </ul>
        {campaigns.isError || rewards.isError ? (
          <p className="mt-2 text-xs text-amber-700">Campagne non raggiungibili: i valori dei premi torneranno appena il servizio risponde.</p>
        ) : null}
      </CardBody>
    </Card>
  );
}

function ReferrerDetail({ memberId }: { memberId: string }) {
  const links = useLhQuery<ReferralLink[]>("member", `/v1/members/${memberId}/referrals`);
  return (
    <QueryState query={links} service="member" isEmpty={(d) => d.length === 0} emptyTitle="Nessun invitato">
      {(d) => (
        <ul className="space-y-2">
          {d.map((l) => (
            <li key={l.refereeId} className="rounded-md border border-[var(--color-bo-border)] p-3 text-sm">
              <div className="flex items-center justify-between">
                <MemberCell id={l.refereeId} name={l.refereeName} />
                <ReferralPill status={l.status} />
              </div>
              <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
                Iscritto il {formatDate(l.registeredAt)}
                {l.completedAt ? ` · completato il ${formatDate(l.completedAt)}` : " · in attesa della prima azione qualificante"}
              </p>
            </li>
          ))}
        </ul>
      )}
    </QueryState>
  );
}
