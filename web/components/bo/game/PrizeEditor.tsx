"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { Contest, ContestPrize, PrizeType, Reward } from "@/lib/api/types";
import { useCan } from "@/components/bo/Can";
import { INPUT } from "@/components/bo/FormBits";
import { isLocked, prizeLabel } from "@/lib/gamification/contests";

// Scheda `prizes` di BO-14 (docs/08 §BO-14): premio, tipo (POINTS n / COUPON premio / PHYSICAL), quantità
// totale/residua, colore dello spicchio. Modificabile solo prima del LIVE; salvare il montepremi cancella gli istanti.

interface Row {
  code: string;
  name: string;
  type: PrizeType;
  points: string;
  rewardCode: string;
  quantity: string;
  wheelColor: string;
}

const TYPE_LABEL: Record<PrizeType, string> = { POINTS: "Punti", COUPON: "Coupon", PHYSICAL: "Fisico" };
const DEFAULT_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"];

function toRow(p: ContestPrize): Row {
  return {
    code: p.code,
    name: p.name,
    type: p.type,
    points: p.points == null ? "" : String(p.points),
    rewardCode: p.rewardCode ?? "",
    quantity: String(p.quantityTotal),
    wheelColor: p.wheelColor ?? "#2a78d6",
  };
}

export function PrizeEditor({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const canEdit = useCan("object.edit");
  const locked = isLocked(contest.status);
  const editing = canEdit && !locked;
  return editing ? <EditablePrizes contest={contest} onChanged={onChanged} /> : <PrizeTable contest={contest} />;
}

function PrizeTable({ contest }: { contest: Contest }) {
  if (contest.prizes.length === 0) {
    return <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun premio nel montepremi.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-md border border-[var(--color-bo-border)]">
      <table className="w-full text-sm">
        <thead className="border-b border-[var(--color-bo-border)] bg-slate-50 text-left text-xs uppercase tracking-wide text-[var(--color-bo-ink-2)]">
          <tr>
            <th className="px-3 py-2 font-medium">Premio</th>
            <th className="px-3 py-2 font-medium">Tipo</th>
            <th className="px-3 py-2 text-right font-medium">Totale</th>
            <th className="px-3 py-2 font-medium">Residui</th>
          </tr>
        </thead>
        <tbody>
          {contest.prizes.map((p) => {
            const pct = p.quantityTotal === 0 ? 0 : (p.quantityRemaining / p.quantityTotal) * 100;
            return (
              <tr key={p.id} className="border-b border-[var(--color-bo-border)] last:border-0">
                <td className="px-3 py-2">
                  <span className="flex items-center gap-2">
                    <span className="size-3 shrink-0 rounded-full ring-1 ring-black/10" style={{ background: p.wheelColor ?? "#cbd5e1" }} aria-hidden />
                    <span className="font-medium">{prizeLabel(p)}</span>
                    <span className="font-mono text-xs text-[var(--color-bo-ink-2)]">{p.code}</span>
                  </span>
                </td>
                <td className="px-3 py-2 text-xs">{TYPE_LABEL[p.type]}</td>
                <td className="px-3 py-2 text-right tabular-nums">{p.quantityTotal}</td>
                <td className="px-3 py-2">
                  <div className="flex min-w-32 items-center gap-2">
                    <div className="h-1.5 flex-1 overflow-hidden rounded-sm bg-[var(--color-bo-bg)]" role="img" aria-label={`${p.quantityRemaining} residui su ${p.quantityTotal}`}>
                      {pct > 0 ? <div className="h-full rounded-sm bg-[var(--color-bo-accent)]" style={{ width: `${Math.max(2, pct)}%` }} /> : null}
                    </div>
                    <span className="text-xs tabular-nums text-[var(--color-bo-ink-2)]">{p.quantityRemaining}</span>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function EditablePrizes({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const [rows, setRows] = useState<Row[]>(() => contest.prizes.map(toRow));
  const [error, setError] = useState<LhError | null>(null);
  const [saved, setSaved] = useState(false);
  const coupons = useLhQuery<Reward[]>("reward", "/v1/rewards", { type: "COUPON" });
  const save = useLhMutation<Contest, { prizes: unknown[] }>("gamification", "PUT", () => `/v1/contests/${contest.id}`, {
    onSuccess: () => {
      setSaved(true);
      onChanged();
    },
  });
  const units = rows.reduce((s, r) => s + (Number(r.quantity) || 0), 0);
  const update = (i: number, patch: Partial<Row>) => {
    setSaved(false);
    setRows((prev) => prev.map((r, k) => (k === i ? { ...r, ...patch } : r)));
  };

  return (
    <div className="space-y-3">
      <p className="text-xs text-[var(--color-bo-ink-2)]">
        Un istante vincente per ogni unità: il montepremi da {units} unità genera {units} istanti.
        {contest.instantsGeneratedAt ? " Salvare il montepremi cancella gli istanti già generati." : ""}
      </p>
      <div className="overflow-x-auto rounded-md border border-[var(--color-bo-border)]">
        <table className="w-full text-sm">
          <thead className="border-b border-[var(--color-bo-border)] bg-slate-50 text-left text-xs uppercase tracking-wide text-[var(--color-bo-ink-2)]">
            <tr>
              <th className="px-2 py-2 font-medium">Colore</th>
              <th className="px-2 py-2 font-medium">Codice</th>
              <th className="px-2 py-2 font-medium">Nome</th>
              <th className="px-2 py-2 font-medium">Tipo</th>
              <th className="px-2 py-2 font-medium">Valore</th>
              <th className="px-2 py-2 font-medium">Quantità</th>
              <th className="px-2 py-2" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-b border-[var(--color-bo-border)] last:border-0">
                <td className="px-2 py-1.5">
                  <input type="color" aria-label="Colore dello spicchio" value={r.wheelColor} onChange={(e) => update(i, { wheelColor: e.target.value })} className="h-8 w-10 cursor-pointer rounded border border-[var(--color-bo-border)] bg-white" />
                </td>
                <td className="px-2 py-1.5">
                  <input aria-label="Codice premio" value={r.code} onChange={(e) => update(i, { code: e.target.value.toUpperCase() })} className={`${INPUT} w-28 font-mono`} />
                </td>
                <td className="px-2 py-1.5">
                  <input aria-label="Nome premio" value={r.name} onChange={(e) => update(i, { name: e.target.value })} className={`${INPUT} min-w-40`} />
                </td>
                <td className="px-2 py-1.5">
                  <select aria-label="Tipo premio" value={r.type} onChange={(e) => update(i, { type: e.target.value as PrizeType })} className={`${INPUT} w-28`}>
                    {(Object.keys(TYPE_LABEL) as PrizeType[]).map((t) => (
                      <option key={t} value={t}>
                        {TYPE_LABEL[t]}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="px-2 py-1.5">
                  {r.type === "POINTS" ? (
                    <input aria-label="Punti" type="number" min={1} value={r.points} onChange={(e) => update(i, { points: e.target.value })} className={`${INPUT} w-24`} placeholder="punti" />
                  ) : r.type === "COUPON" ? (
                    <select aria-label="Premio coupon" value={r.rewardCode} onChange={(e) => update(i, { rewardCode: e.target.value })} className={`${INPUT} min-w-44`}>
                      <option value="">—</option>
                      {(coupons.data ?? []).map((c) => (
                        <option key={c.code} value={c.code}>
                          {c.name} ({c.code})
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="text-xs text-[var(--color-bo-ink-2)]">consegna manuale</span>
                  )}
                </td>
                <td className="px-2 py-1.5">
                  <input aria-label="Quantità" type="number" min={1} value={r.quantity} onChange={(e) => update(i, { quantity: e.target.value })} className={`${INPUT} w-20`} />
                </td>
                <td className="px-2 py-1.5 text-right">
                  <button type="button" aria-label={`Rimuovi ${r.code || "premio"}`} onClick={() => setRows((prev) => prev.filter((_, k) => k !== i))} className="rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-slate-100 hover:text-red-700">
                    <Trash2 className="size-4" aria-hidden />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() =>
            setRows((prev) => [
              ...prev,
              { code: "", name: "", type: "POINTS", points: "50", rewardCode: "", quantity: "10", wheelColor: DEFAULT_COLORS[prev.length % DEFAULT_COLORS.length] },
            ])
          }
          className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50"
        >
          <Plus className="size-4" aria-hidden /> Aggiungi premio
        </button>
        <button
          type="button"
          disabled={save.isPending}
          onClick={() => {
            setError(null);
            save.mutate(
              {
                prizes: rows.map((r, i) => ({
                  code: r.code,
                  name: r.name,
                  type: r.type,
                  points: r.type === "POINTS" && r.points !== "" ? Number(r.points) : null,
                  rewardCode: r.type === "COUPON" ? r.rewardCode || null : null,
                  quantity: Number(r.quantity) || 0,
                  wheelColor: r.wheelColor,
                  sortOrder: i + 1,
                })),
              },
              { onError: setError },
            );
          }}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          Salva montepremi
        </button>
        {saved ? <span className="text-xs text-emerald-700">Montepremi salvato.</span> : null}
        {error ? (
          <span className="text-xs text-red-700" role="alert">
            {error.detail || error.code}
          </span>
        ) : null}
      </div>
    </div>
  );
}
