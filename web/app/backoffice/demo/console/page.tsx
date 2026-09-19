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

// BO-30 Console demo (docs/08 §BO-30): stato dei servizi + reset orchestrato. La macchina del tempo (job) è M3.
const RESETTABLE = ["ingestion", "member", "campaign", "wallet"];

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
              esserci brevi incoerenze tra i servizi. La macchina del tempo (job con data di riferimento) arriva con M3.
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
