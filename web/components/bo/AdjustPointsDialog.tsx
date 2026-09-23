"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError } from "@/lib/api/client";
import { formatPoints } from "@/lib/format/points";

// Rettifica punti (BO-03, F-WAL-07; docs/servizi/wallet-service.md §3): solo PTS (Q-46), nota ≥ 10 caratteri,
// anteprima "saldo dopo", errori sul campo (INSUFFICIENT_BALANCE, NOTE_TOO_SHORT).
const REASONS = [
  { code: "GOODWILL", label: "Gesto commerciale" },
  { code: "CORRECTION", label: "Correzione" },
  { code: "COMPLAINT", label: "Reclamo" },
  { code: "TEST", label: "Test" },
] as const;

type Direction = "CREDIT" | "DEBIT";

export function AdjustPointsDialog({
  memberId,
  balance,
  onClose,
}: {
  memberId: string;
  balance: number;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [direction, setDirection] = useState<Direction>("CREDIT");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState<string>("GOODWILL");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [fieldError, setFieldError] = useState<{ field: "amount" | "note" | "form"; text: string } | null>(null);
  const [done, setDone] = useState<number | null>(null);

  const n = Number.parseInt(amount, 10);
  const valid = Number.isFinite(n) && n > 0;
  const after = valid ? balance + (direction === "CREDIT" ? n : -n) : null;
  const noteOk = note.trim().length >= 10;

  async function submit() {
    setBusy(true);
    setFieldError(null);
    try {
      const res = await lhFetch<{ ledgerEntryId: string; balanceAfter: number }>("wallet", `/v1/wallets/${memberId}/adjustments`, {
        method: "POST",
        body: JSON.stringify({ currency: "PTS", direction, amount: n, reason, note: note.trim() }),
      });
      setDone(res.balanceAfter);
      qc.invalidateQueries({ queryKey: ["wallet"] });
    } catch (e) {
      const err = e as LhError;
      if (err.code === "INSUFFICIENT_BALANCE" || err.code === "INVALID_AMOUNT") {
        setFieldError({ field: "amount", text: err.detail || "Saldo insufficiente." });
      } else if (err.code === "NOTE_TOO_SHORT") {
        setFieldError({ field: "note", text: err.detail || "La nota deve avere almeno 10 caratteri." });
      } else {
        setFieldError({ field: "form", text: err.detail || err.code || "Rettifica non riuscita." });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-label="Rettifica punti"
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-lg border border-[var(--color-bo-border)] bg-white p-5 shadow-lg"
      >
        <h2 className="text-base font-semibold text-[var(--color-bo-ink)]">Rettifica punti ●</h2>
        <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">
          Membro {memberId} · saldo attuale {formatPoints(balance)} PTS. La rettifica finisce sempre in audit.
        </p>

        {done !== null ? (
          <div className="mt-4 space-y-3">
            <p className="rounded bg-emerald-50 p-3 text-sm text-emerald-800">
              Rettifica registrata. Nuovo saldo: <strong>{formatPoints(done)} PTS</strong>. Il movimento compare in <em>Movimenti</em>.
            </p>
            <div className="flex justify-end">
              <button onClick={onClose} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">Chiudi</button>
            </div>
          </div>
        ) : (
          <div className="mt-4 space-y-3 text-sm">
            <label className="block">
              <span className="text-xs text-[var(--color-bo-ink-2)]">Valuta</span>
              <select disabled className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5">
                <option>PTS — Punti</option>
              </select>
              <span className="mt-0.5 block text-[11px] text-[var(--color-bo-ink-2)]">I punti status (STS) non si rettificano a mano.</span>
            </label>

            <div className="flex gap-2">
              {(["CREDIT", "DEBIT"] as const).map((d) => (
                <button
                  key={d}
                  onClick={() => setDirection(d)}
                  className={`flex-1 rounded border px-2 py-1.5 ${direction === d ? "border-[var(--color-bo-accent)] bg-[var(--color-bo-accent)]/10 font-medium" : "border-[var(--color-bo-border)]"}`}
                >
                  {d === "CREDIT" ? "Accredito" : "Addebito"}
                </button>
              ))}
            </div>

            <label className="block">
              <span className="text-xs text-[var(--color-bo-ink-2)]">Quantità (PTS)</span>
              <input
                type="number"
                min={1}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5 tabular-nums"
              />
              {after !== null ? (
                <span className={`mt-0.5 block text-xs ${after < 0 ? "text-[var(--color-spend)]" : "text-[var(--color-bo-ink-2)]"}`}>
                  Saldo dopo: {formatPoints(after)} PTS{after < 0 ? " — saldo insufficiente" : ""}
                </span>
              ) : null}
              {fieldError?.field === "amount" ? <span className="mt-0.5 block text-xs text-[var(--color-spend)]">{fieldError.text}</span> : null}
            </label>

            <label className="block">
              <span className="text-xs text-[var(--color-bo-ink-2)]">Motivo</span>
              <select value={reason} onChange={(e) => setReason(e.target.value)} className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5">
                {REASONS.map((r) => (
                  <option key={r.code} value={r.code}>{r.label}</option>
                ))}
              </select>
            </label>

            <label className="block">
              <span className="text-xs text-[var(--color-bo-ink-2)]">Nota (almeno 10 caratteri)</span>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={2}
                className="mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5"
              />
              {fieldError?.field === "note" ? <span className="mt-0.5 block text-xs text-[var(--color-spend)]">{fieldError.text}</span> : null}
            </label>

            {fieldError?.field === "form" ? <p className="rounded bg-red-50 p-2 text-xs text-red-800">{fieldError.text}</p> : null}

            <div className="flex justify-end gap-2 pt-1">
              <button onClick={onClose} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5">Annulla</button>
              <button
                onClick={submit}
                disabled={busy || !valid || !noteOk || (after !== null && after < 0)}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 font-medium text-white disabled:opacity-50"
              >
                {busy ? "In elaborazione…" : "Conferma rettifica"}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
