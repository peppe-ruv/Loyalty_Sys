"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can, useCan } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { PageHeader, StatusPill, CodeText, TierBadge } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";
import { cn } from "@/lib/cn";
import {
  BLOCK_HINT,
  OUTCOMES,
  actionErrorMessage,
  matchBlock,
  memberSearchHint,
  outcomeOf,
  prettyEvent,
  resolutionLabel,
  resolutionOutcome,
  retryBlock,
  schemaErrors,
  type InboundDetail,
  type InboundRow,
} from "@/lib/inbound/inbound";

// BO-26 Monitor ingressi (docs/08 §BO-26; F-ING-09, F-ING-04): eventi in ingresso per esito; dettaglio in foglio
// laterale con CloudEvent completo, errori di schema campo per campo, TraceLink se accettato e — capacità
// inbound.handle (ADMIN, CARE) — *Riprova* (REJECTED/UNMATCHED) e *Abbina a un membro* (UNMATCHED), M7.4.

export default function InboundPage() {
  const router = useRouter();
  const search = useSearchParams();
  const status = outcomeOf(search.get("status"));
  const openId = search.get("e");
  const query = useLhQuery<InboundRow[]>("ingestion", "/v1/inbound-events", { status, limit: 200 });

  const go = (next: { status?: string; e?: string | null }) => {
    const q = new URLSearchParams(search.toString());
    if (next.status !== undefined) {
      if (next.status) q.set("status", next.status);
      else q.delete("status");
    }
    if (next.e === null) q.delete("e");
    else if (next.e) q.set("e", next.e);
    const qs = q.toString();
    router.replace(`/backoffice/observe/inbound${qs ? `?${qs}` : ""}`, { scroll: false });
  };

  const columns: Column<InboundRow>[] = [
    { key: "when", header: "Ricevuto", render: (e) => (e.receivedAt ? formatDateTime(e.receivedAt) : "—") },
    { key: "source", header: "Fonte", render: (e) => <CodeText>{e.sourceCode}</CodeText> },
    { key: "type", header: "Tipo", render: (e) => <span className="text-xs">{e.typeCode}</span> },
    { key: "subject", header: "Soggetto", render: (e) => <span className="break-all font-mono text-xs">{e.subject}</span> },
    { key: "member", header: "Membro", render: (e) => (e.memberId ? <CodeText>{e.memberId}</CodeText> : "—") },
    { key: "status", header: "Esito", render: (e) => <StatusPill status={e.status} /> },
    {
      key: "reject",
      header: "Motivo",
      render: (e) =>
        e.rejectCode ? (
          <span className="text-xs text-red-700" title={e.rejectDetail ?? ""}>
            {e.rejectCode}
          </span>
        ) : e.resolution ? (
          <span className="text-xs text-emerald-700" title={e.resolvedBy ?? ""}>
            {resolutionLabel(e.resolution)}
          </span>
        ) : (
          "—"
        ),
    },
  ];

  return (
    <div>
      <PageHeader title="Monitor ingressi" subtitle="Eventi in ingresso e loro esito: riprova i respinti, abbina i non abbinati." />
      <div className="mb-3 flex flex-wrap gap-1" role="tablist">
        {OUTCOMES.map((o) => (
          <button
            key={o.key}
            role="tab"
            aria-selected={status === o.key}
            onClick={() => go({ status: o.key })}
            className={
              "rounded-full px-3 py-1 text-xs " +
              (status === o.key ? "bg-[var(--color-bo-accent)] text-white" : "bg-slate-100 text-slate-700")
            }
          >
            {o.label}
          </button>
        ))}
      </div>
      <QueryState
        query={query}
        service="ingestion"
        isEmpty={(d) => d.length === 0}
        emptyTitle={status === "UNMATCHED" ? "Nessun evento non abbinato" : "Nessun evento in ingresso"}
        emptyHint={
          status === "UNMATCHED"
            ? "Tutti gli eventi sono stati abbinati. Per vederne uno, lancia lo scenario «Una giornata mista» da Scenari (BO-29)."
            : "Invia un'azione dal Simulatore eventi (BO-28)."
        }
      >
        {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.id} onRowClick={(e) => go({ e: e.id })} />}
      </QueryState>
      <SideSheet
        open={!!openId}
        onClose={() => go({ e: null })}
        title={
          <div>
            <p className="text-xs text-[var(--color-bo-ink-2)]">Evento in ingresso</p>
            <p className="font-mono text-sm">{openId ?? ""}</p>
          </div>
        }
      >
        {openId ? <InboundDetailView key={openId} id={openId} /> : null}
      </SideSheet>
    </div>
  );
}

function InboundDetailView({ id }: { id: string }) {
  const query = useLhQuery<InboundDetail>("ingestion", `/v1/inbound-events/${id}`);
  const [outcome, setOutcome] = useState<InboundDetail | null>(null);
  return (
    <QueryState query={query} service="ingestion">
      {(d) => {
        const errors = schemaErrors(d.rejectCode, d.rejectDetail);
        const resolved = resolutionLabel(d.resolution);
        return (
          <div className="space-y-5 text-sm">
            <section className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="font-mono text-base font-semibold text-[var(--color-bo-ink)]">{d.typeCode}</p>
                <p className="text-xs text-[var(--color-bo-ink-2)]">
                  dalla fonte <strong>{d.sourceCode}</strong>
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
                <StatusPill status={d.status} />
                {d.status === "ACCEPTED" ? <TraceLink correlationId={d.correlationId} /> : null}
              </div>
            </section>

            {outcome ? <OutcomeNote row={outcome} /> : null}

            {d.rejectCode || d.status === "UNMATCHED" ? (
              <section>
                <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">
                  {d.status === "UNMATCHED" ? "Perché non è abbinato" : "Perché è respinto"}
                </h3>
                <p className={cn("rounded px-3 py-2 text-xs", d.status === "UNMATCHED" ? "bg-amber-50 text-amber-900" : "bg-red-50 text-red-900")}>
                  {d.rejectCode ? <span className="font-mono font-semibold">{d.rejectCode}</span> : null}
                  {d.rejectCode && d.rejectDetail && errors.length === 0 ? " · " : null}
                  {errors.length === 0 ? d.rejectDetail : null}
                </p>
                {errors.length > 0 ? (
                  <table className="mt-2 w-full text-xs">
                    <thead>
                      <tr className="text-left text-[var(--color-bo-ink-2)]">
                        <th className="py-1 pr-3 font-medium">Campo</th>
                        <th className="py-1 font-medium">Errore di schema</th>
                      </tr>
                    </thead>
                    <tbody>
                      {errors.map((e, i) => (
                        <tr key={i} className="border-t border-[var(--color-bo-border)]">
                          <td className="py-1 pr-3 font-mono">{e.field}</td>
                          <td className="py-1">{e.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : null}
              </section>
            ) : null}

            <section className="grid gap-3 sm:grid-cols-2">
              <Info label="Id evento">
                <span className="break-all font-mono text-xs">{d.eventId}</span>
              </Info>
              <Info label="Soggetto">
                <span className="break-all font-mono text-xs">{d.subject}</span>
              </Info>
              <Info label="Ricevuto">{d.receivedAt ? formatDateTime(d.receivedAt) : "—"}</Info>
              <Info label="Istante dell'evento">{d.eventTime ? formatDateTime(d.eventTime) : "—"}</Info>
              <Info label="Origine">{d.origin ?? "—"}</Info>
              <Info label="Correlazione">
                <span className="break-all font-mono text-xs">{d.correlationId}</span>
              </Info>
              {resolved ? (
                <Info label="Risoluzione">
                  {resolved}
                  <p className="text-xs text-[var(--color-bo-ink-2)]">
                    {d.resolvedAt ? formatDateTime(d.resolvedAt) : ""} · <span className="font-mono">{d.resolvedBy}</span>
                  </p>
                </Info>
              ) : null}
            </section>

            <section>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">CloudEvent</h3>
              <pre className="max-h-72 overflow-auto rounded border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] p-3 text-[11px] leading-relaxed">
                {prettyEvent(d.payload)}
              </pre>
            </section>

            <Actions row={d} onDone={setOutcome} onConflict={() => query.refetch()} />
          </div>
        );
      }}
    </QueryState>
  );
}

function TraceLink({ correlationId }: { correlationId: string }) {
  return (
    <Link href={`/backoffice/observe/traces?c=${correlationId}`} className="text-xs text-[var(--color-bo-accent)] hover:underline">
      Tracciato →
    </Link>
  );
}

function OutcomeNote({ row }: { row: InboundDetail }) {
  const o = resolutionOutcome(row);
  return (
    <p
      role="status"
      className={cn(
        "rounded px-3 py-2 text-xs",
        o.tone === "success" && "bg-emerald-50 text-emerald-800",
        o.tone === "warning" && "bg-amber-50 text-amber-900",
        o.tone === "error" && "bg-red-50 text-red-800",
      )}
    >
      {o.text}
      {row.status === "ACCEPTED" ? (
        <>
          {" "}
          <TraceLink correlationId={row.correlationId} />
        </>
      ) : null}
    </p>
  );
}

function Actions({
  row: d,
  onDone,
  onConflict,
}: {
  row: InboundDetail;
  onDone: (row: InboundDetail) => void;
  onConflict: () => void;
}) {
  const canHandle = useCan("inbound.handle");
  const [error, setError] = useState<LhError | null>(null);
  const qc = useQueryClient();
  const handleError = (e: LhError) => {
    setError(e);
    if (e.status === 409) onConflict();
  };
  const finish = (r: InboundDetail) => {
    setError(null);
    qc.setQueryData(["ingestion", `/v1/inbound-events/${d.id}`, {}], r);
    onDone(r);
  };
  const retry = useLhMutation<InboundDetail, Record<string, never>>("ingestion", "POST", () => `/v1/inbound-events/${d.id}/retry`, {
    onSuccess: finish,
  });
  const match = useLhMutation<InboundDetail, { memberId: string }>("ingestion", "POST", () => `/v1/inbound-events/${d.id}/match`, {
    onSuccess: finish,
  });

  const rBlock = retryBlock(d, canHandle);
  const mBlock = matchBlock(d, canHandle);
  if (rBlock === "NOT_RETRYABLE" && mBlock === "NOT_UNMATCHED") return null;
  const busy = retry.isPending || match.isPending;

  return (
    <Can capability="inbound.handle" mode="disable">
      <section className="space-y-4 border-t border-[var(--color-bo-border)] pt-4">
        {rBlock !== "NOT_RETRYABLE" ? (
          <div className="space-y-1">
            <button
              onClick={() => {
                setError(null);
                retry.mutate({}, { onError: handleError });
              }}
              disabled={rBlock != null || busy}
              title={rBlock ? BLOCK_HINT[rBlock] : undefined}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              {retry.isPending ? "Riprovo…" : "Riprova"}
            </button>
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              Ripassa l&apos;evento salvato dalla pipeline (stessa fonte, stesso id): utile dopo aver riabilitato una fonte, corretto
              un tipo o registrato il membro.
            </p>
          </div>
        ) : null}

        {mBlock !== "NOT_UNMATCHED" ? (
          <MatchForm
            subject={d.subject}
            disabled={mBlock != null || busy}
            pending={match.isPending}
            onMatch={(memberId) => {
              setError(null);
              match.mutate({ memberId }, { onError: handleError });
            }}
          />
        ) : null}

        {error ? (
          <p role="alert" className="text-xs text-red-700">
            {actionErrorMessage(error)}
          </p>
        ) : null}
      </section>
    </Can>
  );
}

// Abbina a un membro: ricerca tra i membri (member GET /v1/members?q=) precompilata dal subject; se il servizio membri
// dorme (stato degradato) si può comunque indicare l'ID a mano: la verifica la fa ingestion (member_index).
function MatchForm({
  subject,
  disabled,
  pending,
  onMatch,
}: {
  subject: string;
  disabled: boolean;
  pending: boolean;
  onMatch: (memberId: string) => void;
}) {
  const [q, setQ] = useState(() => memberSearchHint(subject));
  const [manual, setManual] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const searchable = q.trim().length >= 2;
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { q: q.trim(), size: 8 }, { enabled: searchable && !disabled });
  const target = selected ?? (manual.trim() || null);
  const input = "w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm";

  return (
    <div className="space-y-2">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Abbina a un membro</h3>
      <label className="block text-xs text-[var(--color-bo-ink-2)]">
        Cerca
        <input
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setSelected(null);
          }}
          disabled={disabled}
          placeholder="nome, e-mail o ID (min. 2 caratteri)"
          className={cn(input, "mt-1")}
        />
      </label>
      {searchable && !disabled ? (
        members.isLoading ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Ricerca…</p>
        ) : members.isError ? (
          <p className="text-xs text-amber-700">
            Ricerca non disponibile: {members.error.asleep ? "il servizio membri dorme" : members.error.detail}. Indica l&apos;ID qui sotto.
          </p>
        ) : members.data && members.data.items.length > 0 ? (
          <ul className="divide-y divide-[var(--color-bo-border)] rounded border border-[var(--color-bo-border)]" role="listbox" aria-label="Membri trovati">
            {members.data.items
              .filter((m) => m.status !== "ANONYMIZED")
              .map((m) => {
                const active = m.status === "ACTIVE";
                return (
                  <li key={m.id}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={selected === m.id}
                      disabled={!active}
                      title={active ? undefined : `Membro ${m.status}: non riceve azioni`}
                      onClick={() => {
                        setSelected(m.id);
                        setManual("");
                      }}
                      className={cn(
                        "flex w-full items-center justify-between gap-2 px-2 py-1.5 text-left text-sm hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50",
                        selected === m.id && "bg-[var(--color-bo-bg)]",
                      )}
                    >
                      <span className="flex items-center gap-2">
                        {m.firstName} {m.lastName} <CodeText>{m.id}</CodeText>
                      </span>
                      <span className="flex items-center gap-2 text-xs text-[var(--color-bo-ink-2)]">
                        {m.email}
                        {active ? <TierBadge tier={m.tier} /> : <StatusPill status={m.status} />}
                      </span>
                    </button>
                  </li>
                );
              })}
          </ul>
        ) : (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun membro trovato.</p>
        )
      ) : null}
      <label className="block text-xs text-[var(--color-bo-ink-2)]">
        oppure ID membro
        <input
          value={manual}
          onChange={(e) => {
            setManual(e.target.value);
            setSelected(null);
          }}
          disabled={disabled}
          placeholder="MBR-000003"
          className={cn(input, "mt-1 font-mono")}
        />
      </label>
      <button
        onClick={() => target && onMatch(target)}
        disabled={disabled || !target}
        className="rounded border border-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-[var(--color-bo-accent)] hover:bg-[var(--color-bo-bg)] disabled:opacity-50"
      >
        {pending ? "Abbino…" : target ? `Abbina a ${target}` : "Abbina"}
      </button>
    </div>
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
