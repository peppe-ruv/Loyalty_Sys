"use client";

import { useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import { formatRelative } from "@/lib/format/dates";
import { RETRY_DELAYS_MIN, countLabel, describeFactTypes, formatSuccessRate, successRate } from "@/lib/webhooks/webhooks";
import type { Webhook, WebhookDelivery, WebhookRequest } from "@/lib/webhooks/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { SideSheet } from "@/components/bo/SideSheet";
import { Can, useCan } from "@/components/bo/Can";
import { CodeText, PageHeader } from "@/components/bo/primitives";
import { Card, CardBody } from "@/components/ui/card";
import { SecretReveal, WebhookEditor } from "@/components/bo/webhooks/WebhookEditor";
import { DeliveryLog, DeliveryStatusBadge } from "@/components/bo/webhooks/DeliveryLog";
import { cn } from "@/lib/cn";

// BO-23 Webhook (docs/08 §BO-23; F-WBH-01; docs/servizi/engagement-service.md §3, §5). Elenco con URL, tipi di fatto,
// stato, ultima consegna e tasso di successo; il webhook scelto (`?webhook=<code>` nell'URL) mostra il registro
// consegne con *Invia evento di prova* e *Ritenta*. Lettura per tutti; scritture con `webhook.write` (solo ADMIN,
// docs/08 §2), rifiutate comunque dal servizio. Il segreto compare una sola volta, alla creazione.
type Sheet = { mode: "new" } | { mode: "edit"; webhook: Webhook } | { mode: "secret"; webhook: Webhook };

export default function WebhooksPage() {
  const search = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const selectedCode = search.get("webhook") ?? "";
  const list = useLhQuery<Webhook[]>("engagement", "/v1/webhooks");
  const [sheet, setSheet] = useState<Sheet | null>(null);

  function select(code: string) {
    const next = new URLSearchParams(search.toString());
    if (code) next.set("webhook", code);
    else next.delete("webhook");
    router.replace(`${pathname}?${next.toString()}`);
  }

  const columns: Column<Webhook>[] = [
    {
      key: "name",
      header: "Webhook",
      render: (w) => (
        <span className="flex flex-col">
          <span className="font-medium">{w.name}</span>
          <CodeText>{w.code}</CodeText>
        </span>
      ),
    },
    { key: "url", header: "URL", render: (w) => <span className="block max-w-[16rem] truncate font-mono text-xs" title={w.url}>{w.url}</span> },
    { key: "facts", header: "Tipi di fatto", render: (w) => <span className="text-xs" title={w.factTypes.join("\n")}>{describeFactTypes(w.factTypes)}</span> },
    { key: "enabled", header: "Attivo", render: (w) => <EnabledToggle webhook={w} /> },
    {
      key: "last",
      header: "Ultima consegna",
      className: "whitespace-nowrap",
      render: (w) =>
        w.stats.lastDeliveryAt ? (
          <span className="flex flex-col items-start gap-0.5">
            {w.stats.lastStatus ? <DeliveryStatusBadge status={w.stats.lastStatus} /> : null}
            <span className="text-xs text-[var(--color-bo-ink-2)]">{formatRelative(w.stats.lastDeliveryAt)}</span>
          </span>
        ) : (
          <span className="text-xs text-[var(--color-bo-ink-2)]">mai</span>
        ),
    },
    {
      key: "rate",
      header: "Successo",
      className: "whitespace-nowrap",
      render: (w) => (
        <span className="flex flex-col">
          <span className="font-medium tabular-nums">{formatSuccessRate(successRate(w.stats))}</span>
          <span className="text-xs text-[var(--color-bo-ink-2)]">{w.stats.ok} ok su {w.stats.total}</span>
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Webhook"
        subtitle={`Notifiche verso sistemi esterni: i fatti scelti partono firmati (HMAC-SHA256) verso l'URL indicato; se l'endpoint non risponde 2xx si ritenta dopo ${RETRY_DELAYS_MIN.join(", ")} minuti, poi la consegna è abbandonata.`}
        actions={
          <Can capability="webhook.write" mode="disable">
            <button onClick={() => setSheet({ mode: "new" })} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
              Nuovo webhook
            </button>
          </Can>
        }
      />
      <QueryState
        query={list}
        service="engagement"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessun webhook"
        emptyHint="Crea un webhook per inviare i fatti del programma a un sistema esterno (CRM, data warehouse…)."
      >
        {(d) => {
          const selected = d.find((w) => w.code === selectedCode || w.id === selectedCode) ?? null;
          return (
            <div className="space-y-4">
              <DataTable columns={columns} rows={d} rowKey={(w) => w.id} onRowClick={(w) => select(w.code === selectedCode ? "" : w.code)} />
              {selected ? (
                <WebhookDetail webhook={selected} onEdit={() => setSheet({ mode: "edit", webhook: selected })} onClose={() => select("")} />
              ) : (
                <p className="text-xs text-[var(--color-bo-ink-2)]">Scegli un webhook per vederne il registro consegne.</p>
              )}
            </div>
          );
        }}
      </QueryState>
      <SideSheet
        open={sheet != null}
        title={
          sheet?.mode === "new" ? "Nuovo webhook" : sheet?.mode === "secret" ? "Segreto di firma" : sheet ? (
            <span className="flex flex-col"><span className="font-semibold">Webhook</span><CodeText>{sheet.webhook.code}</CodeText></span>
          ) : ""
        }
        onClose={() => setSheet(null)}
      >
        {sheet?.mode === "secret" ? (
          <SecretReveal webhook={sheet.webhook} onDone={() => setSheet(null)} />
        ) : sheet ? (
          <WebhookEditor
            key={sheet.mode === "edit" ? sheet.webhook.id : "new"}
            webhook={sheet.mode === "edit" ? sheet.webhook : null}
            onSaved={() => setSheet(null)}
            onCreated={(created) => {
              select(created.code);
              setSheet({ mode: "secret", webhook: created });
            }}
            onDeleted={() => {
              setSheet(null);
              select("");
            }}
          />
        ) : null}
      </SideSheet>
    </div>
  );
}

function WebhookDetail({ webhook: w, onEdit, onClose }: { webhook: Webhook; onEdit: () => void; onClose: () => void }) {
  const canEdit = useCan("webhook.write");
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const test = useLhMutation<WebhookDelivery, undefined>("engagement", "POST", () => `/v1/webhooks/${w.id}/test`);
  return (
    <Card>
      <CardBody className="space-y-4 pt-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-base font-semibold">{w.name}</h2>
            <p className="break-all font-mono text-xs text-[var(--color-bo-ink-2)]">POST {w.url}</p>
            <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
              {w.enabled ? "Attivo" : "Spento: i nuovi fatti non generano consegne"} · {countLabel(w.factTypes.length, "tipo di fatto", "tipi di fatto")}:{" "}
              <span className="font-mono">{w.factTypes.join(", ")}</span>
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Can capability="webhook.write" mode="disable">
              <button
                type="button"
                disabled={test.isPending}
                onClick={() => {
                  setResult(null);
                  test.mutate(undefined, {
                    onSuccess: (d) =>
                      setResult(
                        d.status === "OK"
                          ? { ok: true, text: `Evento di prova consegnato (HTTP ${d.httpStatus}, ${d.durationMs} ms).` }
                          : {
                              ok: false,
                              text: `Evento di prova non consegnato (${d.httpStatus != null ? `HTTP ${d.httpStatus}` : d.error ?? "errore"}): si ritenta tra 1 minuto.`,
                            },
                      ),
                    onError: (e: LhError) => setResult({ ok: false, text: e.detail || e.code }),
                  });
                }}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
              >
                {test.isPending ? "Invio…" : "Invia evento di prova"}
              </button>
            </Can>
            <button type="button" onClick={onEdit} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm hover:bg-slate-50">
              {canEdit ? "Modifica" : "Dettagli"}
            </button>
            <button type="button" onClick={onClose} className="rounded px-2 py-1.5 text-sm text-[var(--color-bo-ink-2)] hover:bg-slate-50" aria-label="Chiudi il dettaglio">
              ✕
            </button>
          </div>
        </div>
        {result ? (
          <p role="status" className={cn("rounded-md border p-2 text-sm", result.ok ? "border-emerald-200 bg-emerald-50 text-emerald-900" : "border-amber-200 bg-amber-50 text-amber-900")}>
            {result.text}
          </p>
        ) : null}
        <div>
          <h3 className="mb-2 text-sm font-semibold">Registro consegne</h3>
          <DeliveryLog key={w.id} webhook={w} />
        </div>
      </CardBody>
    </Card>
  );
}

/** Interruttore in riga: PUT {enabled, version}; senza webhook.write è solo un'indicazione. */
function EnabledToggle({ webhook: w }: { webhook: Webhook }) {
  const canEdit = useCan("webhook.write");
  const toggle = useLhMutation<Webhook, WebhookRequest>("engagement", "PUT", () => `/v1/webhooks/${w.id}`);
  const on = toggle.isPending ? !w.enabled : w.enabled;
  return (
    <span className="inline-flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={on ? "Disattiva il webhook" : "Attiva il webhook"}
        disabled={!canEdit || toggle.isPending}
        title={canEdit ? undefined : "Richiede il ruolo ADMIN"}
        onClick={() => toggle.mutate({ enabled: !w.enabled, version: w.version })}
        className={cn(
          "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-50",
          on ? "bg-emerald-500" : "bg-slate-300",
        )}
      >
        <span className={cn("inline-block size-4 rounded-full bg-white shadow transition-transform", on ? "translate-x-4" : "translate-x-0.5")} />
      </button>
      {toggle.isError ? <span role="alert" className="text-xs text-red-700">{toggle.error.detail || toggle.error.code}</span> : null}
    </span>
  );
}
