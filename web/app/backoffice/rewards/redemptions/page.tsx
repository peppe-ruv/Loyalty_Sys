"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Redemption } from "@/lib/api/types";
import type { Trace } from "@/components/observe/TraceWaterfall";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { SideSheet } from "@/components/bo/SideSheet";
import { formatDate, formatDateTime } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";
import {
  REDEMPTION_STATUS_LABEL,
  REDEMPTION_TABS,
  ageLabel,
  reasonLabel,
  refundSeen,
  tabOf,
  type RedemptionTab,
} from "@/lib/reward/redemptions";
import { cn } from "@/lib/cn";

// BO-13 Richieste premio (docs/08 §BO-13; F-RWD-05..07): schede di stato, tabella, foglio laterale con la cronologia
// della saga (richiesta → punti spesi → confermata → evasa) e le azioni dell'operatore (redemption.handle).
const PAGE_SIZE = 25;

export default function RedemptionsPage() {
  const router = useRouter();
  const search = useSearchParams();
  const tab = tabOf(search.get("tab"));
  const openId = search.get("r");
  const [page, setPage] = useState(0);
  const current = REDEMPTION_TABS.find((t) => t.key === tab)!;
  const list = useLhQuery<Page<Redemption>>("reward", "/v1/redemptions", { ...current.query, page, size: PAGE_SIZE });

  const go = (next: { tab?: RedemptionTab; r?: string | null }) => {
    const q = new URLSearchParams(search.toString());
    if (next.tab) {
      q.set("tab", next.tab);
      setPage(0);
    }
    if (next.r === null) q.delete("r");
    else if (next.r) q.set("r", next.r);
    router.replace(`/backoffice/rewards/redemptions?${q.toString()}`, { scroll: false });
  };

  const columns: Column<Redemption>[] = [
    { key: "date", header: "Data", render: (r) => <span className="text-xs tabular-nums">{formatDateTime(r.requestedAt)}</span> },
    {
      key: "member",
      header: "Membro",
      render: (r) => (
        <Link href={`/backoffice/members/${r.memberId}`} onClick={(e) => e.stopPropagation()} className="font-mono text-xs hover:underline">
          {r.memberId}
        </Link>
      ),
    },
    { key: "reward", header: "Premio", render: (r) => <span className="font-medium">{r.rewardName}</span> },
    { key: "points", header: "Punti", className: "text-right", render: (r) => <span className="tabular-nums">{formatPoints(r.pointsCost)}</span> },
    {
      key: "status",
      header: "Stato",
      render: (r) => (
        <span className="inline-flex items-center gap-1.5">
          <StatusPill status={r.status} />
          {r.needsAttention ? <span className="text-xs text-amber-700" title="Da verificare">⚠</span> : null}
        </span>
      ),
    },
    {
      key: "fulfilment",
      header: "Evasione",
      render: (r) => (
        <span className="text-xs text-[var(--color-bo-ink-2)]">
          {r.couponCode ? <CodeText>{r.couponCode}</CodeText> : r.fulfilmentNote ?? reasonLabel(r.rejectReason) ?? "—"}
        </span>
      ),
    },
    { key: "age", header: "Età", className: "text-right", render: (r) => <span className="text-xs tabular-nums">{ageLabel(r.requestedAt)}</span> },
  ];

  return (
    <div>
      <PageHeader title="Richieste premio" subtitle="Richieste dei membri: evasione manuale, annulli con rimborso, casi da verificare." />
      <div className="mb-4 flex flex-wrap gap-1 border-b border-[var(--color-bo-border)]" role="tablist">
        {REDEMPTION_TABS.map((t) => (
          <TabButton key={t.key} active={t.key === tab} label={t.label} query={t.query} onClick={() => go({ tab: t.key })} />
        ))}
      </div>
      <QueryState
        query={list}
        service="reward"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle="Nessuna richiesta"
        emptyHint={tab === "todo" ? "Niente da spedire: tutte le richieste manuali sono evase." : "Nessuna richiesta in questa scheda."}
      >
        {(d) => (
          <>
            <DataTable columns={columns} rows={d.items} rowKey={(r) => r.id} onRowClick={(r) => go({ r: r.id })} />
            {d.page.totalPages > 1 ? (
              <div className="mt-2 flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
                <span className="tabular-nums">
                  {d.page.totalItems} richieste · pagina {d.page.number + 1} di {d.page.totalPages}
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
        onClose={() => go({ r: null })}
        title={
          <div>
            <p className="text-xs text-[var(--color-bo-ink-2)]">Richiesta premio</p>
            <p className="font-mono text-sm">{openId}</p>
          </div>
        }
      >
        {openId ? <RedemptionDetail key={openId} id={openId} /> : null}
      </SideSheet>
    </div>
  );
}

function TabButton({ active, label, query, onClick }: { active: boolean; label: string; query: Record<string, string>; onClick: () => void }) {
  // Conteggio della scheda: una pagina da 1 basta a leggere totalItems.
  const count = useLhQuery<Page<Redemption>>("reward", "/v1/redemptions", { ...query, size: 1 }, { refetchInterval: 30_000 });
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

function RedemptionDetail({ id }: { id: string }) {
  const query = useLhQuery<Redemption>("reward", `/v1/redemptions/${id}`);
  const [refundPending, setRefundPending] = useState(false);
  return (
    <QueryState query={query} service="reward">
      {(r) => (
        <div className="space-y-5 text-sm">
          <section className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-base font-semibold text-[var(--color-bo-ink)]">{r.rewardName}</p>
              <p className="text-xs text-[var(--color-bo-ink-2)]">
                <CodeText>{r.rewardCode}</CodeText> · {formatPoints(r.pointsCost)} PTS ·{" "}
                <Link href={`/backoffice/members/${r.memberId}`} className="font-mono hover:underline">
                  {r.memberId}
                </Link>
              </p>
            </div>
            <div className="flex items-center gap-2">
              <StatusPill status={r.status} />
              <Link href={`/backoffice/observe/traces?c=${r.correlationId}`} className="text-xs text-[var(--color-bo-accent)] hover:underline">
                Tracciato →
              </Link>
            </div>
          </section>

          {r.needsAttention ? (
            <p className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
              Pool coupon vuoto al momento dell&apos;evasione. Genera nuovi codici in «Coupon», poi ritenta.
            </p>
          ) : null}

          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Cronologia</h3>
            <ol className="relative space-y-3 border-l border-[var(--color-bo-border)] pl-4">
              {(r.history ?? []).map((h, i) => (
                <li key={i} className="relative">
                  <span className="absolute -left-[21px] top-1 size-2.5 rounded-full border-2 border-[var(--color-bo-surface)] bg-[var(--color-bo-accent)]" aria-hidden />
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className="font-medium">{REDEMPTION_STATUS_LABEL[h.status] ?? h.status}</span>
                    <span className="text-xs tabular-nums text-[var(--color-bo-ink-2)]">{formatDateTime(h.at)}</span>
                    {h.actor ? <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{h.actor}</span> : null}
                  </div>
                  {h.note ? <p className="text-xs text-[var(--color-bo-ink-2)]">{h.note}</p> : null}
                </li>
              ))}
              {refundPending ? <RefundWatcher correlationId={r.correlationId} onDone={() => setRefundPending(false)} /> : null}
            </ol>
          </section>

          <section className="grid gap-3 sm:grid-cols-2">
            <Info label="Coupon emesso">{r.couponCode ? <CodeText>{r.couponCode}</CodeText> : "—"}</Info>
            <Info label="Motivo">{reasonLabel(r.rejectReason) ?? "—"}</Info>
            <Info label="Nota di evasione">{r.fulfilmentNote ?? "—"}</Info>
            <Info label="Richiesta il">{formatDate(r.requestedAt)}</Info>
            {r.shipping ? (
              <Info label="Spedizione">
                <span className="whitespace-pre-line">
                  {[r.shipping.name, r.shipping.street, [r.shipping.zip, r.shipping.city].filter(Boolean).join(" ")].filter(Boolean).join("\n")}
                </span>
              </Info>
            ) : null}
          </section>

          <Actions redemption={r} onCancelled={() => setRefundPending(true)} />
        </div>
      )}
    </QueryState>
  );
}

/** "In elaborazione" finché il wallet non emette il rimborso (fatto nel tracciato della richiesta). */
function RefundWatcher({ correlationId, onDone }: { correlationId: string; onDone: () => void }) {
  const trace = useLhQuery<Trace>("insight", `/v1/traces/${correlationId}`, undefined, { refetchInterval: 1500 });
  const done = refundSeen(trace.data);
  if (done) {
    return (
      <li className="relative text-xs text-emerald-700" role="status">
        <span className="absolute -left-[21px] top-1 size-2.5 rounded-full bg-emerald-600" aria-hidden />
        Punti restituiti dal wallet.{" "}
        <button onClick={onDone} className="underline">
          Ok
        </button>
      </li>
    );
  }
  return (
    <li className="relative text-xs text-[var(--color-bo-ink-2)]" role="status">
      <span className="absolute -left-[21px] top-1 size-2.5 animate-pulse rounded-full bg-amber-500" aria-hidden />
      Rimborso in elaborazione nel wallet…
    </li>
  );
}

function Actions({ redemption: r, onCancelled }: { redemption: Redemption; onCancelled: () => void }) {
  const [note, setNote] = useState("");
  const [tracking, setTracking] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<LhError | null>(null);
  const fulfil = useLhMutation<Redemption, { note: string; tracking: string }>("reward", "POST", () => `/v1/redemptions/${r.id}/fulfil`);
  const cancel = useLhMutation<Redemption, { reason: string }>("reward", "POST", () => `/v1/redemptions/${r.id}/cancel`, {
    onSuccess: onCancelled,
  });
  const retry = useLhMutation<Redemption, undefined>("reward", "POST", () => `/v1/redemptions/${r.id}/retry-fulfilment`);

  if (r.status !== "CONFIRMED") return null;
  const input = "w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm";

  return (
    <Can capability="redemption.handle" mode="disable">
      <section className="space-y-4 border-t border-[var(--color-bo-border)] pt-4">
        {r.needsAttention ? (
          <button
            onClick={() => {
              setError(null);
              retry.mutate(undefined, { onError: setError });
            }}
            disabled={retry.isPending}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            Ritenta l&apos;evasione
          </button>
        ) : r.couponCode ? null : (
          <form
            className="space-y-2"
            onSubmit={(e) => {
              e.preventDefault();
              setError(null);
              fulfil.mutate({ note, tracking }, { onError: setError });
            }}
          >
            <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Segna come spedito</h3>
            <input aria-label="Nota" placeholder="Nota (es. corriere e data)" value={note} onChange={(e) => setNote(e.target.value)} className={input} />
            <input aria-label="Tracking" placeholder="Codice di tracking (facoltativo)" value={tracking} onChange={(e) => setTracking(e.target.value)} className={`${input} font-mono`} />
            <button
              type="submit"
              disabled={!note.trim() || fulfil.isPending}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              Segna come spedito
            </button>
          </form>
        )}
        <form
          className="space-y-2"
          onSubmit={(e) => {
            e.preventDefault();
            setError(null);
            cancel.mutate({ reason }, { onError: setError });
          }}
        >
          <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-bo-ink-2)]">Annulla e rimborsa</h3>
          <input aria-label="Motivo" placeholder="Motivo dell'annullo" value={reason} onChange={(e) => setReason(e.target.value)} className={input} />
          <button
            type="submit"
            disabled={!reason.trim() || cancel.isPending}
            className="rounded border border-red-200 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
          >
            Annulla e rimborsa {formatPoints(r.pointsCost)} PTS
          </button>
        </form>
        {error ? (
          <p role="alert" className="text-xs text-red-700">
            {error.detail || error.code}
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
