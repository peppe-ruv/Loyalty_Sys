"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Loader2, Power, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardBody } from "@/components/ui/card";
import { cn } from "@/lib/cn";
import { it } from "@/lib/i18n/it";
import { entrancesReady, type DemoStatus, type ServiceState } from "@/lib/api/status";

const STATE_COLOR: Record<ServiceState, string> = {
  UP: "var(--color-state-up)",
  WAKING: "var(--color-state-waking)",
  DOWN: "var(--color-state-down)",
  SLEEPING: "var(--color-state-sleeping)",
};

async function fetchStatus(): Promise<DemoStatus> {
  const res = await fetch("/api/demo/status", { cache: "no-store" });
  if (!res.ok) throw new Error("status non disponibile");
  return (await res.json()) as DemoStatus;
}

interface Tile {
  key: string;
  label: string;
  state: ServiceState;
  latencyMs: number | null;
}

function tilesOf(status: DemoStatus): Tile[] {
  const svc = status.services.map((s) => ({ key: s.code, label: s.name, state: s.state, latencyMs: s.latencyMs }));
  return [
    ...svc,
    { key: "kafka", label: "Kafka", state: status.kafka.state, latencyMs: null },
    { key: "db", label: "Postgres", state: status.db.state, latencyMs: null },
  ];
}

function StateTile({ tile, waking }: { tile: Tile; waking: boolean }) {
  const state: ServiceState = waking && tile.state !== "UP" ? "WAKING" : tile.state;
  return (
    <div className="flex items-center justify-between rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-3 py-2.5">
      <div className="flex items-center gap-2">
        <span
          className={cn("inline-block h-2.5 w-2.5 rounded-full", state === "WAKING" && "animate-pulse")}
          style={{ background: STATE_COLOR[state] }}
          aria-hidden
        />
        <span className="text-sm font-medium">{tile.label}</span>
      </div>
      <span className="tabular text-xs text-[var(--color-bo-ink-2)]">
        {state === "UP" && tile.latencyMs != null ? `${tile.latencyMs} ms` : it.states[state]}
      </span>
    </div>
  );
}

export function StatusPanel() {
  const [waking, setWaking] = useState(false);
  const kafkaDownSince = useRef<number | null>(null);

  const query = useQuery({
    queryKey: ["demo-status"],
    queryFn: fetchStatus,
    refetchInterval: waking ? 3000 : 8000,
  });

  const status = query.data;

  // Ferma lo stato "waking" quando tutto è pronto.
  useEffect(() => {
    if (status && status.readyCount >= status.totalCount) setWaking(false);
  }, [status]);

  // Traccia da quanto Kafka è giù (avviso oltre 2 minuti).
  useEffect(() => {
    if (!status) return;
    if (status.kafka.state === "UP") kafkaDownSince.current = null;
    else if (kafkaDownSince.current == null) kafkaDownSince.current = Date.now();
  }, [status]);

  async function wake() {
    setWaking(true);
    try {
      await fetch("/api/demo/wake", { method: "POST" });
    } catch {
      /* ignora: è fire-and-forget */
    }
    query.refetch();
  }

  const kafkaLongDown =
    waking && kafkaDownSince.current != null && Date.now() - kafkaDownSince.current > 120_000;

  return (
    <Card>
      <CardBody className="pt-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold">{it.hub.statusTitle}</h2>
          <Button onClick={wake} disabled={waking}>
            {waking ? <Loader2 className="h-4 w-4 animate-spin" /> : <Power className="h-4 w-4" />}
            {waking ? it.hub.waking : it.hub.wake}
          </Button>
        </div>

        {query.isLoading && (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-hidden>
            {Array.from({ length: 10 }).map((_, i) => (
              <div key={i} className="h-11 animate-pulse rounded-md bg-[var(--color-bo-bg)]" />
            ))}
          </div>
        )}

        {query.isError && (
          <div className="flex items-center justify-between rounded-md border border-[var(--color-state-down)]/30 bg-[var(--color-state-down)]/5 px-3 py-2 text-sm">
            <span>Impossibile leggere lo stato.</span>
            <Button variant="ghost" onClick={() => query.refetch()}>
              <RefreshCw className="h-4 w-4" /> Riprova
            </Button>
          </div>
        )}

        {status && (
          <>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {tilesOf(status).map((tile) => (
                <StateTile key={tile.key} tile={tile} waking={waking} />
              ))}
            </div>

            <div className="mt-4">
              <div className="mb-1 flex items-center justify-between text-sm text-[var(--color-bo-ink-2)]">
                <span>{it.hub.ready(status.readyCount, status.totalCount)}</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-[var(--color-bo-bg)]">
                <div
                  className="h-full rounded-full bg-[var(--color-state-up)] transition-all"
                  style={{ width: `${(status.readyCount / status.totalCount) * 100}%` }}
                />
              </div>
            </div>

            {waking && (
              <p className="mt-3 text-sm text-[var(--color-bo-ink-2)]">{it.hub.firstStart}</p>
            )}
            {kafkaLongDown && (
              <p className="mt-3 rounded-md border border-[var(--color-state-waking)]/40 bg-[var(--color-state-waking)]/10 px-3 py-2 text-sm">
                {it.hub.kafkaDown}
              </p>
            )}

            {!entrancesReady(status) && (
              <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">{it.hub.entrancesLocked}</p>
            )}
          </>
        )}
      </CardBody>
    </Card>
  );
}
