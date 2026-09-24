"use client";

import { useState } from "react";
import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { Campaign } from "@/lib/api/types";
import type { CampaignDraft, ConditionNode, EffectSpec } from "@/lib/campaign/describe";
import { GeneratedSentence } from "@/components/bo/GeneratedSentence";
import { Can } from "@/components/bo/Can";
import { StatusPill } from "@/components/bo/primitives";
import { formatActor } from "@/lib/approvals/queue";
import { SOURCES, type ApprovalItem } from "@/lib/approvals/types";
import { formatDateTime } from "@/lib/format/dates";
import { ApprovalHistoryList } from "./ApprovalHistoryList";

/**
 * Foglio laterale di BO-21 (docs/08 §BO-21): riepilogo leggibile (campagne: frase generata; concorsi: montepremi e
 * periodo; premi: fascia, stock, termini), storico, *Approva* / *Rifiuta con commento* (`object.approve`).
 */
export function ApprovalSheet({ item, onDone }: { item: ApprovalItem; onDone: () => void }) {
  const qc = useQueryClient();
  const src = SOURCES[item.entityType];
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState<"APPROVE" | "REJECT" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function decide(action: "APPROVE" | "REJECT") {
    setBusy(action);
    setError(null);
    try {
      await lhFetch(src.service, `/v1/${src.resource}/${item.id}/transitions`, {
        method: "POST",
        body: JSON.stringify({ action, comment: comment.trim() || undefined }),
      });
      await Promise.all(["campaign", "reward", "gamification"].map((s) => qc.invalidateQueries({ queryKey: [s] })));
      onDone();
    } catch (e) {
      const err = e as LhError;
      setError(err.code === "REJECT_COMMENT_REQUIRED" ? "Il rifiuto richiede un commento." : err.detail || err.code);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs">{src.label}</span>
        <StatusPill status={item.status} />
        <Link href={src.href(item.id)} className="text-xs underline">
          Apri {item.code}
        </Link>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        <dt className="text-[var(--color-bo-ink-2)]">Inviato da</dt>
        <dd>{formatActor(item.submittedBy)}{item.submittedAt ? ` · ${formatDateTime(item.submittedAt)}` : ""}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">Approva</dt>
        <dd>{item.requiredRole ?? "—"}{item.reason ? ` (${item.reason})` : ""}</dd>
      </dl>

      <section className="rounded border border-[var(--color-bo-border)] p-3">
        <h4 className="mb-1 text-xs font-semibold uppercase text-[var(--color-bo-ink-2)]">Riepilogo</h4>
        {item.entityType === "CAMPAIGN" ? <CampaignSentence id={item.id} fallback={item.summary} /> : <p>{item.summary}</p>}
      </section>

      <section>
        <h4 className="mb-2 font-semibold">Storico</h4>
        <ApprovalHistoryList entityType={item.entityType} id={item.id} />
      </section>

      {item.status === "IN_REVIEW" ? (
        <Can capability="object.approve" mode="disable">
          <section className="space-y-2 rounded border border-[var(--color-bo-border)] p-3">
            <label className="block text-xs text-[var(--color-bo-ink-2)]">
              Commento (obbligatorio per rifiutare)
              <textarea
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                rows={3}
                className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm"
              />
            </label>
            {error ? <p role="alert" className="text-sm text-red-700">{error}</p> : null}
            <div className="flex gap-2">
              <button
                onClick={() => decide("APPROVE")}
                disabled={busy != null}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
              >
                {busy === "APPROVE" ? "Approvazione…" : "Approva"}
              </button>
              <button
                onClick={() => decide("REJECT")}
                disabled={busy != null || !comment.trim()}
                className="rounded border border-red-300 px-3 py-1.5 text-sm font-medium text-red-800 disabled:opacity-50"
              >
                {busy === "REJECT" ? "Rifiuto…" : "Rifiuta con commento"}
              </button>
            </div>
          </section>
        </Can>
      ) : null}
    </div>
  );
}

function CampaignSentence({ id, fallback }: { id: string; fallback: string | null }) {
  const query = useLhQuery<Campaign>("campaign", `/v1/campaigns/${id}`);
  if (!query.data) return <p>{fallback}</p>;
  const c = query.data;
  const draft: CampaignDraft = {
    triggerActionTypes: c.triggerActionTypes,
    audience: c.audience as CampaignDraft["audience"],
    conditions: c.conditions as ConditionNode | undefined,
    effects: (c.effects as EffectSpec[]) ?? [],
    limits: c.limits as CampaignDraft["limits"],
  };
  return (
    <div className="space-y-1">
      <GeneratedSentence draft={draft} />
      {fallback ? <p className="text-xs text-[var(--color-bo-ink-2)]">{fallback}</p> : null}
    </div>
  );
}
