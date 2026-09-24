"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { MemberView, WalletView, LedgerEntry, EvaluationRow } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader, StatusPill, TierBadge, CodeText, PointsAmount } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";
import { Can } from "@/components/bo/Can";
import { AdjustPointsDialog } from "@/components/bo/AdjustPointsDialog";
import { TypePill } from "@/components/bo/segments/TypePill";
import type { MemberSegment } from "@/lib/segments/types";
import { MemberAttributesCard } from "@/components/bo/members/MemberAttributesCard";
import { AnonymizeDialog } from "@/components/bo/members/AnonymizeDialog";
import { isAnonymized, memberDisplayName, personalValue } from "@/lib/member/anonymized";

// BO-03 Scheda 360° (docs/08 §BO-03). M1: schede overview / ledger / actions; ogni pannello degrada da solo.
// M3.6: azione rapida "Rettifica punti" (CARE/ADMIN). M6.6: scheda segments (segmenti di appartenenza); M6.7 attributi ed etichette.
// M7.5: menu *Anonimizza* (ADMIN, conferma con l'ID digitato); dopo, campi personali "Membro anonimo" e azioni disabilitate.
const TABS = [
  { key: "overview", label: "Panoramica" },
  { key: "ledger", label: "Movimenti" },
  { key: "actions", label: "Azioni" },
  { key: "segments", label: "Segmenti" },
];

export default function MemberDetailPage() {
  const id = String(useParams().id);
  const tab = useSearchParams().get("tab") ?? "overview";
  const member = useLhQuery<MemberView>("member", `/v1/members/${id}`);
  const wallet = useLhQuery<WalletView>("wallet", `/v1/wallets/${id}`);
  const [adjusting, setAdjusting] = useState(false);
  const [anonymizing, setAnonymizing] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  return (
    <div>
      <QueryState query={member} service="member">
        {(m) => {
          const anonymized = isAnonymized(m.status);
          return (
            <>
              <PageHeader
                title={memberDisplayName(m, "Membro")}
                subtitle={undefined}
                actions={
                  <div className="flex items-center gap-2">
                    <StatusPill status={m.status} />
                    <TierBadge tier={m.tier} />
                    <CodeText>{m.id}</CodeText>
                    <Can capability="points.adjust" mode="disable">
                      <button
                        onClick={() => setAdjusting(true)}
                        disabled={!wallet.data || anonymized}
                        title={anonymized ? "Membro anonimizzato: azioni disabilitate" : undefined}
                        className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-xs font-medium hover:bg-slate-50 disabled:opacity-50"
                      >
                        Rettifica punti ●
                      </button>
                    </Can>
                    <MemberMenu anonymized={anonymized} onAnonymize={() => setAnonymizing(true)} />
                  </div>
                }
              />
              {done ? (
                <p role="status" className="mb-3 rounded bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
                  {done}
                </p>
              ) : anonymized ? (
                <p className="mb-3 rounded bg-slate-100 px-3 py-2 text-sm italic text-slate-600">
                  Membro anonimizzato: i dati personali sono stati rimossi, movimenti e statistiche restano. Le azioni sono disabilitate.
                </p>
              ) : null}
              {anonymizing ? (
                <AnonymizeDialog
                  member={m}
                  onClose={() => setAnonymizing(false)}
                  onDone={(updated) => {
                    setAnonymizing(false);
                    setDone(
                      `Membro ${updated.id} anonimizzato. I dati personali spariscono da tutti i servizi entro pochi secondi; movimenti e saldo restano.`,
                    );
                  }}
                />
              ) : null}
            </>
          );
        }}
      </QueryState>

      <Tabs tabs={TABS} current={tab} />

      {tab === "overview" && <OverviewTab id={id} />}
      {tab === "ledger" && <LedgerTab id={id} />}
      {tab === "actions" && <ActionsTab id={id} />}
      {tab === "segments" && <SegmentsTab id={id} />}

      {adjusting && wallet.data ? (
        <AdjustPointsDialog memberId={id} balance={wallet.data.balances.PTS?.active ?? 0} onClose={() => setAdjusting(false)} />
      ) : null}
    </div>
  );
}

/** Menu della scheda (docs/08 §BO-03): per ora *Anonimizza* ● (solo ADMIN, capacità member.anonymize). */
function MemberMenu({ anonymized, onAnonymize }: { anonymized: boolean; onAnonymize: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Altre azioni"
        className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs font-medium hover:bg-slate-50"
      >
        ⋯
      </button>
      {open ? (
        <div role="menu" className="absolute right-0 z-30 mt-1 w-48 rounded border border-[var(--color-bo-border)] bg-white py-1 text-sm shadow-md">
          <Can capability="member.anonymize" mode="disable">
            <button
              role="menuitem"
              onClick={() => {
                setOpen(false);
                onAnonymize();
              }}
              disabled={anonymized}
              title={anonymized ? "Membro già anonimizzato" : undefined}
              className="block w-full px-3 py-1.5 text-left text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Anonimizza ●
            </button>
          </Can>
        </div>
      ) : null}
    </div>
  );
}

function OverviewTab({ id }: { id: string }) {
  const member = useLhQuery<MemberView>("member", `/v1/members/${id}`);
  const wallet = useLhQuery<WalletView>("wallet", `/v1/wallets/${id}`);
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card>
        <CardBody className="pt-4">
          <h2 className="mb-2 text-sm font-semibold">Profilo</h2>
          <QueryState query={member} service="member">
            {(m) => (
              <dl className={isAnonymized(m.status) ? "space-y-1 text-sm [&_dd]:italic [&_dd]:text-slate-500" : "space-y-1 text-sm"}>
                <Row label="Nome" value={personalValue(m.status, memberDisplayName(m, ""))} />
                <Row label="E-mail" value={personalValue(m.status, m.email)} />
                <Row label="External ID" value={personalValue(m.status, m.externalId)} />
                <Row label="Codice amico" value={m.email ? undefined : "—"} />
                <Row label="Iscritto il" value={m.registeredAt ? formatDateTime(m.registeredAt) : "—"} />
                <Row label="Versione" value={String(m.version)} />
              </dl>
            )}
          </QueryState>
        </CardBody>
      </Card>
      <Card>
        <CardBody className="pt-4">
          <h2 className="mb-2 text-sm font-semibold">Saldo e livello</h2>
          <QueryState query={wallet} service="wallet">
            {(w) => (
              <div className="space-y-2 text-sm">
                <div className="flex items-baseline justify-between">
                  <span className="text-[var(--color-bo-ink-2)]">Punti (PTS)</span>
                  <span className="text-lg font-semibold tabular-nums">{formatPoints(w.balances.PTS?.active ?? 0)}</span>
                </div>
                <div className="flex items-baseline justify-between">
                  <span className="text-[var(--color-bo-ink-2)]">Status (STS)</span>
                  <span className="tabular-nums">{formatPoints(w.balances.STS?.active ?? 0)}</span>
                </div>
                <div className="pt-2">
                  <div className="mb-1 flex items-center justify-between text-xs">
                    <TierBadge tier={w.tier.code} />
                    {w.tier.next ? (
                      <span className="text-[var(--color-bo-ink-2)]">
                        mancano {formatPoints(w.tier.next.missing)} STS a {w.tier.next.code}
                      </span>
                    ) : (
                      <span className="text-[var(--color-bo-ink-2)]">livello massimo</span>
                    )}
                  </div>
                  <div className="h-2 overflow-hidden rounded bg-slate-100">
                    <div className="h-full bg-[var(--color-bo-accent)]" style={{ width: `${w.tier.progressPct}%` }} />
                  </div>
                </div>
              </div>
            )}
          </QueryState>
        </CardBody>
      </Card>
    </div>
  );
}

function LedgerTab({ id }: { id: string }) {
  const query = useLhQuery<LedgerEntry[]>("wallet", `/v1/wallets/${id}/ledger`, { limit: 100 });
  const columns: Column<LedgerEntry>[] = [
    { key: "when", header: "Quando", render: (e) => formatDateTime(e.occurredAt) },
    { key: "type", header: "Tipo", render: (e) => <StatusPill status={e.type} /> },
    { key: "campaign", header: "Campagna", render: (e) => (e.campaignCode ? <CodeText>{e.campaignCode}</CodeText> : "—") },
    { key: "cur", header: "Valuta", render: (e) => e.currency },
    {
      key: "amount",
      header: "Importo",
      className: "text-right",
      render: (e) => <PointsAmount value={e.direction === "-" ? -e.amount : e.amount} />,
    },
    { key: "bal", header: "Saldo dopo", className: "text-right", render: (e) => <span className="tabular-nums">{formatPoints(e.balanceAfter)}</span> },
  ];
  return (
    <QueryState query={query} service="wallet" isEmpty={(d) => d.length === 0} emptyTitle="Nessun movimento">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.id} />}
    </QueryState>
  );
}

function ActionsTab({ id }: { id: string }) {
  const query = useLhQuery<EvaluationRow[]>("campaign", "/v1/evaluations", { memberId: id, limit: 100 });
  const columns: Column<EvaluationRow>[] = [
    { key: "when", header: "Quando", render: (e) => formatDateTime(e.actionTime) },
    { key: "type", header: "Azione", render: (e) => <CodeText>{e.actionType}</CodeText> },
    { key: "outcome", header: "Esito", render: (e) => <StatusPill status={e.outcome} /> },
    { key: "detail", header: "Dettaglio", render: (e) => <ResultSummary json={e.resultsJson} /> },
  ];
  return (
    <QueryState query={query} service="campaign" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna azione valutata">
      {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.actionId} />}
    </QueryState>
  );
}

/**
 * Scheda `segments` (docs/08 §BO-03, M6.6): segmenti di appartenenza come chip (→ BO-04); M6.7: attributi
 * personalizzati ed etichette modificabili.
 */
function SegmentsTab({ id }: { id: string }) {
  const segments = useLhQuery<MemberSegment[]>("member", `/v1/members/${id}/segments`);
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card>
        <CardBody className="pt-4">
          <h2 className="mb-2 text-sm font-semibold">Segmenti di appartenenza</h2>
          <QueryState
            query={segments}
            service="member"
            isEmpty={(d) => d.length === 0}
            emptyTitle="In nessun segmento"
            emptyHint="I segmenti dinamici si aggiornano al ricalcolo (BO-04 «Ricalcola ora» o il job della console demo)."
          >
            {(d) => (
              <ul className="flex flex-wrap gap-2">
                {d.map((s) => (
                  <li key={s.id}>
                    <Link
                      href={`/backoffice/segments/${s.code}`}
                      title={`${s.name} · dentro da ${formatDateTime(s.enteredAt)}`}
                      className={
                        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs hover:bg-slate-50 " +
                        (s.status === "ARCHIVED" ? "border-dashed text-slate-400" : "border-[var(--color-bo-border)]")
                      }
                    >
                      <span className="font-medium">{s.name}</span>
                      <span className="font-mono text-[10px] text-[var(--color-bo-ink-2)]">{s.code}</span>
                      <TypePill type={s.type} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </QueryState>
        </CardBody>
      </Card>
      <MemberAttributesCard id={id} />
    </div>
  );
}

function ResultSummary({ json }: { json: string }) {
  let results: { campaignCode: string; matched: boolean; reason: string | null }[] = [];
  try {
    results = JSON.parse(json);
  } catch {
    return <span className="text-xs text-slate-400">—</span>;
  }
  const matched = results.filter((r) => r.matched).map((r) => r.campaignCode);
  const skipped = results.filter((r) => !r.matched);
  return (
    <span className="text-xs">
      {matched.length > 0 ? <span className="text-emerald-700">✓ {matched.join(", ")}</span> : null}
      {matched.length > 0 && skipped.length > 0 ? " · " : ""}
      {skipped.length > 0 ? (
        <span className="text-slate-500">
          {skipped.map((r) => `${r.campaignCode} (${r.reason})`).slice(0, 3).join(", ")}
        </span>
      ) : null}
    </span>
  );
}

function Row({ label, value }: { label: string; value?: string }) {
  if (value === undefined) return null;
  return (
    <div className="flex justify-between">
      <dt className="text-[var(--color-bo-ink-2)]">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
