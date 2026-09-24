"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { DemoStatus } from "@/lib/api/status";
import type { ServiceCode } from "@/lib/api/services";
import { lhFetch } from "@/lib/api/client";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader, StatusPill } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";
import { it } from "@/lib/i18n/it";

// BO-30 Console demo (docs/08 §BO-30): stato dei servizi + reset orchestrato + macchina del tempo (job M3.2).
const RESETTABLE = ["ingestion", "member", "campaign", "wallet", "reward", "gamification"];

// Job "macchina del tempo" (M3.2 wallet, M4 reward, M5.7 gamification) con asOf: scadenze/preavvisi/rilascio punti,
// scadenza coupon, timeout delle richieste premio, chiusura concorsi (docs/servizi/wallet-service.md §3,
// reward-service.md §3, gamification-service.md §3).
type JobOutcome = {
  lots?: number;
  members?: number;
  amount?: number;
  coupons?: number;
  redemptions?: number;
  contests?: number;
  voided?: number;
  segments?: number;
  entered?: number;
  left?: number;
};
const walletDetail = (verb: string) => (out: JobOutcome) =>
  !out.lots ? "nessun lotto interessato" : `${(out.amount ?? 0).toLocaleString("it-IT")} PTS ${verb} · ${out.lots} lotti · ${out.members} membri`;
const JOBS: { service: ServiceCode; path: string; label: string; detail: (out: JobOutcome) => string }[] = [
  { service: "wallet", path: "expire-points", label: "Scadenza punti", detail: walletDetail("scaduti") },
  { service: "wallet", path: "expiry-warnings", label: "Preavviso scadenze", detail: walletDetail("in preavviso") },
  { service: "wallet", path: "release-pending", label: "Rilascio pending", detail: walletDetail("rilasciati") },
  // M6.6 (docs/servizi/member-service.md §3 "Demo"): ricalcolo dei segmenti dinamici, solo le differenze.
  {
    service: "member",
    path: "refresh-segments",
    label: "Ricalcolo segmenti",
    detail: (o) => `${o.segments ?? 0} segmenti · ${o.entered ?? 0} ingressi · ${o.left ?? 0} uscite`,
  },
  { service: "reward", path: "expire-coupons", label: "Scadenza coupon", detail: (o) => `${o.coupons ?? 0} coupon scaduti` },
  { service: "reward", path: "timeout-redemptions", label: "Timeout richieste", detail: (o) => `${o.redemptions ?? 0} richieste respinte per timeout` },
  {
    service: "gamification",
    path: "close-contests",
    label: "Chiusura concorsi",
    detail: (o) => (!o.contests ? "nessun concorso da chiudere" : `${o.contests} concorsi chiusi · ${o.voided ?? 0} istanti annullati`),
  },
];

export default function ConsolePage() {
  const status = useQuery<DemoStatus>({
    queryKey: ["demo-status"],
    queryFn: async () => {
      const res = await fetch("/api/demo/status", { cache: "no-store" });
      return res.json();
    },
    refetchInterval: 15_000,
  });
  const [log, setLog] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [asOf, setAsOf] = useState("");
  const [jobLog, setJobLog] = useState<string[]>([]);
  const [jobBusy, setJobBusy] = useState(false);

  async function runJob(job: (typeof JOBS)[number]) {
    const { service, path, label } = job;
    setJobBusy(true);
    try {
      const out = await lhFetch<JobOutcome>(service, `/v1/demo/jobs/${path}`, {
        method: "POST",
        query: { asOf: asOf || undefined },
      });
      const when = asOf ? ` (al ${asOf})` : "";
      setJobLog((l) => [`✓ ${label}${when}: ${job.detail(out)}`, ...l]);
    } catch (e) {
      setJobLog((l) => [`✗ ${label}: ${(e as Error).message}`, ...l]);
    } finally {
      setJobBusy(false);
    }
  }

  async function resetOne(service: string) {
    setBusy(true);
    try {
      await lhFetch<{ reset: string[] }>(service as ServiceCode, "/v1/demo/reset", { method: "POST" });
      setLog((l) => [`✓ ${service}: ripristinato`, ...l]);
    } catch (e) {
      setLog((l) => [`✗ ${service}: ${(e as Error).message}`, ...l]);
    } finally {
      setBusy(false);
    }
  }

  async function resetAll() {
    setBusy(true);
    setLog(["Ripristino di tutti i servizi…"]);
    for (const s of RESETTABLE) {
      await resetOne(s);
    }
    setBusy(false);
  }

  const services = status.data?.services ?? [];

  return (
    <div>
      <PageHeader title="Console demo" subtitle="Stato dei servizi e ripristino dei dati" />
      <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-5">
        {services.map((s) => (
          <Card key={s.code}>
            <CardBody className="pt-3">
              <p className="text-sm font-medium">{s.name}</p>
              <p className="mt-1 text-xs">
                <StatusPill status={s.state === "UP" ? "ACTIVE" : s.state === "DOWN" ? "BLOCKED" : "PAUSED"} />
              </p>
              <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
                {it.states[s.state]}
                {s.latencyMs != null ? ` · ${s.latencyMs} ms` : ""}
              </p>
            </CardBody>
          </Card>
        ))}
        {status.data ? (
          <>
            <Infra label="Kafka" state={status.data.kafka.state} />
            <Infra label="Postgres" state={status.data.db.state} />
          </>
        ) : null}
      </div>

      <Can capability="demo.admin" mode="disable">
        <Card>
          <CardBody className="space-y-3 pt-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">Reset dati</h3>
              <button
                onClick={resetAll}
                disabled={busy}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
              >
                {busy ? "In corso…" : "Ripristina tutto"}
              </button>
            </div>
            <div className="flex flex-wrap gap-2">
              {RESETTABLE.map((s) => (
                <button key={s} onClick={() => resetOne(s)} disabled={busy} className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-xs hover:bg-slate-50 disabled:opacity-50">
                  Reset {s}
                </button>
              ))}
            </div>
            {log.length > 0 ? (
              <ul className="space-y-0.5 rounded bg-slate-50 p-2 font-mono text-xs">
                {log.map((line, i) => (
                  <li key={i}>{line}</li>
                ))}
              </ul>
            ) : null}
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              Il reset riporta ogni servizio ai dati di <code>seed/</code>. Durante l&apos;operazione possono
              esserci brevi incoerenze tra i servizi.
            </p>
          </CardBody>
        </Card>
      </Can>

      <Can capability="demo.admin" mode="disable">
        <Card>
          <CardBody className="space-y-3 pt-4">
            <div>
              <h3 className="text-sm font-semibold">Macchina del tempo</h3>
              <p className="mt-0.5 text-xs text-[var(--color-bo-ink-2)]">
                Esegue i job dei servizi con una <strong>data di riferimento</strong>: senza data si usa adesso.
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <label className="text-xs text-[var(--color-bo-ink-2)]" htmlFor="asOf">
                Data di riferimento
              </label>
              <input
                id="asOf"
                type="date"
                value={asOf}
                onChange={(e) => setAsOf(e.target.value)}
                className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
              />
              {asOf ? (
                <button onClick={() => setAsOf("")} className="text-xs text-[var(--color-bo-accent)] underline">
                  adesso
                </button>
              ) : null}
            </div>
            <div className="flex flex-wrap gap-2">
              {JOBS.map((j) => (
                <button
                  key={j.path}
                  onClick={() => runJob(j)}
                  disabled={jobBusy}
                  className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
                >
                  {j.label}
                </button>
              ))}
            </div>
            {jobLog.length > 0 ? (
              <ul className="space-y-0.5 rounded bg-slate-50 p-2 font-mono text-xs">
                {jobLog.map((line, i) => (
                  <li key={i}>{line}</li>
                ))}
              </ul>
            ) : null}
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              Scadenza: azzera i lotti scaduti (movimento <code>EXPIRE</code>). Preavviso: notifica i lotti in
              scadenza entro 30 giorni, una volta per lotto. Rilascio: sblocca i punti in attesa arrivati a
              maturazione. Scadenza coupon: i coupon emessi oltre la scadenza diventano <code>EXPIRED</code>. Timeout
              richieste: le richieste premio in attesa da oltre 10 minuti vengono respinte e lo stock torna disponibile.
              Chiusura concorsi: i concorsi <code>LIVE</code> terminati entro la data passano a <code>ENDED</code> e i loro
              istanti ancora aperti vengono annullati (<code>VOID</code>).
            </p>
          </CardBody>
        </Card>
      </Can>
    </div>
  );
}

function Infra({ label, state }: { label: string; state: string }) {
  return (
    <Card>
      <CardBody className="pt-3">
        <p className="text-sm font-medium">{label}</p>
        <p className="mt-1 text-xs">
          <StatusPill status={state === "UP" ? "ACTIVE" : state === "DOWN" ? "BLOCKED" : "PAUSED"} />
        </p>
      </CardBody>
    </Card>
  );
}
