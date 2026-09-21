"use client";

import { useState } from "react";
import { lhFetch, LhError } from "@/lib/api/client";
import type { Evaluation } from "@/lib/api/types";
import { PointsAmount, StatusPill } from "./primitives";

// Pannello Simulazione (docs/08 §BO-06): esegue il motore senza scrivere. Include la campagna corrente
// via campaignIds (anche se in bozza) più le LIVE. Mostra esito, effetti e altre campagne.
const ACTION_TYPES = [
  "purchase.completed",
  "ebill.activated",
  "app.login.daily",
  "survey.completed",
  "review.submitted",
];

export function SimulationPanel({ campaignId }: { campaignId?: string }) {
  const [memberId, setMemberId] = useState("MBR-000003");
  const [type, setType] = useState("purchase.completed");
  const [dataText, setDataText] = useState('{ "amount": 130, "currency": "EUR" }');
  const [time, setTime] = useState("");
  const [result, setResult] = useState<Evaluation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function simulate() {
    setError(null);
    setResult(null);
    setPending(true);
    try {
      const data = JSON.parse(dataText);
      const body: Record<string, unknown> = {
        action: { type, data, ...(time ? { time: new Date(time).toISOString() } : {}) },
        memberId,
        ...(campaignId ? { campaignIds: [campaignId] } : {}),
      };
      const res = await lhFetch<Evaluation>("campaign", "/v1/campaigns/simulate", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setResult(res);
    } catch (e) {
      setError(e instanceof LhError ? e.detail || e.code : "Dati non validi (JSON)");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="space-y-3">
      <h3 className="text-sm font-semibold">Simulazione</h3>
      <label className="block text-xs">
        Membro
        <input value={memberId} onChange={(e) => setMemberId(e.target.value)} className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm" />
      </label>
      <label className="block text-xs">
        Tipo azione
        <select value={type} onChange={(e) => setType(e.target.value)} className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm">
          {ACTION_TYPES.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </label>
      <label className="block text-xs">
        Dati (JSON)
        <textarea value={dataText} onChange={(e) => setDataText(e.target.value)} rows={3} className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 font-mono text-xs" />
      </label>
      <label className="block text-xs">
        Data/ora (opzionale)
        <input type="datetime-local" value={time} onChange={(e) => setTime(e.target.value)} className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm" />
      </label>
      <button onClick={simulate} disabled={pending} className="w-full rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
        {pending ? "Simulazione…" : "Simula"}
      </button>

      {error ? <p className="text-xs text-red-700">{error}</p> : null}
      {result ? (
        <div className="space-y-2 rounded-md border border-[var(--color-bo-border)] p-3">
          <div className="flex items-center gap-2">
            <span className="text-xs text-[var(--color-bo-ink-2)]">Esito</span>
            <StatusPill status={result.outcome === "MATCHED" ? "LIVE" : "DRAFT"} />
            <span className="text-xs">{result.outcome === "MATCHED" ? "SCATTA" : "NON SCATTA"}</span>
          </div>
          {result.effects.length > 0 ? (
            <ul className="space-y-0.5 text-sm">
              {result.effects.map((e) => (
                <li key={e.effectId} className="flex items-center justify-between">
                  <span className="text-xs text-[var(--color-bo-ink-2)]">{e.campaignCode}</span>
                  <PointsAmount value={e.amount} currency={e.currency} />
                </li>
              ))}
            </ul>
          ) : null}
          <div className="space-y-0.5">
            {result.results.map((r) => (
              <div key={r.campaignCode} className="flex items-center justify-between text-xs">
                <span>{r.campaignCode}</span>
                {r.matched ? (
                  <span className="text-emerald-700">✓ scatta</span>
                ) : (
                  <span className="text-slate-500">✗ {r.reason}</span>
                )}
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
