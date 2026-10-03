"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { call, HttpError } from "@/lib/vetrina/client";
import { it as t } from "@/lib/i18n/it";
import { useBoPersona } from "@/components/bo/PersonaContext";
import type { JobView, PreviewView, Scope } from "@/lib/vetrina/programma";

// BO-01 (V10) — «Carica il programma di esempio» (F2-DIST-09, ADR-051, Q-617, Q-722, Q-723). Riquadro in cima alla
// Dashboard, solo per ADMIN nell'ambiente di test dichiarato (il layout decide con `sampleProgram`). Parla solo con
// /api/vetrina/programma: anteprima (GET), avvio (POST con CSRF) e avanzamento (GET ?job=, ogni 1,5 s: niente SSE, Q-622).
// Il browser non vede mai token. Importa SOLO i tipi del modulo server (`import type`).

const s = t.sampleProgram;
const POLL_MS = 1500;
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

/** Il riquadro, solo se il layout lo ha abilitato (ADMIN, enterprise, ambiente di test). */
export function SampleProgramBox() {
  const persona = useBoPersona();
  if (!persona.sampleProgram) return null;
  return <Box />;
}

const PREVIEW_KEY = ["vetrina", "programma"] as const;

function Box() {
  const qc = useQueryClient();
  const [dialog, setDialog] = useState<Scope | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);

  const preview = useQuery<PreviewView, HttpError>({
    queryKey: PREVIEW_KEY,
    queryFn: () => call<PreviewView>("/api/vetrina/programma"),
    retry: false,
    refetchOnWindowFocus: false,
  });

  // Un lavoro già in corso (pagina ricaricata): lo si riprende.
  const runningId = preview.data?.job && preview.data.job.actor === preview.data.summary.actor ? preview.data.job.id : null;
  useEffect(() => {
    if (runningId && jobId === null) setJobId(runningId);
  }, [runningId, jobId]);

  const job = useQuery<JobView, HttpError>({
    queryKey: [...PREVIEW_KEY, "job", jobId],
    queryFn: () => call<JobView>(`/api/vetrina/programma?job=${encodeURIComponent(jobId ?? "")}`),
    enabled: jobId !== null,
    retry: false,
    refetchOnWindowFocus: false,
    // Si ferma se la lettura è in errore: niente raffiche, l'operatore sceglie «Controlla di nuovo».
    refetchInterval: (q) => (q.state.status === "error" ? false : q.state.data?.status === "running" || q.state.data === undefined ? POLL_MS : false),
  });

  // Finito (bene o male): l'anteprima si rilegge, così il riquadro riflette lo stato vero.
  const finished = job.data && job.data.status !== "running" ? job.data.id : null;
  const refreshedFor = useRef<string | null>(null);
  useEffect(() => {
    if (finished && refreshedFor.current !== finished) {
      refreshedFor.current = finished;
      void qc.invalidateQueries({ queryKey: PREVIEW_KEY, exact: true });
    }
  }, [finished, qc]);

  const recheck = () => {
    setJobId(null);
    void qc.invalidateQueries({ queryKey: PREVIEW_KEY, exact: true });
  };

  return (
    <section
      aria-labelledby="sample-program-title"
      data-testid="sample-program"
      className="mb-4 rounded-lg border border-[var(--color-bo-border)] bg-white p-4"
    >
      <p className="mb-1 inline-flex rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-900">{s.badge}</p>
      <Body preview={preview} job={job} jobId={jobId} onRecheck={recheck} onOpen={setDialog} />
      {dialog !== null && preview.data ? (
        <PreviewDialog
          scope={dialog}
          preview={preview.data}
          onClose={() => setDialog(null)}
          onRunningElsewhere={() => {
            setDialog(null);
            recheck();
          }}
          onStarted={(id) => {
            setDialog(null);
            refreshedFor.current = null;
            setJobId(id);
          }}
        />
      ) : null}
    </section>
  );
}

function Body({
  preview, job, jobId, onRecheck, onOpen,
}: {
  preview: UseQueryResult<PreviewView, HttpError>;
  job: UseQueryResult<JobView, HttpError>;
  jobId: string | null;
  onRecheck: () => void;
  onOpen: (scope: Scope) => void;
}) {
  // Un lavoro (in corso o appena finito) ha la precedenza sullo stato del programma.
  if (jobId !== null) {
    if (job.error?.status === 404) return <Notice title={s.errorTitle} text={s.jobLost} action={s.recheck} onAction={onRecheck} />;
    if (job.isError) return <Notice title={s.errorTitle} text={s.jobReadError} action={s.retry} onAction={() => void job.refetch()} testId="sample-job-read-error" secondary={{ label: s.recheck, onClick: onRecheck }} />;
    if (job.data) return <JobPanel job={job.data} onRetry={() => onOpen(job.data.scope)} onRecheck={onRecheck} />;
    return <p role="status" className="text-sm text-[var(--color-bo-ink-2)]">{s.starting}</p>;
  }
  if (preview.isPending) {
    return (
      <p role="status" className="text-sm text-[var(--color-bo-ink-2)]">
        {s.loading}
      </p>
    );
  }
  if (preview.isError) {
    const degraded = preview.error.status === 503;
    return (
      <Notice
        title={degraded ? s.degradedTitle : s.errorTitle}
        text={degraded ? s.degradedText : s.errorText}
        action={s.retry}
        onAction={() => void preview.refetch()}
        testId={degraded ? "sample-degraded" : "sample-error"}
      />
    );
  }
  const p = preview.data;
  switch (p.box) {
    case "pending":
      return (
        <Head title={s.emptyTitle} text={s.emptyText}>
          <Primary onClick={() => onOpen("program")}>{s.load}</Primary>
        </Head>
      );
    case "stories-ready":
      return (
        <Head title={s.storiesReadyTitle} text={p.summary.stories.incompleteJobIds.length ? `${s.storiesReadyText} ${s.storiesIncomplete}` : s.storiesReadyText}>
          <Primary onClick={() => onOpen("stories")}>{s.loadStories}</Primary>
          {p.summary.stories.incompleteJobIds.length ? <Links imports /> : null}
        </Head>
      );
    case "stories-waiting":
      return (
        <Head title={s.storiesWaitingTitle} text={s.storiesWaitingText}>
          <ul className="mb-2 list-disc pl-5 text-sm text-[var(--color-bo-ink-2)]" aria-label={s.waitingLabel}>
            {p.summary.stories.reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
          <Links campaigns catalogue approvals />
          <Secondary onClick={onRecheck}>{s.recheck}</Secondary>
        </Head>
      );
    case "running":
      return (
        <Head title={s.runningTitle} text={s.runningHint}>
          <Secondary onClick={onRecheck}>{s.recheck}</Secondary>
        </Head>
      );
    case "complete":
      return (
        <Head
          title={s.completeTitle}
          text={s.completeText + (p.lastRun ? ` ${s.completeBy(new Date(p.lastRun.finishedAt).toLocaleDateString("it-IT"), p.lastRun.by)}` : "")}
        >
          <Secondary onClick={onRecheck}>{s.recheck}</Secondary>
        </Head>
      );
  }
}

function Head({ title, text, children }: { title: string; text: string; children?: React.ReactNode }) {
  return (
    <div>
      <h2 id="sample-program-title" className="text-sm font-semibold text-[var(--color-bo-ink)]">
        {title}
      </h2>
      <p className="mb-3 mt-1 max-w-3xl text-sm text-[var(--color-bo-ink-2)]">{text}</p>
      <div className="flex flex-wrap items-center gap-3">{children}</div>
    </div>
  );
}

function Notice({ title, text, action, onAction, testId, secondary }: { title: string; text: string; action: string; onAction: () => void; testId?: string; secondary?: { label: string; onClick: () => void } }) {
  return (
    <div role="alert" data-testid={testId}>
      <h2 id="sample-program-title" className="text-sm font-semibold text-[var(--color-bo-ink)]">
        {title}
      </h2>
      <p className="mb-3 mt-1 text-sm text-[var(--color-bo-ink-2)]">{text}</p>
      <div className="flex flex-wrap gap-3">
        <Secondary onClick={onAction}>{action}</Secondary>
        {secondary ? <Secondary onClick={secondary.onClick}>{secondary.label}</Secondary> : null}
      </div>
    </div>
  );
}

const btn = "rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-50";
function Primary({ children, onClick, disabled }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} className={`${btn} bg-[var(--color-bo-accent)] text-white`}>
      {children}
    </button>
  );
}
function Secondary({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={`${btn} border border-[var(--color-bo-border)] text-[var(--color-bo-ink)]`}>
      {children}
    </button>
  );
}

function Links({ approvals = false, campaigns = false, catalogue = false, audit = false, imports = false }: { approvals?: boolean; campaigns?: boolean; catalogue?: boolean; audit?: boolean; imports?: boolean }) {
  const cls = "text-sm underline text-[var(--color-bo-ink)]";
  return (
    <p className="flex flex-wrap gap-x-4 gap-y-1">
      {campaigns ? <Link href="/backoffice/campaigns" className={cls}>{s.linkCampaigns}</Link> : null}
      {approvals ? <Link href="/backoffice/governance/approvals" className={cls}>{s.linkApprovals}</Link> : null}
      {catalogue ? <Link href="/backoffice/rewards" className={cls}>{s.linkCatalogue}</Link> : null}
      {imports ? <Link href="/backoffice/observe/imports" className={cls}>{s.linkImports}</Link> : null}
      {audit ? <Link href="/backoffice/governance/audit" className={cls}>{s.linkAudit}</Link> : null}
    </p>
  );
}

/** Avanzamento ed esito (mockup sezione 3). */
function JobPanel({ job, onRetry, onRecheck }: { job: JobView; onRetry: () => void; onRecheck: () => void }) {
  if (job.status === "running") {
    const pct = job.total > 0 ? Math.min(100, Math.round((job.done / job.total) * 100)) : 0;
    return (
      <div data-testid="sample-running">
        <h2 id="sample-program-title" className="text-sm font-semibold text-[var(--color-bo-ink)]">
          {s.runningTitle}
        </h2>
        <p role="status" className="mt-1 text-sm text-[var(--color-bo-ink-2)]">
          {s.progress(job.done, job.total, job.message)}
        </p>
        <div
          role="progressbar"
          aria-label={s.runningTitle}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
          className="mt-2 h-2 w-full max-w-md overflow-hidden rounded bg-slate-100"
        >
          <div className="h-full bg-[var(--color-bo-accent)]" style={{ width: `${pct}%` }} />
        </div>
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">{s.runningHint}</p>
      </div>
    );
  }
  const r = job.result;
  if (job.status === "done" && r) {
    const stories = job.scope === "stories";
    return (
      <div data-testid="sample-done">
        <h2 id="sample-program-title" className="text-sm font-semibold text-[var(--color-state-up)]">
          {stories ? s.doneStoriesTitle : s.doneTitle}
        </h2>
        <p className="mt-1 text-sm text-[var(--color-bo-ink)]">
          {stories ? s.doneStoriesSummary(r.storyRows, r.auditVerified, r.auditTotal, job.actor) : s.doneSummary(r.created, r.auditVerified, r.auditTotal, job.actor)}
        </p>
        {!stories && (r.rewardsDraft > 0 || r.campaignsDraft > 0) ? (
          <p className="mb-2 mt-1 text-sm text-[var(--color-bo-ink)]">{s.approvalNote(r.rewardsDraft, r.campaignsDraft)}</p>
        ) : null}
        <div className="mt-2 flex flex-wrap items-center gap-4">
          <Links campaigns={!stories} catalogue={!stories} approvals={!stories} imports audit />
          <Secondary onClick={onRecheck}>{s.recheck}</Secondary>
        </div>
      </div>
    );
  }
  // error
  return (
    <div role="alert" data-testid="sample-job-error">
      <h2 id="sample-program-title" className="text-sm font-semibold text-[var(--color-state-down)]">
        {job.message}
      </h2>
      {r && r.problems.length ? (
        <>
          <p className="mt-1 text-sm text-[var(--color-bo-ink-2)]">{s.errorProblems}</p>
          <ul className="mb-3 list-disc pl-5 text-sm text-[var(--color-bo-ink)]">
            {r.problems.slice(0, 8).map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </>
      ) : (
        <p className="mb-3 mt-1 text-sm text-[var(--color-bo-ink-2)]">{s.errorGeneric}</p>
      )}
      <div className="flex flex-wrap gap-3">
        <Primary onClick={onRetry}>{s.retry}</Primary>
        <Secondary onClick={onRecheck}>{s.recheck}</Secondary>
      </div>
    </div>
  );
}

/** Pannello di anteprima (mockup sezione 2): niente si scrive finché non confermi. */
function PreviewDialog({ scope, preview, onClose, onStarted, onRunningElsewhere }: { scope: Scope; preview: PreviewView; onClose: () => void; onStarted: (jobId: string) => void; onRunningElsewhere: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const panel = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  // Come il foglio laterale (components/bo/SideSheet.tsx): focus nel pannello all'apertura, Esc chiude, il focus torna
  // dov'era; Tab resta dentro il pannello.
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        closeRef.current();
        return;
      }
      if (e.key !== "Tab" || !panel.current) return;
      const items = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && (active === first || active === panel.current)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      if (previous && previous !== document.body && previous.isConnected) previous.focus();
    };
  }, []);
  const sum = preview.summary;
  const stories = scope === "stories";
  const writes = stories ? 1 : sum.create;

  async function start() {
    setBusy(true);
    setError(null);
    try {
      const res = await call<{ jobId: string }>("/api/vetrina/programma", { method: "POST", body: JSON.stringify({ scope }) });
      onStarted(res.jobId);
    } catch (e) {
      if (e instanceof HttpError && e.status === 409) {
        // Un lavoro è già in corso (anche di un altro operatore): lo stato di un lavoro lo legge solo chi l'ha avviato,
        // quindi si chiude il pannello e si rilegge l'anteprima, che lo mostra come «in corso».
        onRunningElsewhere();
        return;
      }
      setError(e instanceof HttpError && e.status === 403 ? s.startForbidden : s.startError);
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="sample-preview-title"
        ref={panel}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="max-h-[90dvh] w-full max-w-2xl overflow-y-auto rounded-lg border border-[var(--color-bo-border)] bg-white p-5 shadow-lg"
      >
        <h2 id="sample-preview-title" className="text-base font-semibold text-[var(--color-bo-ink)]">
          {stories ? s.previewTitleStories : s.previewTitle}
        </h2>
        {stories ? (
          <table className="mt-3 w-full text-sm" data-testid="preview-stories">
            <tbody>
              {sum.stories.members.map((m) => (
                <tr key={m.username} className="border-t border-[var(--color-bo-border)]">
                  <td className="py-1.5">{m.name}</td>
                  <td className="py-1.5 text-[var(--color-bo-ink-2)]">{m.state === "ready" ? s.memberRows(m.rows) : s.memberMissing}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <table className="mt-3 w-full text-sm" data-testid="preview-table">
            <thead>
              <tr className="text-left text-xs text-[var(--color-bo-ink-2)]">
                <th className="py-1 font-medium">{s.colWhat}</th>
                <th className="py-1 text-right font-medium">{s.colCreate}</th>
                <th className="py-1 text-right font-medium">{s.colPresent}</th>
              </tr>
            </thead>
            <tbody>
              {sum.groups.map((g) => (
                <tr key={g.id} className="border-t border-[var(--color-bo-border)]">
                  <td className="py-1.5">{g.label}</td>
                  <td className="py-1.5 text-right tabular-nums">{g.create}</td>
                  <td className="py-1.5 text-right tabular-nums">{g.present}</td>
                </tr>
              ))}
              <tr className="border-t border-[var(--color-bo-border)]">
                <td className="py-1.5">{s.storiesRow}</td>
                <td className="py-1.5 text-right text-[var(--color-bo-ink-2)]" colSpan={2}>
                  {sum.stories.state === "ready" ? s.storiesRowImport(sum.stories.rows) : sum.stories.reasons[0] ?? s.storiesRowWaiting}
                </td>
              </tr>
            </tbody>
          </table>
        )}
        {!stories && sum.excluded.length > 0 ? (
          <details className="mt-3 text-sm">
            <summary className="cursor-pointer text-[var(--color-bo-ink)]">{s.excludedTitle(sum.excluded.length)}</summary>
            <ul className="mt-1 list-disc pl-5 text-[var(--color-bo-ink-2)]">
              {sum.excluded.map((x) => (
                <li key={x.what + x.why}>
                  <b className="font-medium text-[var(--color-bo-ink)]">{x.what}</b>: {x.why}
                </li>
              ))}
            </ul>
          </details>
        ) : null}
        <p className="mt-3 text-sm text-[var(--color-bo-ink)]">{stories ? s.writesStories(sum.stories.rows, sum.actor) : s.writes(writes, sum.actor)}</p>
        {error ? (
          <p role="alert" className="mt-2 text-sm text-[var(--color-state-down)]">
            {error}
          </p>
        ) : null}
        <div className="mt-4 flex justify-end gap-2">
          <Secondary onClick={onClose}>{s.cancel}</Secondary>
          <Primary onClick={() => void start()} disabled={busy || writes === 0}>
            {busy ? s.starting : stories ? s.confirmStories : s.confirm(writes)}
          </Primary>
        </div>
      </div>
    </div>
  );
}
