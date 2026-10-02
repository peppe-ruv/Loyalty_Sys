"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Card } from "@/components/ui/card";
import { it } from "@/lib/i18n/it";
import { CopyValue } from "./CopyButton";

// HUB-02 — codice OTP del momento degli operatori di test (ADR-051, Q-676). Interroga `GET /api/vetrina/totp` UNA volta
// per periodo (al cambio di codice, non ogni secondo) e conta i secondi in locale. Il seme non arriva qui: solo la
// versione abbreviata che la pagina passa come prop.

const t = it.testUsers;
const PERIOD = 30;

interface Otp {
  code: string;
  /** Istante (ms) in cui il codice smette di valere, sull'orologio del browser. */
  expiresAt: number;
}

export function OtpCard({ seedHint }: { seedHint: string }) {
  const [otp, setOtp] = useState<Otp | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const timer = useRef<number | undefined>(undefined);
  const abort = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    window.clearTimeout(timer.current);
    abort.current?.abort();
    const ctl = new AbortController();
    abort.current = ctl;
    fetch("/api/vetrina/totp", { cache: "no-store", signal: ctl.signal })
      .then((res) => {
        if (!res.ok) throw new Error("risposta non valida");
        return res.json() as Promise<{ code?: unknown; remainingSeconds?: unknown }>;
      })
      .then((body) => {
        if (ctl.signal.aborted) return;
        if (typeof body.code !== "string" || typeof body.remainingSeconds !== "number") throw new Error("risposta non valida");
        const at = Date.now();
        setOtp({ code: body.code, expiresAt: at + body.remainingSeconds * 1000 });
        setNow(at);
        setFailed(false);
        // Una sola richiesta per periodo: la prossima al cambio di codice (con un margine per l'orologio).
        timer.current = window.setTimeout(load, body.remainingSeconds * 1000 + 300);
      })
      .catch(() => {
        if (!ctl.signal.aborted) setFailed(true);
      });
  }, []);

  // All'uscita dalla pagina si annullano la richiesta in volo e il timer del prossimo codice.
  useEffect(() => {
    load();
    return () => {
      window.clearTimeout(timer.current);
      abort.current?.abort();
    };
  }, [load]);

  // Conto alla rovescia locale, solo a codice presente.
  const hasOtp = otp !== null;
  useEffect(() => {
    if (!hasOtp) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [hasOtp]);

  const remaining = otp ? Math.max(0, Math.ceil((otp.expiresAt - now) / 1000)) : 0;

  return (
    <Card className="flex flex-wrap items-center justify-between gap-3 p-4" data-testid="otp-card">
      <div>
        <p className="text-sm text-[var(--color-bo-ink-2)]">{t.otpTitle}</p>
        {otp ? (
          <>
            <p className="mt-1 text-2xl font-bold tracking-wider" data-testid="otp-code">
              <CopyValue value={otp.code} display={`${otp.code.slice(0, 3)} ${otp.code.slice(3)}`} what={t.otpCopyWhat} />
            </p>
            <div
              className="mt-1 h-1.5 w-44 overflow-hidden rounded-full bg-[var(--color-bo-bg)]"
              role="progressbar"
              aria-label={t.otpLabel}
              aria-valuemin={0}
              aria-valuemax={PERIOD}
              aria-valuenow={Math.min(PERIOD, remaining)}
              aria-valuetext={t.otpChanges(remaining)}
            >
              <div className="h-full rounded-full bg-[var(--color-bo-accent)] transition-all" style={{ width: `${Math.min(100, (remaining / PERIOD) * 100)}%` }} />
            </div>
            <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]" data-testid="otp-remaining">
              {t.otpChanges(remaining)}
            </p>
          </>
        ) : failed ? (
          <p role="alert" className="mt-1 text-sm">
            {t.otpError}{" "}
            <button type="button" onClick={load} className="font-medium text-[var(--color-bo-accent)] underline">
              {t.otpRetry}
            </button>
          </p>
        ) : (
          <div aria-busy="true" data-testid="otp-skeleton">
            <span className="sr-only">{t.otpLoading}</span>
            <div className="mt-1 h-8 w-32 animate-pulse rounded bg-[var(--color-bo-bg)]" aria-hidden />
          </div>
        )}
      </div>
      <p className="max-w-[38ch] text-xs text-[var(--color-bo-ink-2)]">{t.otpNote(seedHint)}</p>
    </Card>
  );
}
