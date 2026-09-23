"use client";

import { useMemo, useState } from "react";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { Reward, RewardBand } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader, StatusPill } from "@/components/bo/primitives";
import { Can, useCan } from "@/components/bo/Can";
import { liveRewardsInBand } from "@/lib/reward/stock";
import { formatPoints } from "@/lib/format/points";

// BO-11 Fasce (docs/08 §BO-11): scala verticale F1…F5 ordinata per soglia, con nome e premi per stato. Modifica in
// linea; *Nuova fascia*; elimina solo se vuota (409 BAND_IN_USE). Cambiare una soglia mostra l'impatto prima di
// salvare: la soglia è il costo di ogni premio della fascia (docs/03 §5).
export default function RewardBandsPage() {
  const bands = useLhQuery<RewardBand[]>("reward", "/v1/reward-bands");
  const rewards = useLhQuery<Reward[]>("reward", "/v1/rewards");
  const [adding, setAdding] = useState(false);

  return (
    <div>
      <PageHeader
        title="Fasce premi"
        subtitle="Ogni fascia è un prezzo: tutti i premi della fascia costano la sua soglia."
        actions={
          <Can capability="object.edit" mode="disable">
            <button
              onClick={() => setAdding(true)}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              Nuova fascia
            </button>
          </Can>
        }
      />
      <QueryState query={bands} service="reward" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna fascia">
        {(bs) => {
          const sorted = [...bs].sort((a, b) => a.pointsThreshold - b.pointsThreshold);
          const nextOrder = Math.max(0, ...bs.map((b) => b.sortOrder)) + 1;
          return (
            <ol className="max-w-3xl space-y-2">
              {sorted.map((b) => (
                <BandRow key={`${b.code}-${b.pointsThreshold}-${b.name}`} band={b} rewards={rewards.data ?? []} />
              ))}
              {adding ? (
                <NewBandRow sortOrder={nextOrder} suggested={(sorted.at(-1)?.pointsThreshold ?? 0) * 2 || 500} onDone={() => setAdding(false)} />
              ) : null}
            </ol>
          );
        }}
      </QueryState>
    </div>
  );
}

function BandRow({ band, rewards }: { band: RewardBand; rewards: Reward[] }) {
  const canEdit = useCan("object.edit");
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(band.name);
  const [threshold, setThreshold] = useState(String(band.pointsThreshold));
  const [error, setError] = useState<LhError | null>(null);
  const save = useLhMutation<RewardBand, Omit<RewardBand, "code">>("reward", "PUT", () => `/v1/reward-bands/${band.code}`, {
    onSuccess: () => setEditing(false),
  });
  const remove = useLhMutation<void, undefined>("reward", "DELETE", () => `/v1/reward-bands/${band.code}`);

  const byStatus = useMemo(() => {
    const m = new Map<string, number>();
    for (const r of rewards) if (r.bandCode === band.code) m.set(r.status, (m.get(r.status) ?? 0) + 1);
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [rewards, band.code]);
  const total = byStatus.reduce((s, [, n]) => s + n, 0);
  const next = Number(threshold);
  const thresholdChanged = threshold.trim() !== "" && next !== band.pointsThreshold;
  const live = liveRewardsInBand(rewards, band.code);

  return (
    <li className="flex items-stretch gap-3 rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)]">
      <div className="w-1.5 shrink-0 rounded-l-md" style={{ background: band.color ?? "var(--color-bo-ink-2)" }} aria-hidden />
      <div className="flex flex-1 flex-wrap items-center gap-x-6 gap-y-2 py-3 pr-3">
        <div className="w-28">
          <div className="font-mono text-xs text-[var(--color-bo-ink-2)]">{band.code}</div>
          {editing ? (
            <input aria-label="Nome" value={name} onChange={(e) => setName(e.target.value)} className={INPUT} />
          ) : (
            <div className="font-medium text-[var(--color-bo-ink)]">{band.name}</div>
          )}
        </div>
        <div className="w-32">
          <div className="text-xs text-[var(--color-bo-ink-2)]">Soglia (costo)</div>
          {editing ? (
            <input
              aria-label="Soglia"
              type="number"
              min={1}
              value={threshold}
              onChange={(e) => setThreshold(e.target.value)}
              className={`${INPUT} tabular-nums`}
            />
          ) : (
            <div className="font-semibold tabular-nums text-[var(--color-bo-ink)]">{formatPoints(band.pointsThreshold)} PTS</div>
          )}
        </div>
        <div className="flex flex-1 flex-wrap items-center gap-1.5">
          {total === 0 ? (
            <span className="text-xs text-[var(--color-bo-ink-2)]">Nessun premio</span>
          ) : (
            byStatus.map(([s, n]) => (
              <span key={s} className="inline-flex items-center gap-1 text-xs">
                <StatusPill status={s} />
                <span className="tabular-nums">{n}</span>
              </span>
            ))
          )}
        </div>
        {canEdit ? (
          <div className="flex items-center gap-2">
            {editing ? (
              <>
                <button
                  onClick={() => {
                    setError(null);
                    save.mutate(
                      { name: name.trim(), pointsThreshold: next, color: band.color, sortOrder: band.sortOrder },
                      { onError: setError },
                    );
                  }}
                  disabled={save.isPending || !name.trim() || !(next > 0)}
                  className="rounded bg-[var(--color-bo-accent)] px-2.5 py-1 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
                >
                  Salva
                </button>
                <button
                  onClick={() => {
                    setEditing(false);
                    setName(band.name);
                    setThreshold(String(band.pointsThreshold));
                    setError(null);
                  }}
                  className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50"
                >
                  Annulla
                </button>
              </>
            ) : (
              <>
                <button onClick={() => setEditing(true)} className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50">
                  Modifica
                </button>
                <button
                  onClick={() => {
                    setError(null);
                    remove.mutate(undefined, { onError: setError });
                  }}
                  disabled={total > 0 || remove.isPending}
                  title={total > 0 ? "Si elimina solo una fascia senza premi" : undefined}
                  className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-40"
                >
                  Elimina
                </button>
              </>
            )}
          </div>
        ) : null}
        {editing && thresholdChanged ? (
          <p className="w-full rounded border border-amber-200 bg-amber-50 px-2 py-1 text-xs text-amber-900" role="status">
            {live > 0
              ? `Cambia il costo di ${live} ${live === 1 ? "premio LIVE" : "premi LIVE"}: da ${formatPoints(band.pointsThreshold)} a ${formatPoints(next)} PTS.`
              : "Nessun premio LIVE in questa fascia: il cambio non tocca il catalogo del portale."}
          </p>
        ) : null}
        {error ? (
          <p className="w-full text-xs text-red-700" role="alert">
            {error.detail || error.code}
          </p>
        ) : null}
      </div>
    </li>
  );
}

function NewBandRow({ sortOrder, suggested, onDone }: { sortOrder: number; suggested: number; onDone: () => void }) {
  const [code, setCode] = useState(`F${sortOrder}`);
  const [name, setName] = useState(`Fascia ${sortOrder}`);
  const [threshold, setThreshold] = useState(String(suggested));
  const [error, setError] = useState<LhError | null>(null);
  const create = useLhMutation<RewardBand, RewardBand>("reward", "POST", () => "/v1/reward-bands", { onSuccess: onDone });
  const next = Number(threshold);

  return (
    <li className="flex flex-wrap items-end gap-3 rounded-md border border-dashed border-[var(--color-bo-accent)] bg-[var(--color-bo-surface)] p-3">
      <label className="text-xs text-[var(--color-bo-ink-2)]">
        Codice
        <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} className={`${INPUT} w-20 font-mono`} />
      </label>
      <label className="text-xs text-[var(--color-bo-ink-2)]">
        Nome
        <input value={name} onChange={(e) => setName(e.target.value)} className={`${INPUT} w-36`} />
      </label>
      <label className="text-xs text-[var(--color-bo-ink-2)]">
        Soglia (PTS)
        <input type="number" min={1} value={threshold} onChange={(e) => setThreshold(e.target.value)} className={`${INPUT} w-28 tabular-nums`} />
      </label>
      <button
        onClick={() => {
          setError(null);
          create.mutate({ code: code.trim(), name: name.trim(), pointsThreshold: next, color: null, sortOrder }, { onError: setError });
        }}
        disabled={create.isPending || !code.trim() || !name.trim() || !(next > 0)}
        className="rounded bg-[var(--color-bo-accent)] px-2.5 py-1 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
      >
        Crea
      </button>
      <button onClick={onDone} className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50">
        Annulla
      </button>
      <p className="w-full text-xs text-[var(--color-bo-ink-2)]">La nuova fascia va in cima alla scala: la soglia deve superare quella dell&apos;ultima.</p>
      {error ? (
        <p className="w-full text-xs text-red-700" role="alert">
          {error.detail || error.code}
        </p>
      ) : null}
    </li>
  );
}

const INPUT = "mt-0.5 block w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm";
