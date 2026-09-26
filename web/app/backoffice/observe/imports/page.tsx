"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { cn } from "@/lib/cn";
import {
  ACCEPT_ATTRIBUTE,
  CSV_COLUMNS,
  MAX_FILE_BYTES,
  MAX_ROWS,
  ROW_FILTERS,
  checkFile,
  countEntries,
  formatBytes,
  importFormData,
  inboundHref,
  isActive,
  newIdempotencyKey,
  progressPercent,
  reportHref,
  retryAllUnmatched,
  retrySummary,
  rowFilterOf,
  statusLabel,
  uploadErrorMessage,
  type ImportDetail,
  type ImportJob,
  type ImportOutcome,
  type ImportRow,
  type RetryUnmatchedResult,
} from "@/lib/imports/imports";

// BO-32 Import (docs/08 §BO-32; F2-ING-02, M8.7): caricamento di un file di eventi (CSV, NDJSON, JSON) elaborato in
// background da ingestion, elenco dei lavori con avanzamento, rapporto per esito con le righe non accettate, rapporto
// CSV scaricabile e — capacità inbound.handle (ADMIN, CARE) — *Carica* e *Riprova non abbinati* (come BO-26).

const POLL_MS = 3_000;
const PAGE_SIZE = 20;
const ROWS_PAGE_SIZE = 50;

interface SourceOption {
  code: string;
  name: string;
  enabled: boolean;
}

export default function ImportsPage() {
  const router = useRouter();
  const search = useSearchParams();
  const page = Math.max(0, Number(search.get("page") ?? 0) || 0);
  const openId = search.get("i");
  const [polling, setPolling] = useState(false);
  const list = useLhQuery<Page<ImportJob>>("ingestion", "/v1/imports", { page, size: PAGE_SIZE }, {
    refetchInterval: polling ? POLL_MS : undefined,
  });
  useEffect(() => setPolling(!!list.data?.items.some((j) => isActive(j.status))), [list.data]);

  const go = (next: { page?: number; i?: string | null }) => {
    const q = new URLSearchParams(search.toString());
    if (next.page !== undefined) {
      if (next.page > 0) q.set("page", String(next.page));
      else q.delete("page");
    }
    if (next.i === null) q.delete("i");
    else if (next.i) q.set("i", next.i);
    const qs = q.toString();
    router.replace(`/backoffice/observe/imports${qs ? `?${qs}` : ""}`, { scroll: false });
  };

  const columns: Column<ImportJob>[] = [
    { key: "when", header: "Caricato", render: (j) => formatDateTime(j.createdAt) },
    {
      key: "file",
      header: "File",
      render: (j) => (
        <div>
          <p className="break-all text-sm font-medium text-[var(--color-bo-ink)]">{j.fileName}</p>
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            {j.format} · {formatBytes(j.sizeBytes)}
          </p>
        </div>
      ),
    },
    { key: "source", header: "Fonte", render: (j) => (j.defaultSource ? <CodeText>{j.defaultSource}</CodeText> : "dal file") },
    { key: "status", header: "Stato", render: (j) => <JobStatus job={j} /> },
    { key: "outcomes", header: "Esiti", render: (j) => <CountsInline job={j} /> },
    { key: "by", header: "Caricato da", render: (j) => <span className="font-mono text-xs">{j.createdBy}</span> },
  ];

  return (
    <div>
      <PageHeader
        title="Import"
        subtitle="Carica un file di eventi: il servizio lo elabora in background con la stessa pipeline degli ingressi e produce un rapporto per esito."
      />
      <UploadPanel onUploaded={(job) => go({ page: 0, i: job.id })} />
      <QueryState
        query={list}
        service="ingestion"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle="Nessun import"
        emptyHint="Carica un file CSV, NDJSON o JSON di eventi con il modulo qui sopra."
      >
        {(d) => (
          <div className="space-y-2">
            <DataTable columns={columns} rows={d.items} rowKey={(j) => j.id} onRowClick={(j) => go({ i: j.id })} />
            <Pager
              page={d.page.number}
              totalPages={d.page.totalPages}
              totalItems={d.page.totalItems}
              onPage={(p) => go({ page: p })}
            />
          </div>
        )}
      </QueryState>
      <SideSheet
        open={!!openId}
        onClose={() => go({ i: null })}
        title={
          <div>
            <p className="text-xs text-[var(--color-bo-ink-2)]">Import</p>
            <p className="font-mono text-sm">{openId ?? ""}</p>
          </div>
        }
      >
        {openId ? <ImportDetailView key={openId} id={openId} /> : null}
      </SideSheet>
    </div>
  );
}

// ================= caricamento =================

function UploadPanel({ onUploaded }: { onUploaded: (job: ImportJob) => void }) {
  const sources = useLhQuery<SourceOption[]>("ingestion", "/v1/sources");
  const [file, setFile] = useState<File | null>(null);
  const [source, setSource] = useState("");
  const [key, setKey] = useState(() => newIdempotencyKey());
  const [error, setError] = useState<string | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const qc = useQueryClient();
  const upload = useMutation<ImportJob, LhError, File>({
    mutationFn: (f) =>
      lhFetch<ImportJob>("ingestion", "/v1/imports", {
        method: "POST",
        body: importFormData(f, source),
        headers: { "idempotency-key": key },
      }),
    onSuccess: (job) => {
      setError(null);
      setFile(null);
      setInputKey((k) => k + 1);
      setKey(newIdempotencyKey());
      void qc.invalidateQueries({ queryKey: ["ingestion", "/v1/imports"] });
      onUploaded(job);
    },
    onError: (e) => setError(uploadErrorMessage(e)),
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const problem = checkFile(file);
    if (problem || !file) {
      setError(problem);
      return;
    }
    setError(null);
    upload.mutate(file);
  };

  return (
    <section className="mb-5 rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] p-4">
      <Can capability="inbound.handle" mode="disable">
        <form onSubmit={submit} className="flex flex-wrap items-end gap-3" aria-label="Carica un import">
          <label className="flex flex-col gap-1 text-xs text-[var(--color-bo-ink-2)]">
            File (CSV, NDJSON, JSON)
            <input
              key={inputKey}
              type="file"
              accept={ACCEPT_ATTRIBUTE}
              aria-label="File da importare"
              onChange={(e) => {
                const f = e.target.files?.[0] ?? null;
                setFile(f);
                setKey(newIdempotencyKey());
                setError(f ? checkFile(f) : null);
              }}
              className="text-sm text-[var(--color-bo-ink)]"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-[var(--color-bo-ink-2)]">
            Fonte predefinita
            <select
              aria-label="Fonte predefinita"
              value={source}
              onChange={(e) => setSource(e.target.value)}
              className="rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm text-[var(--color-bo-ink)]"
            >
              <option value="">dalla colonna source del file</option>
              {(sources.data ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.name} ({s.code}){s.enabled ? "" : " — spenta"}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            disabled={upload.isPending}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {upload.isPending ? "Carico…" : "Carica"}
          </button>
          {sources.isError ? (
            <span className="text-xs text-amber-700">Fonti non disponibili: puoi caricare un file con la colonna source.</span>
          ) : null}
        </form>
      </Can>
      {error ? (
        <p role="alert" className="mt-2 rounded bg-red-50 px-3 py-2 text-xs text-red-800">
          {error}
        </p>
      ) : null}
      <details className="mt-3 text-xs text-[var(--color-bo-ink-2)]">
        <summary className="cursor-pointer select-none">Formato del file</summary>
        <div className="mt-2 space-y-2">
          <p>
            Al massimo {formatBytes(MAX_FILE_BYTES)} e {MAX_ROWS.toLocaleString("it-IT")} righe. <strong>CSV</strong> con
            intestazione (separatore virgola o punto e virgola); <strong>NDJSON</strong> un CloudEvent per riga;{" "}
            <strong>JSON</strong> un array di CloudEvent. Ogni riga passa dalle stesse regole del monitor ingressi: gli id già
            accettati diventano duplicati, i soggetti sconosciuti restano da abbinare.
          </p>
          <table className="w-full">
            <tbody>
              {CSV_COLUMNS.map((c) => (
                <tr key={c.name} className="border-t border-[var(--color-bo-border)]">
                  <td className="py-1 pr-3 font-mono">{c.name}</td>
                  <td className="py-1">{c.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}

// ================= elenco =================

function JobStatus({ job }: { job: ImportJob }) {
  const pct = progressPercent(job);
  return (
    <div className="min-w-32 space-y-1">
      <div className="flex items-center gap-2">
        <StatusPill status={job.status} />
        <span className="text-xs text-[var(--color-bo-ink-2)]">{statusLabel(job.status)}</span>
      </div>
      <div
        className="h-1.5 overflow-hidden rounded bg-slate-100"
        role="progressbar"
        aria-label={`Righe elaborate di ${job.fileName}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
      >
        <div
          className={cn("h-full rounded", job.status === "FAILED" ? "bg-red-400" : "bg-[var(--color-bo-accent)]")}
          style={{ width: `${pct}%` }}
        />
      </div>
      <p className="font-mono text-[11px] tabular-nums text-[var(--color-bo-ink-2)]">
        {job.rowsDone.toLocaleString("it-IT")} / {job.rowsTotal.toLocaleString("it-IT")} righe
      </p>
    </div>
  );
}

const COUNT_TONE: Record<ImportOutcome, string> = {
  ACCEPTED: "text-emerald-700",
  DUPLICATE: "text-amber-700",
  REJECTED: "text-red-700",
  UNMATCHED: "text-amber-700",
  INVALID: "text-red-700",
};

function CountsInline({ job }: { job: ImportJob }) {
  return (
    <ul className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs">
      {countEntries(job.counts)
        .filter((c) => c.value > 0 || c.key === "ACCEPTED")
        .map((c) => (
          <li key={c.key} className={COUNT_TONE[c.key]}>
            <span className="font-mono tabular-nums">{c.value.toLocaleString("it-IT")}</span> {c.label.toLowerCase()}
          </li>
        ))}
    </ul>
  );
}

function Pager({
  page,
  totalPages,
  totalItems,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Pagine" className="flex items-center justify-end gap-2 text-xs text-[var(--color-bo-ink-2)]">
      <span>
        {totalItems.toLocaleString("it-IT")} in tutto · pagina {page + 1} di {totalPages}
      </span>
      <button
        type="button"
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
        className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40"
      >
        Precedenti
      </button>
      <button
        type="button"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
        className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40"
      >
        Successivi
      </button>
    </nav>
  );
}

// ================= dettaglio e rapporto =================

function ImportDetailView({ id }: { id: string }) {
  const [polling, setPolling] = useState(false);
  const query = useLhQuery<ImportDetail>("ingestion", `/v1/imports/${id}`, undefined, {
    refetchInterval: polling ? POLL_MS : undefined,
  });
  useEffect(() => setPolling(!!query.data && isActive(query.data.job.status)), [query.data]);
  return (
    <QueryState query={query} service="ingestion" skeletonRows={4}>
      {(d) => (
        <div className="space-y-5 text-sm">
          <section className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="break-all text-base font-semibold text-[var(--color-bo-ink)]">{d.job.fileName}</p>
              <p className="text-xs text-[var(--color-bo-ink-2)]">
                {d.job.format} · {formatBytes(d.job.sizeBytes)} · caricato il {formatDateTime(d.job.createdAt)} da{" "}
                <span className="font-mono">{d.job.createdBy}</span>
                {d.job.defaultSource ? (
                  <>
                    {" "}
                    · fonte <strong>{d.job.defaultSource}</strong>
                  </>
                ) : null}
              </p>
            </div>
            <a
              href={reportHref(d.job.id)}
              download={`import-${d.job.id}-esiti.csv`}
              className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-xs font-medium hover:bg-slate-50"
            >
              Scarica rapporto CSV
            </a>
          </section>

          <JobStatus job={d.job} />
          {d.job.status === "FAILED" && d.job.errorDetail ? (
            <p role="alert" className="rounded bg-red-50 px-3 py-2 text-xs text-red-800">
              {d.job.errorDetail}
            </p>
          ) : null}

          <section aria-label="Esiti" className="grid grid-cols-2 gap-2 sm:grid-cols-5">
            {countEntries(d.job.counts).map((c) => (
              <div key={c.key} className="rounded border border-[var(--color-bo-border)] px-3 py-2">
                <p className="text-[11px] uppercase tracking-wide text-[var(--color-bo-ink-2)]">{c.label}</p>
                <p className={cn("font-mono text-lg tabular-nums", COUNT_TONE[c.key])}>{c.value.toLocaleString("it-IT")}</p>
              </div>
            ))}
          </section>

          <RetryUnmatched id={d.job.id} open={d.openUnmatched} />

          <ImportRows id={d.job.id} active={isActive(d.job.status)} />
        </div>
      )}
    </QueryState>
  );
}

function RetryUnmatched({ id, open }: { id: string; open: number }) {
  const [result, setResult] = useState<RetryUnmatchedResult | null>(null);
  const [error, setError] = useState<LhError | null>(null);
  const qc = useQueryClient();
  // A blocchi finché il servizio non dice che l'elenco è finito (nextAfterRow assente).
  const retry = useMutation<RetryUnmatchedResult, LhError, void>({
    mutationFn: () =>
      retryAllUnmatched((afterRow) =>
        lhFetch<RetryUnmatchedResult>("ingestion", `/v1/imports/${id}/retry-unmatched`, { method: "POST", query: { afterRow } }),
      ),
    onSuccess: (r) => {
      setError(null);
      setResult(r);
      void qc.invalidateQueries({ queryKey: ["ingestion"] });
    },
  });
  if (open === 0 && !result) return null;
  return (
    <section className="space-y-1 rounded border border-amber-200 bg-amber-50 p-3">
      <p className="text-xs text-amber-900">
        {open > 0
          ? `${open.toLocaleString("it-IT")} righe sono ancora da abbinare a un membro nel monitor ingressi.`
          : "Nessuna riga da abbinare."}
      </p>
      {open > 0 ? (
        <Can capability="inbound.handle" mode="disable">
          <button
            type="button"
            disabled={retry.isPending}
            onClick={() => retry.mutate(undefined, { onError: setError })}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {retry.isPending ? "Riprovo…" : "Riprova non abbinati"}
          </button>
        </Can>
      ) : null}
      {result ? (
        <p role="status" className="text-xs text-emerald-800">
          {retrySummary(result)}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-red-800">
          {error.detail || error.code}
        </p>
      ) : null}
    </section>
  );
}

function ImportRows({ id, active }: { id: string; active: boolean }) {
  const [filter, setFilter] = useState<ImportOutcome | "">("");
  const [page, setPage] = useState(0);
  // Il rapporto cresce mentre il lavoro avanza: si aggiorna con il lavoro.
  const rows = useLhQuery<Page<ImportRow>>(
    "ingestion",
    `/v1/imports/${id}/rows`,
    { outcome: filter || undefined, page, size: ROWS_PAGE_SIZE },
    { refetchInterval: active ? POLL_MS : undefined },
  );
  const columns: Column<ImportRow>[] = [
    { key: "row", header: "Riga", render: (r) => <span className="font-mono tabular-nums">{r.rowNumber}</span> },
    { key: "event", header: "Id evento", render: (r) => <span className="break-all font-mono text-xs">{r.eventId ?? "—"}</span> },
    {
      key: "outcome",
      header: "Esito",
      render: (r) => (
        <div className="space-y-0.5">
          <StatusPill status={r.outcome} />
          {r.rejectCode ? <p className="font-mono text-[11px] text-red-700">{r.rejectCode}</p> : null}
        </div>
      ),
    },
    { key: "detail", header: "Dettaglio", render: (r) => <span className="text-xs">{r.detail ?? "—"}</span> },
    {
      key: "now",
      header: "Ora",
      render: (r) => {
        const href = inboundHref(r);
        return (
          <div className="space-y-0.5">
            {r.currentStatus && r.currentStatus !== r.outcome ? <StatusPill status={r.currentStatus} /> : null}
            {href ? (
              <Link href={href} className="block text-xs text-[var(--color-bo-accent)] hover:underline">
                Monitor →
              </Link>
            ) : null}
          </div>
        );
      },
    },
  ];
  return (
    <section className="space-y-2">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Righe non accettate</h3>
      <div className="flex flex-wrap gap-1" role="tablist" aria-label="Filtra per esito">
        {ROW_FILTERS.map((f) => (
          <button
            key={f.key}
            role="tab"
            aria-selected={filter === f.key}
            onClick={() => {
              setFilter(rowFilterOf(f.key));
              setPage(0);
            }}
            className={cn(
              "rounded-full px-3 py-1 text-xs",
              filter === f.key ? "bg-[var(--color-bo-accent)] text-white" : "bg-slate-100 text-slate-700",
            )}
          >
            {f.label}
          </button>
        ))}
      </div>
      <QueryState
        query={rows}
        service="ingestion"
        skeletonRows={4}
        isEmpty={(d) => d.items.length === 0}
        emptyTitle={filter ? "Nessuna riga con questo esito" : "Tutte le righe elaborate sono state accettate"}
        emptyHint="Le righe accettate sono solo contate: le trovi nel monitor ingressi e nei tracciati."
      >
        {(d) => (
          <div className="space-y-2">
            <DataTable columns={columns} rows={d.items} rowKey={(r) => String(r.rowNumber)} />
            <Pager page={d.page.number} totalPages={d.page.totalPages} totalItems={d.page.totalItems} onPage={setPage} />
          </div>
        )}
      </QueryState>
    </section>
  );
}
