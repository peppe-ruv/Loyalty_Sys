"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Copy, Download, ShieldAlert } from "lucide-react";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Contest, ContestStats, ContestWinner, InstantHistogram as Histogram, InstantRow, InstantsGenerated } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { Can, useCan } from "@/components/bo/Can";
import { LifecycleBar } from "@/components/bo/LifecycleBar";
import { Card, CardBody } from "@/components/ui/card";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { INPUT } from "@/components/bo/FormBits";
import { ContestSetupForm, type ContestInput } from "@/components/bo/game/ContestSetupForm";
import { PrizeEditor } from "@/components/bo/game/PrizeEditor";
import { InstantHistogram } from "@/components/bo/game/InstantHistogram";
import { DailyBars } from "@/components/bo/campaigns/DailyBars";
import { DISTRIBUTION_LABEL, MECHANIC_LABEL, formatRate, isLocked, prizePoolSummary } from "@/lib/gamification/contests";
import { formatDate, formatDateTime } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";

// BO-14 Concorso instant win (docs/08 §BO-14): dettaglio a schede setup · prizes · instants · winners · stats.
// `/game/contests/new` crea in DRAFT. Pubblicare senza istanti → INSTANTS_NOT_GENERATED con il collegamento a `instants`.
const TABS = [
  { key: "setup", label: "Impostazioni" },
  { key: "prizes", label: "Montepremi" },
  { key: "instants", label: "Istanti" },
  { key: "winners", label: "Vincitori" },
  { key: "stats", label: "Statistiche" },
];

export default function ContestDetailPage() {
  const id = String(useParams().id);
  if (id === "new") return <CreateContest />;
  return <ContestDetail id={id} />;
}

function Back() {
  return (
    <Link href="/backoffice/game/contests" className="mb-2 inline-block text-xs text-[var(--color-bo-ink-2)] hover:underline">
      ← Concorsi
    </Link>
  );
}

function CreateContest() {
  const router = useRouter();
  const [error, setError] = useState<LhError | null>(null);
  const create = useLhMutation<Contest, ContestInput>("gamification", "POST", () => "/v1/contests", {
    onSuccess: (c) => router.replace(`/backoffice/game/contests/${c.id}?tab=prizes`),
  });
  return (
    <div>
      <Back />
      <PageHeader title="Nuovo concorso" subtitle="Nasce in bozza (DRAFT): poi montepremi, istanti e pubblicazione." />
      <ContestSetupForm
        contest={null}
        saving={create.isPending}
        error={error}
        onSubmit={(input) => {
          setError(null);
          create.mutate(input, { onError: setError });
        }}
      />
    </div>
  );
}

function ContestDetail({ id }: { id: string }) {
  const tab = useSearchParams().get("tab") ?? "setup";
  const contest = useLhQuery<Contest>("gamification", `/v1/contests/${id}`);
  return (
    <div>
      <Back />
      <QueryState query={contest} service="gamification">
        {(c) => (
          <>
            <PageHeader
              title={c.name}
              subtitle={`${MECHANIC_LABEL[c.mechanic]} · ${formatDate(c.startAt)} → ${formatDate(c.endAt)} · ${prizePoolSummary(c.prizes)}`}
              actions={<CodeText>{c.code}</CodeText>}
            />
            <div className="mb-4">
              <LifecycleBar
                service="gamification"
                transitionsPath={`/v1/contests/${c.id}/transitions`}
                status={c.status}
                onChanged={() => contest.refetch()}
                renderError={(e) =>
                  e.code === "INSTANTS_NOT_GENERATED" ? (
                    <>
                      {e.detail}{" "}
                      <Link href={`/backoffice/game/contests/${c.id}?tab=instants`} className="font-medium underline">
                        Vai agli istanti
                      </Link>
                    </>
                  ) : (
                    e.detail || e.code
                  )
                }
              />
            </div>
            <Tabs tabs={TABS} current={tab} />
            {tab === "setup" && <SetupTab key={`${c.id}-${c.version}`} contest={c} onChanged={() => contest.refetch()} />}
            {tab === "prizes" && <PrizeEditor key={`${c.id}-${c.status}`} contest={c} onChanged={() => contest.refetch()} />}
            {tab === "instants" && <InstantsTab contest={c} onChanged={() => contest.refetch()} />}
            {tab === "winners" && <WinnersTab contest={c} />}
            {tab === "stats" && <StatsTab contest={c} />}
          </>
        )}
      </QueryState>
    </div>
  );
}

function SetupTab({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const [error, setError] = useState<LhError | null>(null);
  const update = useLhMutation<Contest, ContestInput>("gamification", "PUT", () => `/v1/contests/${contest.id}`, {
    onSuccess: () => onChanged(),
  });
  return (
    <ContestSetupForm
      contest={contest}
      saving={update.isPending}
      error={error}
      onSubmit={(input) => {
        setError(null);
        update.mutate(input, { onError: setError });
      }}
    />
  );
}

// ---------- istanti ----------

function InstantsTab({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const canView = useCan("instants.view");
  const histogram = useLhQuery<Histogram>("gamification", `/v1/contests/${contest.id}/instants/histogram`);
  return (
    <div className="space-y-4">
      <GeneratePanel contest={contest} onGenerated={() => { onChanged(); histogram.refetch(); }} />
      {contest.status === "LIVE" && contest.instants.open > 0 ? (
        <p className="rounded-md border border-sky-200 bg-sky-50 px-3 py-2 text-xs text-sky-900">
          Un istante già passato e non ancora assegnato resta aperto: la prossima giocata di chiunque lo vince. Per questo,
          nella demo, la prima giocata tende a vincere (docs/10 §6).
        </p>
      ) : null}
      <Card>
        <CardBody className="pt-4">
          <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
            <h3 className="text-sm font-semibold">Istanti per giorno</h3>
            <span className="text-xs text-[var(--color-bo-ink-2)]">
              {contest.instants.total} istanti · {contest.instants.claimed} assegnati · {contest.instants.open} aperti · {contest.instants.voided} annullati
            </span>
          </div>
          <QueryState query={histogram} service="gamification">
            {(h) => <InstantHistogram days={h.days} />}
          </QueryState>
        </CardBody>
      </Card>
      {canView ? (
        <InstantTable contest={contest} />
      ) : (
        <div className="flex gap-3 rounded-md border border-[var(--color-bo-border)] bg-white p-4">
          <ShieldAlert className="mt-0.5 size-5 shrink-0 text-[var(--color-bo-ink-2)]" aria-hidden />
          <div>
            <p className="text-sm font-medium">Tabella degli istanti riservata (403)</p>
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              Chi configura il concorso non deve conoscere gli istanti: la tabella è visibile solo ai ruoli LEGAL e ADMIN.
              L&apos;istogramma qui sopra mostra quanti istanti cadono in ogni giorno, non quando.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}

function GeneratePanel({ contest, onGenerated }: { contest: Contest; onGenerated: () => void }) {
  const [seed, setSeed] = useState(String(contest.seed));
  const [error, setError] = useState<LhError | null>(null);
  const [result, setResult] = useState<InstantsGenerated | null>(null);
  const [copied, setCopied] = useState(false);
  const locked = isLocked(contest.status);
  const generate = useLhMutation<InstantsGenerated, { seed?: number }>("gamification", "POST", () => `/v1/contests/${contest.id}/instants/generate`, {
    onSuccess: (r) => {
      setResult(r);
      onGenerated();
    },
  });
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-sm font-semibold">Generazione</h3>
          <span className="text-xs text-[var(--color-bo-ink-2)]">
            {contest.instantsGeneratedAt ? `Ultima generazione ${formatDateTime(contest.instantsGeneratedAt)}` : "Istanti non ancora generati"}
          </span>
        </div>
        <dl className="grid gap-2 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-xs text-[var(--color-bo-ink-2)]">Distribuzione</dt>
            <dd>{DISTRIBUTION_LABEL[contest.distribution] ?? contest.distribution}</dd>
          </div>
          <div>
            <dt className="text-xs text-[var(--color-bo-ink-2)]">Seme in uso</dt>
            <dd className="flex items-center gap-2">
              <span className="font-mono">{contest.seed}</span>
              <button
                type="button"
                aria-label="Copia il seme"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(String(contest.seed));
                    setCopied(true);
                  } catch {
                    setCopied(false);
                  }
                }}
                className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-[var(--color-bo-accent)] hover:bg-slate-100"
              >
                <Copy className="size-3.5" aria-hidden /> {copied ? "Copiato" : "Copia"}
              </button>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-[var(--color-bo-ink-2)]">Montepremi</dt>
            <dd className="tabular-nums">{contest.prizesTotal} unità → {contest.prizesTotal} istanti</dd>
          </div>
        </dl>
        {locked ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            Concorso {contest.status}: gli istanti sono immutabili dall&apos;avvio. Con lo stesso seme chiunque può rigenerarli
            altrove e verificarli.
          </p>
        ) : (
          <Can capability="object.edit" mode="disable">
            <div className="flex flex-wrap items-end gap-2">
              <label className="text-sm">
                <span className="mb-1 block text-xs font-medium text-[var(--color-bo-ink-2)]">Seme</span>
                <input type="number" min={0} value={seed} onChange={(e) => setSeed(e.target.value)} className={`${INPUT} w-40 font-mono`} />
              </label>
              <button
                type="button"
                disabled={generate.isPending || contest.prizes.length === 0}
                onClick={() => {
                  setError(null);
                  generate.mutate(seed === "" ? {} : { seed: Number(seed) }, { onError: setError });
                }}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
              >
                {contest.instantsGeneratedAt ? "Rigenera istanti" : "Genera istanti"}
              </button>
              {result ? (
                <span className="text-xs text-emerald-700">
                  Generati {result.instants} istanti col seme {result.seed}.
                </span>
              ) : null}
              {error ? (
                <span className="text-xs text-red-700" role="alert">
                  {error.detail || error.code}
                </span>
              ) : null}
            </div>
          </Can>
        )}
      </CardBody>
    </Card>
  );
}

const INSTANT_STATUSES = ["", "OPEN", "CLAIMED", "VOID"];

function InstantTable({ contest }: { contest: Contest }) {
  const [status, setStatus] = useState("");
  const [prizeId, setPrizeId] = useState("");
  const [page, setPage] = useState(0);
  const query = useLhQuery<Page<InstantRow>>("gamification", `/v1/contests/${contest.id}/instants`, { status, prizeId, page, size: 25 });
  const columns: Column<InstantRow>[] = [
    { key: "at", header: "Istante", render: (i) => <span className="whitespace-nowrap tabular-nums">{formatDateTime(i.instantAt)}</span> },
    {
      key: "prize",
      header: "Premio",
      render: (i) => (
        <span>
          {i.prizeName} <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{i.prizeCode}</span>
        </span>
      ),
    },
    {
      key: "status",
      header: "Stato",
      render: (i) => (
        <span className="inline-flex items-center gap-1.5">
          <StatusPill status={i.status} />
          {i.planted ? <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[11px] text-violet-800">piantato</span> : null}
        </span>
      ),
    },
    { key: "member", header: "Assegnato a", render: (i) => (i.claimedBy ? <CodeText>{i.claimedBy}</CodeText> : <span className="text-xs text-[var(--color-bo-ink-2)]">—</span>) },
    { key: "claimedAt", header: "Quando", render: (i) => <span className="text-xs tabular-nums">{i.claimedAt ? formatDateTime(i.claimedAt) : "—"}</span> },
  ];
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="mr-auto text-sm font-semibold">Istanti vincenti</h3>
          <select aria-label="Stato istante" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm">
            {INSTANT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s || "Tutti gli stati"}
              </option>
            ))}
          </select>
          <select aria-label="Premio" value={prizeId} onChange={(e) => { setPrizeId(e.target.value); setPage(0); }} className="rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm">
            <option value="">Tutti i premi</option>
            {contest.prizes.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <QueryState query={query} service="gamification" isEmpty={(d) => d.items.length === 0} emptyTitle="Nessun istante" emptyHint="Genera gli istanti o cambia i filtri.">
          {(d) => (
            <>
              <DataTable columns={columns} rows={d.items} rowKey={(i) => i.id} />
              <div className="flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
                <span>
                  {formatPoints(d.page.totalItems)} istanti · pagina {d.page.number + 1} di {Math.max(1, d.page.totalPages)}
                </span>
                <span className="flex gap-2">
                  <button disabled={page === 0} onClick={() => setPage((p) => p - 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-0.5 disabled:opacity-40">
                    ← Precedente
                  </button>
                  <button disabled={page + 1 >= d.page.totalPages} onClick={() => setPage((p) => p + 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-0.5 disabled:opacity-40">
                    Successiva →
                  </button>
                </span>
              </div>
            </>
          )}
        </QueryState>
      </CardBody>
    </Card>
  );
}

// ---------- vincitori ----------

function WinnersTab({ contest }: { contest: Contest }) {
  const query = useLhQuery<ContestWinner[]>("gamification", `/v1/contests/${contest.id}/winners`, undefined, { refetchInterval: 15_000 });
  const columns: Column<ContestWinner>[] = [
    { key: "when", header: "Vinto il", render: (w) => <span className="whitespace-nowrap text-xs tabular-nums">{formatDateTime(w.playedAt)}</span> },
    {
      key: "member",
      header: "Membro",
      render: (w) => (
        <Link href={`/backoffice/members/${w.memberId}`} className="hover:underline" onClick={(e) => e.stopPropagation()}>
          {w.nickname ?? "—"} <CodeText>{w.memberId}</CodeText>
        </Link>
      ),
    },
    { key: "prize", header: "Premio", render: (w) => <span>{w.prizeName}</span> },
    { key: "delivery", header: "Consegna", render: (w) => <DeliveryCell winner={w} onChanged={() => query.refetch()} /> },
  ];
  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <a
          href={`/api/lh/gamification/v1/contests/${contest.id}/winners.csv`}
          download
          className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50"
        >
          <Download className="size-4" aria-hidden /> Esporta CSV
        </a>
      </div>
      <QueryState
        query={query}
        service="gamification"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Ancora nessuna vincita"
        emptyHint={contest.status === "LIVE" ? "Le vincite compaiono qui appena un membro gioca in un istante vincente." : "Il concorso non ha vincite registrate."}
      >
        {(d) => <DataTable columns={columns} rows={d} rowKey={(w) => w.playId} />}
      </QueryState>
    </div>
  );
}

function DeliveryCell({ winner, onChanged }: { winner: ContestWinner; onChanged: () => void }) {
  const [note, setNote] = useState(winner.deliveryNote ?? "");
  const [error, setError] = useState<LhError | null>(null);
  const save = useLhMutation<unknown, { status: string; note: string }>("gamification", "POST", () => `/v1/plays/${winner.playId}/delivery`, {
    onSuccess: () => onChanged(),
  });
  if (winner.prizeType !== "PHYSICAL") {
    return <span className="text-xs text-[var(--color-bo-ink-2)]">automatica</span>;
  }
  const next = winner.deliveryStatus === "DELIVERED" ? "PENDING" : "DELIVERED";
  return (
    <div className="flex flex-wrap items-center gap-2">
      <StatusPill status={winner.deliveryStatus} />
      <Can capability="delivery.handle" mode="disable">
        <input aria-label="Nota di consegna" value={note} onChange={(e) => setNote(e.target.value)} placeholder="nota (es. corriere)" className={`${INPUT} w-40 py-1 text-xs`} />
        <button
          type="button"
          disabled={save.isPending}
          onClick={() => {
            setError(null);
            save.mutate({ status: next, note }, { onError: setError });
          }}
          className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
        >
          {next === "DELIVERED" ? "Segna consegnato" : "Riporta in attesa"}
        </button>
      </Can>
      {error ? <span className="text-xs text-red-700">{error.detail || error.code}</span> : null}
    </div>
  );
}

// ---------- statistiche ----------

function StatsTab({ contest }: { contest: Contest }) {
  const query = useLhQuery<ContestStats>("gamification", `/v1/contests/${contest.id}/stats`, undefined, { refetchInterval: 15_000 });
  return (
    <QueryState query={query} service="gamification">
      {(s) => (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
            <Stat label="Giocate" value={formatPoints(s.plays)} />
            <Stat label="Vincite" value={formatPoints(s.wins)} />
            <Stat label="Tasso di vincita" value={formatRate(s.wins, s.plays)} />
            <Stat label="Giocatori" value={formatPoints(s.players)} />
            <Stat label="Premi residui" value={`${formatPoints(s.prizesRemaining)} / ${formatPoints(s.prizesTotal)}`} />
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardBody className="pt-4">
                <h3 className="mb-2 text-sm font-semibold">Giocate al giorno</h3>
                <DailyBars data={s.daily.map((d) => ({ day: d.day, value: d.plays }))} colorVar="var(--color-bo-accent)" unit="giocate" />
              </CardBody>
            </Card>
            <Card>
              <CardBody className="pt-4">
                <h3 className="mb-2 text-sm font-semibold">Vincite al giorno</h3>
                <DailyBars data={s.daily.map((d) => ({ day: d.day, value: d.wins }))} colorVar="var(--color-earn)" unit="vincite" />
              </CardBody>
            </Card>
          </div>
          <div className="overflow-x-auto rounded-md border border-[var(--color-bo-border)]">
            <table className="w-full text-sm">
              <thead className="border-b border-[var(--color-bo-border)] bg-slate-50 text-left text-xs uppercase tracking-wide text-[var(--color-bo-ink-2)]">
                <tr>
                  <th className="px-3 py-2 font-medium">Premio</th>
                  <th className="px-3 py-2 text-right font-medium">Totale</th>
                  <th className="px-3 py-2 text-right font-medium">Vinti</th>
                  <th className="px-3 py-2 text-right font-medium">Residui</th>
                </tr>
              </thead>
              <tbody>
                {s.prizes.map((p) => (
                  <tr key={p.code} className="border-b border-[var(--color-bo-border)] last:border-0">
                    <td className="px-3 py-2">
                      {p.name} <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{p.code}</span>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.total}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.won}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.remaining}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </QueryState>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white p-3">
      <p className="text-xs text-[var(--color-bo-ink-2)]">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{value}</p>
    </div>
  );
}
