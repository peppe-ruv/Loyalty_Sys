"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import type { Currency } from "@/lib/api/types";
import { it } from "@/lib/i18n/it";
import { formatDateTime } from "@/lib/format/dates";
import { formatSeconds, formatSecondsShort } from "@/lib/format/duration";
import { formatPoints } from "@/lib/format/points";
import { eventLabel } from "@/lib/observe/eventLabels";
import { buildStory, countFacts, hasFailedStep, statusLabel, storySentence, type StoryStep, type TraceStory } from "@/lib/observe/traceStory";
import type { CampaignResult, EventDetail, Trace, TraceNode } from "@/lib/observe/trace";
import { CodeText, DegradedBox } from "@/components/bo/primitives";
import { JsonViewer } from "@/components/bo/JsonViewer";
import { TraceWaterfall } from "./TraceWaterfall";

// Dettaglio di BO-25 per chi non è tecnico (docs/08 §BO-25, issue #204): sintesi, riquadri KPI, «Passo per passo»,
// «Perché questi punti» e, ripiegato, il «Dettaglio tecnico (per IT)».

const T = it.traces;

const STATUS_CHIP: Record<string, string> = {
  COMPLETE: "bg-emerald-100 text-emerald-900",
  IN_PROGRESS: "bg-amber-100 text-amber-900",
  FAILED: "bg-red-100 text-red-900",
};

/** Chip di stato: sempre su una riga, mai troncata (testo + colore, docs/07 §6). */
export function TraceStatusChip({ status }: { status: string }) {
  return (
    <span
      data-testid="trace-status"
      className={`inline-flex shrink-0 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-semibold ${STATUS_CHIP[status] ?? "bg-slate-200 text-slate-800"}`}
    >
      {statusLabel(status)}
    </span>
  );
}

/** Nomi delle valute da Valute ed edizioni (wallet); se il servizio non risponde, il ripiego del dizionario. */
export function useCurrencyName(): (code: string) => string {
  const currencies = useLhQuery<Currency[]>("wallet", "/v1/currencies");
  return (code: string) => currencies.data?.find((c) => c.code === code)?.name ?? T.currencies[code] ?? code;
}

export function dlqHref(eventId: string): string {
  return `/backoffice/observe/dlq?status=ALL&e=${encodeURIComponent(eventId)}`;
}

export function TraceStoryView({ trace, memberName }: { trace: Trace; memberName?: string | null }) {
  const currencyName = useCurrencyName();
  const story = buildStory(trace, { currencyName });
  const timeText =
    trace.status === "FAILED"
      ? T.detail.stoppedAfter(formatSeconds(trace.durationMs))
      : trace.status === "IN_PROGRESS"
        ? T.detail.runningFor(formatSeconds(trace.durationMs))
        : T.detail.processedIn(formatSeconds(trace.durationMs));
  const who = memberName || trace.memberId || T.detail.noMember;

  return (
    <div className="space-y-4">
      <section className="space-y-2 rounded-md border border-[var(--color-bo-border)] bg-white p-4">
        <div className="flex flex-wrap items-center gap-2">
          <TraceStatusChip status={trace.status} />
          <span className="text-xs text-[var(--color-bo-ink-2)]">
            {trace.startedAt ? `${formatDateTime(trace.startedAt)} · ` : ""}
            {timeText}
          </span>
        </div>
        <h2 className="text-lg font-semibold text-[var(--color-bo-ink)]">
          {trace.memberId ? (
            <Link href={`/backoffice/members/${trace.memberId}`} className="underline">
              {who}
            </Link>
          ) : (
            who
          )}{" "}
          {storySentence(story.root)}
        </h2>
        {trace.memberId ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            {T.detail.memberLine} <span className="font-mono">{trace.memberId}</span> ·{" "}
            <Link href={`/backoffice/members/${trace.memberId}`} className="text-[var(--color-bo-accent)] underline">
              {T.detail.open360}
            </Link>
          </p>
        ) : null}
      </section>

      <Kpis trace={trace} currencyName={currencyName} />
      <Steps story={story} status={trace.status} />
      <WhyPanel story={story} currencyName={currencyName} />
      <TechDetails trace={trace} />
    </div>
  );
}

function Kpis({ trace, currencyName }: { trace: Trace; currencyName: (c: string) => string }) {
  const tier = trace.outcome.tierChange?.to;
  const tiles: { label: string; value: string; good?: boolean }[] = [
    ...trace.outcome.points.map((p) => ({
      label: currencyName(p.currency),
      value: `${p.amount > 0 ? "+" : ""}${formatPoints(p.amount)}`,
      good: p.amount > 0,
    })),
    { label: T.kpi.tier, value: tier ? `→ ${tier}` : T.kpi.tierSame },
    { label: T.kpi.badges, value: String(countFacts(trace, "badge.awarded")) },
    { label: T.kpi.goals, value: String(countFacts(trace, "achievement.completed")) },
    { label: T.kpi.messages, value: String(trace.outcome.messages) },
    { label: T.kpi.coupons, value: String(trace.outcome.coupons) },
    { label: T.kpi.plays, value: String(trace.outcome.plays) },
  ];
  return (
    <dl className="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-2">
      {tiles.map((t) => (
        <div key={t.label} className="rounded-md border border-[var(--color-bo-border)] bg-white px-3 py-2">
          <dt className="text-xs text-[var(--color-bo-ink-2)]">{t.label}</dt>
          <dd className={`font-mono text-lg tabular-nums ${t.good ? "text-emerald-800" : "text-[var(--color-bo-ink)]"}`}>{t.value}</dd>
        </div>
      ))}
    </dl>
  );
}

function Steps({ story, status }: { story: TraceStory; status: string }) {
  // «Non partito» solo per le fasi vuote DOPO quella del primo passo bloccato.
  const firstFailed = story.phases.findIndex((p) => hasFailedStep(p.steps));
  return (
    <section className="rounded-md border border-[var(--color-bo-border)] bg-white p-4">
      <h3 className="mb-3 text-base font-semibold">{T.steps.title}</h3>
      <ol className="space-y-0">
        {story.phases.map((phase, i) => {
          const phaseFailed = hasFailedStep(phase.steps);
          return (
          <li key={phase.id} className="grid grid-cols-[1.75rem_minmax(0,1fr)] gap-x-3">
            <span className="flex flex-col items-center">
              <span
                aria-hidden
                className={`flex size-7 items-center justify-center rounded-full text-sm font-bold text-white ${
                  phaseFailed ? "bg-red-700" : "bg-[var(--color-bo-accent)]"
                }`}
              >
                {phaseFailed ? "!" : i + 1}
              </span>
              {i < story.phases.length - 1 ? <span className="my-1 w-0.5 flex-1 bg-[var(--color-bo-border)]" /> : null}
            </span>
            <div className="pb-4">
              <p className="mt-0.5 font-medium">{T.steps.phases[phase.id]}</p>
              {phase.steps.length === 0 ? (
                <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{status === "FAILED" && firstFailed >= 0 && i > firstFailed ? T.steps.notStarted : T.steps.none}</p>
              ) : (
                <ul className="mt-1.5 space-y-1.5">
                  {phase.steps.map((s) => (
                    <StepRow key={s.key} step={s} />
                  ))}
                </ul>
              )}
            </div>
          </li>
          );
        })}
      </ol>
    </section>
  );
}

function StepText({ step }: { step: StoryStep }) {
  return (
    <span className="flex justify-between gap-3">
      <span>
        {step.label}
        {step.count > 1 ? ` ${T.steps.times(step.count)}` : ""}
        {step.detail ? <span className="text-[var(--color-bo-ink-2)]"> · {step.detail}</span> : null}
      </span>
      <span className="shrink-0 font-mono text-xs text-[var(--color-bo-ink-2)]">{formatSecondsShort(step.offsetMs)}</span>
    </span>
  );
}

function StepRow({ step }: { step: StoryStep }) {
  if (step.failed) {
    return (
      <li data-testid="trace-step-failed" className="space-y-1 rounded border-2 border-red-700 bg-red-50 px-3 py-2 text-sm text-red-900">
        <p className="font-semibold">
          {T.steps.blockedTitle} · {T.steps.blockedHere}
        </p>
        <StepText step={step} />
        <p className="text-xs">{T.steps.blockedHint}</p>
        <Link href={dlqHref(step.eventIds[0])} className="inline-block font-semibold underline">
          {T.steps.openDlq}
        </Link>
      </li>
    );
  }
  return (
    <li className="rounded bg-[var(--color-bo-bg)] px-3 py-1.5 text-sm">
      <StepText step={step} />
      {step.triggered.length > 0 ? (
        <div className="mt-2 space-y-1 rounded border border-dashed border-[var(--color-bo-ink-2)] bg-white px-3 py-2">
          <p className="text-xs font-semibold text-[var(--color-bo-ink-2)]">{T.steps.triggered}</p>
          <ul className="space-y-1">
            {step.triggered.map((s) => (
              <StepRow key={s.key} step={s} />
            ))}
          </ul>
        </div>
      ) : null}
    </li>
  );
}

function WhyPanel({ story, currencyName }: { story: TraceStory; currencyName: (c: string) => string }) {
  return (
    <section className="rounded-md border border-[var(--color-bo-border)] bg-white p-4">
      <h3 className="text-base font-semibold">{T.why.title}</h3>
      <p className="mb-3 text-xs text-[var(--color-bo-ink-2)]">{T.why.hint}</p>
      {story.actions.length === 0 ? (
        <p className="text-sm text-[var(--color-bo-ink-2)]">{T.why.noAction}</p>
      ) : (
        <div className="space-y-4">
          {story.actions.map((a, i) => (
            <WhyTable key={a.eventId} action={a} nested={i > 0} currencyName={currencyName} />
          ))}
        </div>
      )}
    </section>
  );
}

function WhyTable({ action, nested, currencyName }: { action: TraceNode; nested: boolean; currencyName: (c: string) => string }) {
  const query = useLhQuery<CampaignResult[]>("campaign", `/v1/evaluations/${encodeURIComponent(action.eventId)}`);
  const caption = nested ? T.why.triggeredBy(eventLabel(action.family, action.shortType)) : null;
  let body: React.ReactNode;
  if (query.isLoading) {
    body = <div className="h-16 animate-pulse rounded bg-slate-100" aria-busy="true" />;
  } else if (query.isError) {
    body =
      query.error.status === 404 ? (
        <p className="text-sm text-[var(--color-bo-ink-2)]">{T.why.notFound}</p>
      ) : (
        <DegradedBox service="campaign" onRetry={() => void query.refetch()} autoRetry={query.error.asleep} />
      );
  } else if (!query.data || query.data.length === 0) {
    body = <p className="text-sm text-[var(--color-bo-ink-2)]">{T.why.none}</p>;
  } else {
    body = (
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-[var(--color-bo-bg)] text-left text-xs text-[var(--color-bo-ink-2)]">
            <tr>
              <th scope="col" className="px-2 py-1.5">{T.why.campaign}</th>
              <th scope="col" className="px-2 py-1.5">{T.why.outcome}</th>
              <th scope="col" className="px-2 py-1.5">{T.why.reason}</th>
              <th scope="col" className="px-2 py-1.5 text-right">{T.why.granted}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--color-bo-border)]">
            {query.data.map((r) => (
              <tr key={r.campaignCode}>
                <td className="px-2 py-1.5 font-medium">{r.campaignName || r.campaignCode}</td>
                <td className="px-2 py-1.5">
                  <span
                    className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ${
                      r.matched ? "bg-emerald-100 text-emerald-900" : "bg-slate-200 text-slate-800"
                    }`}
                  >
                    {r.matched ? T.why.applied : T.why.notApplied}
                  </span>
                </td>
                <td className="px-2 py-1.5 text-[var(--color-bo-ink-2)]">{reasonText(r)}</td>
                <td className="whitespace-nowrap px-2 py-1.5 text-right font-mono text-xs tabular-nums">{grantedText(r, currencyName)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <div data-testid="trace-why">
      {caption ? <p className="mb-1 text-xs font-semibold text-[var(--color-bo-ink-2)]">{caption}</p> : null}
      {body}
    </div>
  );
}

function reasonText(r: CampaignResult): string {
  if (r.matched) return T.why.matched;
  const base = (r.reason && T.why.reasons[r.reason]) || r.reason || "—";
  const failed = r.failedConditions?.length ?? 0;
  return r.reason === "CONDITION" && failed > 0 ? `${base}: ${T.why.failedConditions(failed)}` : base;
}

function grantedText(r: CampaignResult, currencyName: (c: string) => string): string {
  const parts = (r.effects ?? [])
    .filter((e) => typeof e.amount === "number")
    .map((e) => `${(e.amount ?? 0) > 0 ? "+" : ""}${formatPoints(e.amount ?? 0)} ${currencyName(e.currency ?? "PTS")}`);
  return parts.length > 0 ? parts.join(" · ") : "—";
}

function TechDetails({ trace }: { trace: Trace }) {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<TraceNode | null>(null);
  const [copied, setCopied] = useState(false);
  const copy = () =>
    void navigator.clipboard?.writeText(trace.correlationId).then(
      () => setCopied(true),
      () => undefined,
    );
  return (
    <section className="rounded-md border border-[var(--color-bo-border)] bg-white">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
      >
        <span className="text-base font-semibold">{T.tech.title}</span>
        <span className="text-xs text-[var(--color-bo-ink-2)]">{open ? T.tech.close : T.tech.open}</span>
      </button>
      {open ? (
        <div className="space-y-3 border-t border-[var(--color-bo-border)] p-4">
          <p className="flex flex-wrap items-center gap-2 text-xs text-[var(--color-bo-ink-2)]">
            {T.tech.correlation} <CodeText>{trace.correlationId}</CodeText>
            <button type="button" onClick={copy} className="rounded border border-[var(--color-bo-ink-2)] px-2 py-0.5">
              {copied ? T.tech.copied : T.tech.copy}
            </button>
          </p>
          <TraceWaterfall trace={trace} selectedId={selected?.eventId} onSelect={setSelected} />
          {selected ? <NodePayload node={selected} /> : <p className="text-xs text-[var(--color-bo-ink-2)]">{T.tech.pick}</p>}
        </div>
      ) : null}
    </section>
  );
}

function NodePayload({ node }: { node: TraceNode }) {
  // Le voci DLQ hanno l'id della voce, non di un evento: il contenuto si apre in BO-27.
  const isDlq = node.family === "DLQ";
  const detail = useLhQuery<EventDetail>("insight", `/v1/events/${encodeURIComponent(node.eventId)}`, undefined, { enabled: !isDlq });
  return (
    <div data-testid="trace-payload" className="space-y-2 rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] p-3">
      <p className="text-sm font-semibold">{eventLabel(node.family, node.shortType)}</p>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs">
        <dt className="text-[var(--color-bo-ink-2)]">{T.tech.service}</dt>
        <dd className="font-mono">{node.service}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">{T.tech.eventId}</dt>
        <dd className="break-all font-mono">{node.eventId}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">{T.tech.summary}</dt>
        <dd className="font-mono">
          {node.shortType} · {node.summary}
        </dd>
      </dl>
      {isDlq ? (
        <Link href={dlqHref(node.eventId)} className="text-xs font-semibold text-red-800 underline">
          {T.steps.openDlq}
        </Link>
      ) : detail.isLoading ? (
        <div className="h-20 animate-pulse rounded bg-slate-100" aria-busy="true" />
      ) : detail.isError ? (
        <DegradedBox service="insight" onRetry={() => void detail.refetch()} autoRetry={detail.error.asleep} />
      ) : (
        <JsonViewer value={detail.data?.payload ?? detail.data} label={T.tech.payload} />
      )}
    </div>
  );
}
