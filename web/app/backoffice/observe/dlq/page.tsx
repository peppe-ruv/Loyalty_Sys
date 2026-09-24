"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can, useCan } from "@/components/bo/Can";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { SideSheet } from "@/components/bo/SideSheet";
import { TopicDot } from "@/components/observe/TopicDot";
import { formatDateTime } from "@/lib/format/dates";
import { cn } from "@/lib/cn";
import {
  BLOCK_HINT,
  DLQ_CONSUMERS,
  DLQ_STATUS_TABS,
  FAMILY_LABEL,
  canConfirmDiscard,
  consumerService,
  discardBlock,
  dlqCode,
  dlqQuery,
  explainError,
  prettyPayload,
  reprocessBlock,
  shortStack,
  statusLabel,
  statusTabOf,
  type DlqEntry,
  type DlqStatus,
} from "@/lib/dlq/dlq";
import type { LiveFamily } from "@/lib/realtime/sse";

// BO-27 DLQ (docs/08 §BO-27; F-INS-05; insight /v1/dlq*): messaggi che un consumer non è riuscito a elaborare, con
// causa e tentativi; dettaglio in foglio laterale (messaggio, stack abbreviato, payload) e le azioni ADMIN
// *Riprocessa* (solo azioni: ingestion le ripubblica con lo stesso id) e *Scarta* (con nota e codice digitato).
const PAGE_SIZE = 25;

const STATUS_TONE: Record<string, string> = {
  OPEN: "bg-red-100 text-red-800",
  REPROCESSED: "bg-emerald-100 text-emerald-800",
  DISCARDED: "bg-slate-200 text-slate-600",
};

function DlqStatusPill({ status }: { status: string }) {
  return (
    <span className={cn("inline-flex rounded-full px-2 py-0.5 text-xs font-medium", STATUS_TONE[status] ?? "bg-slate-100 text-slate-700")}>
      {statusLabel(status)}
    </span>
  );
}

function familyOfTopic(topic: string | null): LiveFamily | null {
  if (!topic) return null;
  if (topic.startsWith("lh.actions")) return "ACTION";
  if (topic.startsWith("lh.effects")) return "EFFECT";
  if (topic.startsWith("lh.facts")) return "FACT";
  if (topic.startsWith("lh.audit")) return "AUDIT";
  return null;
}

export default function DlqPage() {
  const router = useRouter();
  const search = useSearchParams();
  const status = statusTabOf(search.get("status"));
  const openId = search.get("e");
  const [consumer, setConsumer] = useState("");
  const [errorCode, setErrorCode] = useState("");
  const [page, setPage] = useState(0);
  const list = useLhQuery<Page<DlqEntry>>("insight", "/v1/dlq", dlqQuery({ status, consumer, errorCode }, page, PAGE_SIZE), {
    refetchInterval: 15_000,
  });

  const go = (next: { status?: DlqStatus | "ALL"; e?: string | null }) => {
    const q = new URLSearchParams(search.toString());
    if (next.status) {
      q.set("status", next.status);
      setPage(0);
    }
    if (next.e === null) q.delete("e");
    else if (next.e) q.set("e", next.e);
    router.replace(`/backoffice/observe/dlq?${q.toString()}`, { scroll: false });
  };

  const columns: Column<DlqEntry>[] = [
    { key: "when", header: "Quando", render: (d) => <span className="text-xs tabular-nums">{formatDateTime(d.firstSeenAt)}</span> },
    { key: "consumer", header: "Consumer", render: (d) => <CodeText>{d.consumer}</CodeText> },
    {
      key: "topic",
      header: "Topic d'origine",
      render: (d) => {
        const fam = familyOfTopic(d.originalTopic);
        return (
          <span className="inline-flex items-center gap-1.5 font-mono text-xs">
            {fam ? <TopicDot family={fam} /> : null}
            {d.originalTopic ?? "—"}
          </span>
        );
      },
    },
    { key: "type", header: "Tipo", render: (d) => <span className="font-mono text-xs">{d.shortType ?? "—"}</span> },
    {
      key: "code",
      header: "errorCode",
      render: (d) => (
        <span className={cn("rounded px-1.5 py-0.5 font-mono text-xs", d.errorCode === "LOOP_GUARD" ? "bg-amber-100 text-amber-900" : "bg-red-50 text-red-800")}>
          {d.errorCode ?? "—"}
        </span>
      ),
    },
    { key: "attempts", header: "Tentativi", className: "text-right", render: (d) => <span className="tabular-nums">{d.attempts ?? "—"}</span> },
    { key: "status", header: "Stato", render: (d) => <DlqStatusPill status={d.status} /> },
  ];

  const input = "rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm";

  return (
    <div>
      <PageHeader
        title="DLQ"
        subtitle="Messaggi che un servizio non è riuscito a elaborare dopo i tentativi: causa, riprocessa o scarta."
      />
      <div className="mb-3 flex flex-wrap gap-1 border-b border-[var(--color-bo-border)]" role="tablist">
        {DLQ_STATUS_TABS.map((t) => (
          <StatusTab key={t.key} active={t.key === status} label={t.label} status={t.key} onClick={() => go({ status: t.key })} />
        ))}
      </div>
      <div className="mb-3 flex flex-wrap items-end gap-3 text-sm">
        <label className="flex flex-col gap-1 text-xs text-[var(--color-bo-ink-2)]">
          Consumer
          <select
            value={consumer}
            onChange={(e) => {
              setConsumer(e.target.value);
              setPage(0);
            }}
            className={input}
          >
            <option value="">Tutti</option>
            {DLQ_CONSUMERS.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-[var(--color-bo-ink-2)]">
          errorCode
          <input
            value={errorCode}
            onChange={(e) => {
              setErrorCode(e.target.value);
              setPage(0);
            }}
            placeholder="es. LOOP_GUARD"
            className={cn(input, "font-mono")}
          />
        </label>
        {consumer || errorCode ? (
          <button
            onClick={() => {
              setConsumer("");
              setErrorCode("");
              setPage(0);
            }}
            className="rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs hover:bg-slate-50"
          >
            Azzera filtri
          </button>
        ) : null}
      </div>
      <QueryState
        query={list}
        service="insight"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle={status === "OPEN" ? "Nessun messaggio in DLQ" : "Nessuna voce"}
        emptyHint={
          status === "OPEN"
            ? "Tutti i messaggi sono stati elaborati. Per vederne uno, lancia lo scenario «Un messaggio avvelenato» (SCN-POISON) da Scenari."
            : "Nessuna voce con questi filtri."
        }
      >
        {(d) => (
          <>
            <DataTable columns={columns} rows={d.items} rowKey={(r) => r.id} onRowClick={(r) => go({ e: r.id })} />
            {d.page.totalPages > 1 ? (
              <div className="mt-2 flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
                <span className="tabular-nums">
                  {d.page.totalItems} voci · pagina {d.page.number + 1} di {d.page.totalPages}
                </span>
                <span className="flex gap-2">
                  <button disabled={page === 0} onClick={() => setPage((p) => p - 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40">
                    ← Precedente
                  </button>
                  <button
                    disabled={page + 1 >= d.page.totalPages}
                    onClick={() => setPage((p) => p + 1)}
                    className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40"
                  >
                    Successiva →
                  </button>
                </span>
              </div>
            ) : null}
          </>
        )}
      </QueryState>
      <SideSheet
        open={!!openId}
        onClose={() => go({ e: null })}
        title={
          <div>
            <p className="text-xs text-[var(--color-bo-ink-2)]">Voce DLQ</p>
            <p className="font-mono text-sm">{openId ? dlqCode(openId) : ""}</p>
          </div>
        }
      >
        {openId ? <DlqDetail key={openId} id={openId} /> : null}
      </SideSheet>
    </div>
  );
}

function StatusTab({ active, label, status, onClick }: { active: boolean; label: string; status: DlqStatus | "ALL"; onClick: () => void }) {
  const count = useLhQuery<Page<DlqEntry>>("insight", "/v1/dlq", { status: status === "ALL" ? undefined : status, size: 1 }, {
    refetchInterval: 30_000,
  });
  const n = count.data?.page.totalItems;
  return (
    <button
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={cn(
        "-mb-px border-b-2 px-3 py-2 text-sm",
        active
          ? "border-[var(--color-bo-accent)] font-medium text-[var(--color-bo-ink)]"
          : "border-transparent text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]",
      )}
    >
      {label}
      {n != null ? <span className="ml-1.5 rounded-full bg-[var(--color-bo-bg)] px-1.5 text-xs tabular-nums text-[var(--color-bo-ink-2)]">{n}</span> : null}
    </button>
  );
}

function DlqDetail({ id }: { id: string }) {
  const query = useLhQuery<DlqEntry>("insight", `/v1/dlq/${id}`);
  return (
    <QueryState query={query} service="insight">
      {(d) => {
        const explanation = explainError(d.errorCode);
        return (
          <div className="space-y-5 text-sm">
            <section className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="font-mono text-base font-semibold text-[var(--color-bo-ink)]">{d.shortType ?? d.eventId}</p>
                <p className="text-xs text-[var(--color-bo-ink-2)]">
                  {FAMILY_LABEL[d.family] ?? d.family} · non elaborato da <strong>{consumerService(d.consumer)}</strong>
                  {d.memberId ? (
                    <>
                      {" · "}
                      <Link href={`/backoffice/members/${d.memberId}`} className="font-mono hover:underline">
                        {d.memberId}
                      </Link>
                    </>
                  ) : null}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <DlqStatusPill status={d.status} />
                {d.correlationId ? (
                  <Link href={`/backoffice/observe/traces?c=${d.correlationId}`} className="text-xs text-[var(--color-bo-accent)] hover:underline">
                    Tracciato →
                  </Link>
                ) : null}
              </div>
            </section>

            {explanation ? (
              <section className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900" role="note">
                <p className="font-semibold">{explanation.title}</p>
                <p className="mt-1">{explanation.body}</p>
              </section>
            ) : null}

            <section>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Errore</h3>
              <p className="rounded bg-red-50 px-3 py-2 text-xs text-red-900">
                <span className="font-mono font-semibold">{d.errorCode ?? "—"}</span>
                {d.errorMessage ? ` · ${d.errorMessage}` : ""}
              </p>
              {d.errorStack ? (
                <pre className="mt-2 max-h-48 overflow-auto rounded bg-slate-900 p-3 text-[11px] leading-relaxed text-slate-100">{shortStack(d.errorStack)}</pre>
              ) : null}
            </section>

            <section className="grid gap-3 sm:grid-cols-2">
              <Info label="Topic d'origine">
                <span className="font-mono text-xs">{d.originalTopic ?? "—"}</span>
              </Info>
              <Info label="Consumer">
                <CodeText>{d.consumer}</CodeText>
              </Info>
              <Info label="Tentativi">
                {d.attempts ?? "—"}
                {d.retryable === false ? <span className="ml-1 text-xs text-[var(--color-bo-ink-2)]">(errore non ritentabile)</span> : null}
              </Info>
              <Info label="Arrivato in DLQ">{formatDateTime(d.firstSeenAt)}</Info>
              <Info label="Id evento">
                <span className="break-all font-mono text-xs">{d.eventId}</span>
              </Info>
              {d.resolvedAt ? (
                <Info label={d.status === "DISCARDED" ? "Scartata" : "Riprocessata"}>
                  {formatDateTime(d.resolvedAt)} · <span className="font-mono text-xs">{d.resolvedBy}</span>
                  {d.resolutionNote ? <p className="text-xs text-[var(--color-bo-ink-2)]">{d.resolutionNote}</p> : null}
                </Info>
              ) : null}
            </section>

            <section>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Payload</h3>
              <pre className="max-h-72 overflow-auto rounded border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] p-3 text-[11px] leading-relaxed">
                {prettyPayload(d.payload)}
              </pre>
            </section>

            <Actions entry={d} />
          </div>
        );
      }}
    </QueryState>
  );
}

function Actions({ entry: d }: { entry: DlqEntry }) {
  const canHandle = useCan("dlq.handle");
  const [note, setNote] = useState("");
  const [typed, setTyped] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<LhError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const reprocess = useLhMutation<DlqEntry, Record<string, never>>("insight", "POST", () => `/v1/dlq/${d.id}/reprocess`, {
    onSuccess: () => setDone("Riprocessata: ingestion ha ripubblicato l'azione con lo stesso id. Segui il tracciato."),
  });
  const discard = useLhMutation<DlqEntry, { note: string }>("insight", "POST", () => `/v1/dlq/${d.id}/discard`, {
    onSuccess: () => setDone("Voce scartata."),
  });

  const rBlock = reprocessBlock(d, canHandle);
  const dBlock = discardBlock(d, canHandle);
  if (d.status !== "OPEN") {
    return done ? (
      <p role="status" className="rounded bg-emerald-50 px-3 py-2 text-xs text-emerald-800">
        {done}
      </p>
    ) : null;
  }
  const code = dlqCode(d.id);
  const input = "w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm";

  return (
    <Can capability="dlq.handle" mode="disable">
      <section className="space-y-4 border-t border-[var(--color-bo-border)] pt-4">
        <div className="space-y-1">
          <button
            onClick={() => {
              setError(null);
              reprocess.mutate({}, { onError: setError });
            }}
            disabled={rBlock != null || reprocess.isPending}
            title={rBlock ? BLOCK_HINT[rBlock] : undefined}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {reprocess.isPending ? "Riprocesso…" : "Riprocessa ●"}
          </button>
          {rBlock === "NOT_ACTION" ? <p className="text-xs text-[var(--color-bo-ink-2)]">{BLOCK_HINT.NOT_ACTION}</p> : null}
        </div>

        <div className="space-y-2">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Scarta</h3>
          <textarea
            aria-label="Nota"
            placeholder="Perché la scarti (obbligatoria)"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            className={input}
          />
          {!confirming ? (
            <button
              onClick={() => setConfirming(true)}
              disabled={dBlock != null || !note.trim()}
              className="rounded border border-red-200 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
            >
              Scarta ●
            </button>
          ) : (
            <div className="rounded border border-red-200 bg-red-50 p-3">
              <p className="text-sm font-semibold text-red-800">Scartare il messaggio?</p>
              <p className="mt-1 text-xs text-red-700">
                Il messaggio non verrà più elaborato da <strong>{consumerService(d.consumer)}</strong> e l&apos;azione resta nell&apos;audit. Non è
                reversibile. Digita <strong className="font-mono">{code}</strong> per confermare.
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                <input
                  aria-label="Codice di conferma"
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                  placeholder={code}
                  className="rounded border border-red-300 px-2 py-1 font-mono text-sm"
                />
                <button
                  onClick={() => {
                    setError(null);
                    discard.mutate({ note: note.trim() }, { onError: setError });
                  }}
                  disabled={!canConfirmDiscard(note, typed, d.id) || discard.isPending}
                  className="rounded bg-red-600 px-3 py-1 text-sm font-medium text-white disabled:opacity-50"
                >
                  Conferma
                </button>
                <button
                  onClick={() => {
                    setConfirming(false);
                    setTyped("");
                  }}
                  className="rounded border border-red-200 px-3 py-1 text-sm text-red-800 hover:bg-red-100"
                >
                  Annulla
                </button>
              </div>
            </div>
          )}
        </div>
        {error ? (
          <p role="alert" className="text-xs text-red-700">
            {error.asleep ? "Il servizio non risponde: riprova quando la demo è accesa." : error.detail || error.code}
          </p>
        ) : null}
      </section>
    </Can>
  );
}

function Info({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-[var(--color-bo-ink-2)]">{label}</p>
      <div className="text-sm text-[var(--color-bo-ink)]">{children}</div>
    </div>
  );
}
