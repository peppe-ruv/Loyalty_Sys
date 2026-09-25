"use client";

import { useEffect, useState } from "react";
import { ExternalLink, Loader2, Power, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardBody } from "@/components/ui/card";
import { useKeepAliveIdle } from "@/components/shared/KeepAlive";
import { cn } from "@/lib/cn";
import { it } from "@/lib/i18n/it";
import { KAFKA_RESTART_DOCS_URL } from "@/lib/hub/links";
import {
  displayState,
  entrancesReady,
  formatElapsed,
  kafkaLongDown,
  statusPollInterval,
  trackKafkaDown,
  wakeComplete,
  type DemoStatus,
  type ServiceState,
} from "@/lib/api/status";
import { useDemoStatus } from "./useDemoStatus";

// HUB-01 — pannello stato (docs/07 §8): 10 tessere, "Accendi la demo", barra "pronti N/10" e tempo trascorso,
// riquadro Kafka spento da oltre 2 minuti.

const STATE_COLOR: Record<ServiceState, string> = {
  UP: "var(--color-state-up)",
  WAKING: "var(--color-state-waking)",
  DOWN: "var(--color-state-down)",
  SLEEPING: "var(--color-state-sleeping)",
};

interface Tile {
  key: string;
  label: string;
  state: ServiceState;
  latencyMs: number | null;
  version?: string;
}

function tilesOf(status: DemoStatus): Tile[] {
  const svc = status.services.map((s) => ({
    key: s.code,
    label: s.name,
    state: s.state,
    latencyMs: s.latencyMs,
    version: s.version,
  }));
  return [
    ...svc,
    { key: "kafka", label: "Kafka", state: status.kafka.state, latencyMs: null },
    { key: "db", label: "Postgres", state: status.db.state, latencyMs: null },
  ];
}

function StateTile({ tile, waking }: { tile: Tile; waking: boolean }) {
  const state = displayState(tile.state, waking);
  return (
    <div
      className="flex items-center justify-between rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-3 py-2.5"
      data-testid={`tile-${tile.key}`}
      data-state={state}
    >
      <div className="flex min-w-0 items-center gap-2">
        <span
          className={cn("inline-block h-2.5 w-2.5 shrink-0 rounded-full", state === "WAKING" && "animate-pulse")}
          style={{ background: STATE_COLOR[state] }}
          aria-hidden
        />
        <span className="truncate text-sm font-medium">{tile.label}</span>
        {tile.version ? (
          <span className="font-mono text-[10px] text-[var(--color-bo-ink-2)]">v{tile.version}</span>
        ) : null}
      </div>
      <span className="tabular shrink-0 text-xs text-[var(--color-bo-ink-2)]">
        {state === "UP" && tile.latencyMs != null ? `${tile.latencyMs} ms` : it.states[state]}
      </span>
    </div>
  );
}

function TilesSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5" aria-hidden>
      {Array.from({ length: 10 }).map((_, i) => (
        <div key={i} className="h-11 animate-pulse rounded-md bg-[var(--color-bo-bg)]" />
      ))}
    </div>
  );
}

export function StatusPanel() {
  const idle = useKeepAliveIdle();
  const [wakeStartedAt, setWakeStartedAt] = useState<number | null>(null);
  const [wakeDoneAt, setWakeDoneAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [kafkaDownSince, setKafkaDownSince] = useState<number | null>(null);
  const waking = wakeStartedAt !== null && wakeDoneAt === null;

  const query = useDemoStatus(statusPollInterval({ waking, idle }));
  const status = query.data;

  // Ogni nuovo stato: traccia Kafka DOWN e chiude il risveglio a 10/10.
  useEffect(() => {
    if (!status) return;
    setKafkaDownSince((prev) => trackKafkaDown(prev, status));
    if (waking && wakeComplete(status)) setWakeDoneAt(Date.now());
  }, [status, waking]);

  // Orologio del tempo trascorso, solo durante il risveglio.
  useEffect(() => {
    if (!waking) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [waking]);

  async function wake() {
    const t = Date.now();
    setWakeStartedAt(t);
    setWakeDoneAt(null);
    setNow(t);
    try {
      await fetch("/api/demo/wake", { method: "POST" });
    } catch {
      /* fire-and-forget: lo stato lo dice il polling */
    }
    void query.refetch();
  }

  const elapsed =
    wakeStartedAt === null ? null : formatElapsed((wakeDoneAt ?? Math.max(now, wakeStartedAt)) - wakeStartedAt);

  return (
    <Card>
      <CardBody className="pt-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold">{it.hub.statusTitle}</h2>
          <Button onClick={wake} disabled={waking}>
            {waking ? <Loader2 className="h-4 w-4 animate-spin" /> : <Power className="h-4 w-4" />}
            {waking ? it.hub.waking : it.hub.wake}
          </Button>
        </div>

        {query.isLoading && <TilesSkeleton />}

        {query.isError && !status && (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-[var(--color-state-down)]/30 bg-[var(--color-state-down)]/5 px-3 py-2 text-sm"
          >
            <span>
              <span className="font-medium">{it.hub.statusError}</span>{" "}
              <span className="text-xs text-[var(--color-bo-ink-2)]">{query.error.message}</span>
            </span>
            <Button variant="ghost" onClick={() => query.refetch()}>
              <RefreshCw className="h-4 w-4" /> {it.hub.retry}
            </Button>
          </div>
        )}

        {status && (
          <>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
              {tilesOf(status).map((tile) => (
                <StateTile key={tile.key} tile={tile} waking={waking} />
              ))}
            </div>

            <div className="mt-4">
              <div className="mb-1 flex items-center justify-between text-sm text-[var(--color-bo-ink-2)]">
                <span>{it.hub.ready(status.readyCount, status.totalCount)}</span>
                {elapsed !== null ? (
                  <span className="tabular" data-testid="elapsed">
                    {wakeDoneAt !== null ? it.hub.readyIn(elapsed) : it.hub.elapsed(elapsed)}
                  </span>
                ) : null}
              </div>
              <div
                className="h-2 w-full overflow-hidden rounded-full bg-[var(--color-bo-bg)]"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={status.totalCount}
                aria-valuenow={status.readyCount}
                aria-label={it.hub.ready(status.readyCount, status.totalCount)}
              >
                <div
                  className="h-full rounded-full bg-[var(--color-state-up)] transition-all"
                  style={{ width: `${(status.readyCount / Math.max(1, status.totalCount)) * 100}%` }}
                />
              </div>
            </div>

            {waking && <p className="mt-3 text-sm text-[var(--color-bo-ink-2)]">{it.hub.firstStart}</p>}

            {kafkaLongDown(kafkaDownSince, status) && (
              <div
                role="alert"
                className="mt-3 rounded-md border border-[var(--color-state-waking)]/40 bg-[var(--color-state-waking)]/10 px-3 py-2 text-sm"
              >
                <p>{it.hub.kafkaDown}</p>
                <a
                  href={KAFKA_RESTART_DOCS_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="mt-1 inline-flex items-center gap-1 font-medium text-[var(--color-bo-accent)] underline"
                >
                  {it.hub.kafkaDownLink} <ExternalLink className="h-3.5 w-3.5" />
                </a>
              </div>
            )}

            {query.isError && <p className="mt-3 text-xs text-[var(--color-state-waking)]">{it.hub.statusStale}</p>}

            {!entrancesReady(status) && (
              <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">{it.hub.entrancesLocked}</p>
            )}
          </>
        )}

        {idle && <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">{it.hub.idle}</p>}
      </CardBody>
    </Card>
  );
}
