"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhQuery, useLhMutation } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";
import type { Scenario, ScenarioRun, ScenarioStepResult } from "@/lib/api/types";

// BO-29 — Scenari (docs/08 §BO-29, F-DEMO-04): schede scenario + vista di esecuzione passo per passo con
// stato (in attesa → in corso → elaborato), TraceLink per passo, suggerimento "apri il portale come…".
// Uno scenario alla volta. Dati: ingestion GET /v1/demo/scenarios, POST …/run, GET /v1/demo/scenario-runs/{id}.

const STATUS_TONE: Record<string, string> = {
  ACCEPTED: "bg-emerald-100 text-emerald-800",
  UNMATCHED: "bg-amber-100 text-amber-800",
  REJECTED: "bg-red-100 text-red-800",
  DUPLICATE: "bg-slate-200 text-slate-700",
};

export default function ScenariosPage() {
  const [runId, setRunId] = useState<string | null>(null);
  const [activeCode, setActiveCode] = useState<string | null>(null);
  const scenarios = useLhQuery<Scenario[]>("ingestion", "/v1/demo/scenarios");

  const runMutation = useLhMutation<{ runId: string }, { code: string }>(
    "ingestion",
    "POST",
    (b) => `/v1/demo/scenarios/${b.code}/run`,
    { invalidate: false, onSuccess: (r) => setRunId(r.runId) },
  );

  const runQuery = useLhQuery<ScenarioRun>("ingestion", runId ? `/v1/demo/scenario-runs/${runId}` : "/v1/demo/scenarios", undefined, {
    enabled: runId != null,
    refetchInterval: 900,
  });
  const run = runId ? runQuery.data : undefined;
  const running = run?.status === "RUNNING" || runMutation.isPending;

  function start(code: string) {
    setActiveCode(code);
    setRunId(null);
    runMutation.mutate({ code });
  }

  return (
    <div>
      <PageHeader
        title="Scenari"
        subtitle="Storie eseguibili che inviano azioni vere nella pipeline. Uno scenario alla volta."
      />

      <QueryState query={scenarios} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessuno scenario">
        {(list) => {
          const active = list.find((s) => s.code === activeCode) ?? null;
          return (
            <div className="grid gap-4 lg:grid-cols-[1fr_1fr]">
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">
                {list.map((s) => (
                  <ScenarioCard
                    key={s.code}
                    scenario={s}
                    active={s.code === activeCode}
                    disabled={running}
                    onRun={() => start(s.code)}
                  />
                ))}
              </div>

              <div>
                {active == null ? (
                  <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-6 text-center text-sm text-[var(--color-bo-ink-2)]">
                    Scegli uno scenario e premi «Esegui» per vederlo scorrere passo per passo.
                  </div>
                ) : (
                  <ExecutionPanel scenario={active} run={run} pending={runMutation.isPending} error={runMutation.isError} />
                )}
              </div>
            </div>
          );
        }}
      </QueryState>
    </div>
  );
}

function ScenarioCard({
  scenario,
  active,
  disabled,
  onRun,
}: {
  scenario: Scenario;
  active: boolean;
  disabled: boolean;
  onRun: () => void;
}) {
  const durationS = Math.round(scenario.steps.reduce((a, s) => a + (s.delayMs || 0), 0) / 1000);
  return (
    <div className={`rounded-lg border bg-white p-4 ${active ? "border-[var(--color-bo-accent)]" : "border-[var(--color-bo-border)]"}`}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="font-medium text-[var(--color-bo-ink)]">{scenario.name}</p>
          {scenario.protagonist ? <p className="text-xs text-[var(--color-bo-ink-2)]">{scenario.protagonist}</p> : null}
        </div>
        <CodeText>{scenario.code}</CodeText>
      </div>
      {scenario.description ? <p className="mt-2 text-sm text-[var(--color-bo-ink-2)]">{scenario.description}</p> : null}
      <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-[var(--color-bo-ink-2)]">
        <span>{scenario.steps.length} passi</span>
        <span>~{durationS}s</span>
      </div>
      {scenario.watch ? (
        <p className="mt-2 rounded bg-[var(--color-bo-bg)] px-2 py-1 text-[11px] text-[var(--color-bo-ink-2)]">
          👀 {scenario.watch}
        </p>
      ) : null}
      <div className="mt-3">
        <Can capability="demo.simulate" mode="disable">
          <button
            onClick={onRun}
            disabled={disabled}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            Esegui
          </button>
        </Can>
      </div>
    </div>
  );
}

function ExecutionPanel({
  scenario,
  run,
  pending,
  error,
}: {
  scenario: Scenario;
  run: ScenarioRun | undefined;
  pending: boolean;
  error: boolean;
}) {
  const byIndex = new Map<number, ScenarioStepResult>();
  (run?.results ?? []).forEach((r) => byIndex.set(r.index, r));
  const done = run?.status === "DONE" || run?.status === "FAILED";

  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-[var(--color-bo-ink)]">{scenario.name}</h2>
        {run ? (
          <span className="text-xs text-[var(--color-bo-ink-2)]">
            {run.stepsDone}/{run.stepsTotal} · {run.status === "RUNNING" ? "in corso…" : run.status.toLowerCase()}
          </span>
        ) : pending ? (
          <span className="text-xs text-[var(--color-bo-ink-2)]">avvio…</span>
        ) : null}
      </div>

      {error ? (
        <p className="mb-2 rounded bg-red-50 px-2 py-1 text-xs text-red-700">
          Avvio non riuscito: serve un ruolo che può simulare.
        </p>
      ) : null}

      <ol className="space-y-2">
        {scenario.steps.map((step, i) => {
          const result = byIndex.get(i);
          const state = result ? "done" : run && i === run.stepsDone && run.status === "RUNNING" ? "running" : "pending";
          return (
            <li key={i} className="flex items-start gap-3 rounded-md border border-[var(--color-bo-border)] p-2.5">
              <StepDot state={state} ok={result?.ok} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm text-[var(--color-bo-ink)]">{step.note || step.type}</span>
                  {result ? (
                    <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${STATUS_TONE[result.status] ?? "bg-slate-100"}`}>
                      {result.status}
                    </span>
                  ) : (
                    <span className="text-[10px] text-[var(--color-bo-ink-2)]">
                      {state === "running" ? "in corso…" : "in attesa"}
                    </span>
                  )}
                </div>
                <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-[var(--color-bo-ink-2)]">
                  <span className="font-mono">{step.type}</span>
                  <span>{step.memberId}</span>
                  {result?.expected ? <span>atteso: {result.expected}</span> : null}
                  {result?.rejectCode ? <span>{result.rejectCode}</span> : null}
                  {result?.correlationId ? (
                    <Link href={`/backoffice/observe/traces?c=${result.correlationId}`} className="text-[var(--color-bo-accent)] hover:underline">
                      Tracciato →
                    </Link>
                  ) : null}
                </div>
              </div>
            </li>
          );
        })}
      </ol>

      {done ? (
        <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">
          Esecuzione completata. Suggerimento: apri il portale come {scenario.protagonist ?? "il protagonista"} per
          vederlo dal suo lato.
        </p>
      ) : null}
    </div>
  );
}

function StepDot({ state, ok }: { state: "pending" | "running" | "done"; ok?: boolean }) {
  if (state === "done") {
    return (
      <span
        className={`mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[10px] text-white ${ok ? "bg-emerald-600" : "bg-amber-600"}`}
        aria-hidden
      >
        {ok ? "✓" : "!"}
      </span>
    );
  }
  if (state === "running") {
    return <span className="mt-0.5 inline-block h-4 w-4 shrink-0 animate-pulse rounded-full bg-[var(--color-bo-accent)]" aria-hidden />;
  }
  return <span className="mt-0.5 inline-block h-4 w-4 shrink-0 rounded-full border border-[var(--color-bo-border)]" aria-hidden />;
}
