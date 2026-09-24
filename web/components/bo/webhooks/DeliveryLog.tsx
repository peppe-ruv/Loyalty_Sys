"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhMutation, useLhQuery, type Page } from "@/lib/api/client";
import { factLabel } from "@/lib/messages/facts";
import { formatDateTime } from "@/lib/format/dates";
import {
  DELIVERY_STATUSES,
  DELIVERY_STATUS_LABEL,
  DELIVERY_STATUS_TONE,
  attemptLabel,
  canRetry,
  countLabel,
  hasOpenDeliveries,
  nextAttemptLabel,
  outcomeLabel,
  prettyPayload,
  verifyCommand,
} from "@/lib/webhooks/webhooks";
import type { DeliveryStatus, Webhook, WebhookDelivery } from "@/lib/webhooks/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { SideSheet } from "@/components/bo/SideSheet";
import { Can } from "@/components/bo/Can";
import { INPUT } from "@/components/bo/FormBits";
import { CodeText } from "@/components/bo/primitives";
import { cn } from "@/lib/cn";

const SIZE = 20;
const FILTER = INPUT.replace("w-full ", "");

export function DeliveryStatusBadge({ status }: { status: DeliveryStatus }) {
  return (
    <span className={cn("inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium", DELIVERY_STATUS_TONE[status] ?? "bg-slate-100 text-slate-700")}>
      {DELIVERY_STATUS_LABEL[status] ?? status}
    </span>
  );
}

// Registro consegne di BO-23 (docs/08 §BO-23; GET /v1/webhooks/{id}/deliveries): stato, tentativi, esito HTTP, durata,
// prossimo ritento; filtro per stato, paginazione. Finché ci sono consegne in coda o da ritentare si aggiorna ogni 15 s
// (lo scheduler lavora ogni 30 s). Dettaglio: header inviati, firma, payload, risposta, *Ritenta* (webhook.write).
export function DeliveryLog({ webhook }: { webhook: Webhook }) {
  const [status, setStatus] = useState<DeliveryStatus | "">("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<WebhookDelivery | null>(null);
  const [live, setLive] = useState(true);
  const log = useLhQuery<Page<WebhookDelivery>>(
    "engagement",
    `/v1/webhooks/${webhook.id}/deliveries`,
    { status, page, size: SIZE },
    { refetchInterval: live ? 15_000 : undefined },
  );
  const items = log.data?.items ?? [];
  const autoRefresh = hasOpenDeliveries(items);
  if (live !== autoRefresh && log.data) setLive(autoRefresh);
  const current = open ? items.find((d) => d.id === open.id) ?? open : null;

  const columns: Column<WebhookDelivery>[] = [
    { key: "when", header: "Quando", className: "whitespace-nowrap", render: (d) => <span className="text-xs tabular-nums">{formatDateTime(d.createdAt)}</span> },
    {
      key: "fact",
      header: "Fatto",
      render: (d) => (
        <span className="flex flex-col">
          <span className="flex items-center gap-1.5">
            {factLabel(d.factType)}
            {d.test ? <span className="rounded bg-violet-100 px-1.5 text-[10px] font-semibold uppercase text-violet-800">prova</span> : null}
          </span>
          <span className="font-mono text-[11px] text-[var(--color-bo-ink-2)]">{d.factType}</span>
        </span>
      ),
    },
    {
      key: "member",
      header: "Membro",
      render: (d) =>
        d.memberId ? (
          <Link href={`/backoffice/members/${d.memberId}`} onClick={(e) => e.stopPropagation()} className="hover:underline">
            <CodeText>{d.memberId}</CodeText>
          </Link>
        ) : (
          "—"
        ),
    },
    { key: "status", header: "Stato", render: (d) => <DeliveryStatusBadge status={d.status} /> },
    { key: "attempt", header: "Tentativo", className: "whitespace-nowrap", render: (d) => <span className="text-xs">{attemptLabel(d)}</span> },
    { key: "outcome", header: "Esito", className: "whitespace-nowrap", render: (d) => <span className="text-xs">{outcomeLabel(d)}</span> },
    { key: "duration", header: "Durata", className: "whitespace-nowrap", render: (d) => <span className="text-xs tabular-nums">{d.durationMs != null ? `${d.durationMs} ms` : "—"}</span> },
    { key: "next", header: "Prossimo tentativo", className: "whitespace-nowrap", render: (d) => <span className="text-xs">{nextAttemptLabel(d)}</span> },
    {
      key: "actions",
      header: "",
      render: (d) => (canRetry(d.status) ? <RetryButton delivery={d} compact /> : null),
    },
  ];

  const info = log.data?.page;
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <select
          aria-label="Stato della consegna"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as DeliveryStatus | "");
            setPage(0);
          }}
          className={cn(FILTER, "w-48")}
        >
          <option value="">Tutti gli stati</option>
          {DELIVERY_STATUSES.map((s) => <option key={s} value={s}>{DELIVERY_STATUS_LABEL[s]}</option>)}
        </select>
        {autoRefresh ? <span className="text-xs text-[var(--color-bo-ink-2)]">Aggiornamento automatico: ci sono consegne in corso.</span> : null}
      </div>
      <QueryState
        query={log}
        service="engagement"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle="Nessuna consegna"
        emptyHint={status ? "Nessuna consegna in questo stato." : "Le consegne nascono dai fatti dei tipi scelti (webhook attivo) o da “Invia evento di prova”."}
      >
        {(d) => (
          <>
            <DataTable columns={columns} rows={d.items} rowKey={(x) => x.id} onRowClick={setOpen} />
            <div className="mt-2 flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
              <span>{info ? `${countLabel(info.totalItems, "consegna", "consegne")} · pagina ${info.number + 1} di ${Math.max(1, info.totalPages)}` : null}</span>
              <span className="flex gap-2">
                <button disabled={page === 0} onClick={() => setPage(page - 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40">
                  ← Precedente
                </button>
                <button disabled={!info || page + 1 >= info.totalPages} onClick={() => setPage(page + 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40">
                  Successiva →
                </button>
              </span>
            </div>
          </>
        )}
      </QueryState>
      <SideSheet
        open={current != null}
        title={current ? <span className="flex flex-col"><span className="font-semibold">Consegna {current.test ? "di prova" : ""}</span><CodeText>{current.id}</CodeText></span> : ""}
        onClose={() => setOpen(null)}
      >
        {current ? <DeliveryDetail delivery={current} webhook={webhook} onRetried={setOpen} /> : null}
      </SideSheet>
    </>
  );
}

function DeliveryDetail({ delivery: d, webhook, onRetried }: { delivery: WebhookDelivery; webhook: Webhook; onRetried: (d: WebhookDelivery) => void }) {
  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <DeliveryStatusBadge status={d.status} />
        <span className="text-xs text-[var(--color-bo-ink-2)]">tentativo {attemptLabel(d)} · {outcomeLabel(d)}</span>
        {canRetry(d.status) ? <RetryButton delivery={d} onDone={onRetried} /> : null}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        <dt className="text-[var(--color-bo-ink-2)]">Creata</dt>
        <dd>{formatDateTime(d.createdAt)}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">Ultimo tentativo</dt>
        <dd>{d.lastAttemptAt ? `${formatDateTime(d.lastAttemptAt)}${d.durationMs != null ? ` · ${d.durationMs} ms` : ""}` : "—"}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">Prossimo</dt>
        <dd>{d.nextAttemptAt && (d.status === "FAILED" || d.status === "PENDING") ? `${formatDateTime(d.nextAttemptAt)} (${nextAttemptLabel(d)})` : "—"}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">Fatto</dt>
        <dd>{factLabel(d.factType)} <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{d.factType}</span></dd>
        <dt className="text-[var(--color-bo-ink-2)]">Destinazione</dt>
        <dd className="break-all font-mono text-xs">POST {webhook.url}</dd>
      </dl>

      <section>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Header inviati</h4>
        <pre className="overflow-x-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
{`Content-Type: application/cloudevents+json; charset=utf-8
X-LH-Signature: ${d.signature}
X-LH-Event-Id: ${d.eventId}
X-LH-Delivery-Id: ${d.id}`}
        </pre>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          Firma = HMAC-SHA256 del corpo qui sotto col segreto del webhook. Per verificarla:{" "}
          <code className="break-all font-mono">{verifyCommand(d.signature)}</code>
        </p>
      </section>

      <section>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Payload (CloudEvent)</h4>
        <pre className="max-h-72 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">{prettyPayload(d.payload)}</pre>
      </section>

      <section>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Risposta</h4>
        {d.attempt === 0 ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Non ancora tentata: parte al prossimo giro dello scheduler (ogni 30 s).</p>
        ) : (
          <pre className="max-h-40 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
            {`${outcomeLabel(d)}${d.responseExcerpt ? `\n\n${d.responseExcerpt}` : ""}`}
          </pre>
        )}
      </section>
    </div>
  );
}

/** *Ritenta* (POST /v1/webhook-deliveries/{id}/retry): un tentativo immediato; solo webhook.write (ADMIN). */
function RetryButton({ delivery, compact = false, onDone }: { delivery: WebhookDelivery; compact?: boolean; onDone?: (d: WebhookDelivery) => void }) {
  const retry = useLhMutation<WebhookDelivery, undefined>("engagement", "POST", () => `/v1/webhook-deliveries/${delivery.id}/retry`, {
    onSuccess: (d) => onDone?.(d),
  });
  return (
    <span className="inline-flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
      <Can capability="webhook.write" mode="disable">
        <button
          type="button"
          disabled={retry.isPending}
          onClick={() => retry.mutate(undefined)}
          className={cn(
            "rounded border border-[var(--color-bo-accent)] text-[var(--color-bo-accent)] hover:bg-slate-50 disabled:opacity-50",
            compact ? "px-2 py-0.5 text-xs" : "px-3 py-1 text-sm",
          )}
        >
          {retry.isPending ? "Invio…" : "Ritenta"}
        </button>
      </Can>
      {retry.isError ? <span role="alert" className="text-xs text-red-700">{retry.error.detail || retry.error.code}</span> : null}
    </span>
  );
}
