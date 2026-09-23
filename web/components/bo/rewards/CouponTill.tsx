"use client";

import { useState } from "react";
import { lhFetch, LhError, useLhMutation } from "@/lib/api/client";
import type { Coupon } from "@/lib/api/types";
import { Card, CardBody } from "@/components/ui/card";
import { Can } from "@/components/bo/Can";
import { StatusPill } from "@/components/bo/primitives";
import { formatDate } from "@/lib/format/dates";

// Cassa simulata (docs/08 §BO-12; F-CPN-03): verifica di un codice → stato, membro, scadenza; *Segna come usato*
// (409 già usato, 410 scaduto) e *Annulla*.
export function CouponTill() {
  const [input, setInput] = useState("");
  const [coupon, setCoupon] = useState<Coupon | null>(null);
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [checking, setChecking] = useState(false);

  const use = useLhMutation<Coupon, undefined>("reward", "POST", () => `/v1/coupons/${encodeURIComponent(coupon?.code ?? "")}/use`);
  const voidIt = useLhMutation<Coupon, undefined>("reward", "POST", () => `/v1/coupons/${encodeURIComponent(coupon?.code ?? "")}/void`);

  async function check(code: string) {
    const c = code.trim();
    if (!c) return;
    setChecking(true);
    setMessage(null);
    try {
      setCoupon(await lhFetch<Coupon>("reward", `/v1/coupons/${encodeURIComponent(c)}`));
    } catch (e) {
      setCoupon(null);
      const err = e as LhError;
      setMessage({ tone: "error", text: err.status === 404 ? "Codice inesistente." : err.detail || err.code });
    } finally {
      setChecking(false);
    }
  }

  const act = (m: typeof use, ok: string) =>
    m.mutate(undefined, {
      onSuccess: (c) => {
        setCoupon(c);
        setMessage({ tone: "ok", text: ok });
      },
      onError: (e) => {
        setMessage({ tone: "error", text: e.detail || e.code });
        void check(coupon?.code ?? "");
      },
    });

  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <h2 className="text-sm font-semibold">Cassa simulata</h2>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void check(input);
          }}
        >
          <input
            aria-label="Codice coupon"
            placeholder="Es. CAF-XXXX-XXXX"
            value={input}
            onChange={(e) => setInput(e.target.value.toUpperCase())}
            className="w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1.5 font-mono text-sm"
          />
          <button
            type="submit"
            disabled={checking || !input.trim()}
            className="shrink-0 rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            Verifica
          </button>
        </form>

        {coupon ? (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-[var(--color-bo-ink-2)]">Codice</dt>
            <dd className="font-mono">{coupon.code}</dd>
            <dt className="text-[var(--color-bo-ink-2)]">Stato</dt>
            <dd>
              <StatusPill status={coupon.status} />
            </dd>
            <dt className="text-[var(--color-bo-ink-2)]">Pool</dt>
            <dd>{coupon.poolName ?? "—"}</dd>
            <dt className="text-[var(--color-bo-ink-2)]">Membro</dt>
            <dd className="font-mono">{coupon.memberId ?? "—"}</dd>
            <dt className="text-[var(--color-bo-ink-2)]">Scadenza</dt>
            <dd>{formatDate(coupon.expiresAt)}</dd>
          </dl>
        ) : null}

        {coupon ? (
          <div className="flex gap-2">
            <Can capability="coupon.use" mode="disable">
              <button
                onClick={() => act(use, "Coupon segnato come usato.")}
                disabled={use.isPending || coupon.status !== "ISSUED"}
                className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
              >
                Segna come usato
              </button>
            </Can>
            <Can capability="coupon.void" mode="disable">
              <button
                onClick={() => act(voidIt, "Coupon annullato.")}
                disabled={voidIt.isPending || coupon.status === "USED" || coupon.status === "VOID"}
                className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-40"
              >
                Annulla
              </button>
            </Can>
          </div>
        ) : null}

        {message ? (
          <p role={message.tone === "error" ? "alert" : "status"} className={`text-xs ${message.tone === "error" ? "text-red-700" : "text-emerald-700"}`}>
            {message.text}
          </p>
        ) : null}
      </CardBody>
    </Card>
  );
}
