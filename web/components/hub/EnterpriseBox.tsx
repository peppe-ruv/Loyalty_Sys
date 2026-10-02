"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowUpRight, Loader2, Power, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardBody } from "@/components/ui/card";
import { formatElapsed } from "@/lib/api/status";
import { cn } from "@/lib/cn";
import { VETRINA_GUIDE_URL } from "@/lib/hub/links";
import { it } from "@/lib/i18n/it";

// HUB-01 — riquadro «Modalità Enterprise» accanto al pannello di stato (Q-674, ADR-051 decisione 9, F2-DIST-09).
// Client: legge lo stato del codespace da `GET /api/vetrina/codespace` e lo avvia con `POST` (nessun dato dal browser
// oltre all'azione). Il polling ogni 10 s esiste SOLO mentre la vetrina si sta accendendo. L'indirizzo della vetrina
// arriva come prop dal server component (`LH_HUB_ENTERPRISE_URL`, mai NEXT_PUBLIC). Senza token o codespace il server
// rende `EnterpriseNotConfigured` e questo componente non viene montato.
// Dopo il proprio POST GitHub può dire «spento» per qualche secondo: per 90 s si tratta come «in accensione» e si continua
// a leggere; il polling ha un tetto di 10 minuti, poi errore con «Riprova».
// SPEC-GAP: Q-721 — lo stato «Quasi pronta» (vetrina accesa ma IdP non ancora pronto) del mockup non c'è: da Vercel non si
// rileva (l'unica destinazione in uscita è GitHub e il CORS blocca una sonda dal browser); «accesa» porta alla vetrina,
// che mostra già lo stato dei suoi componenti (HUB-02).

const t = it.enterpriseBox;
export const POLL_MS = 10_000;
/** Durata attesa dell'accensione (3–5 minuti, mockup): serve solo per la barra, che non supera mai il 95%. */
const EXPECTED_MS = 240_000;
/** Dopo il proprio POST, «spento» di GitHub vale ancora «in accensione» per questo tempo. */
export const GRACE_MS = 90_000;
/** Tetto del polling: oltre, errore con «Riprova» (la vetrina non si è accesa o GitHub non lo dice). */
export const MAX_STARTING_MS = 600_000;

type CodespaceState = "available" | "starting" | "shutdown" | "unknown";
type ErrorReason = "read" | "start" | "unknown" | "timeout";
type View =
  | { kind: "loading" }
  | { kind: "off" }
  | { kind: "starting"; since: number | null }
  | { kind: "on" }
  | { kind: "error"; reason: ErrorReason };

function parse(body: unknown): CodespaceState {
  const s = body !== null && typeof body === "object" ? (body as { state?: unknown }).state : undefined;
  return s === "available" || s === "starting" || s === "shutdown" ? s : "unknown";
}

async function call(method: "GET" | "POST"): Promise<CodespaceState> {
  const res = await fetch("/api/vetrina/codespace", { method, cache: "no-store" });
  if (!res.ok) throw new Error("risposta non valida");
  return parse(await res.json());
}

const PILL: Record<View["kind"], string> = {
  loading: t.pill.loading,
  off: t.pill.off,
  starting: t.pill.starting,
  on: t.pill.on,
  error: t.pill.error,
};

const PILL_STYLE: Record<View["kind"], string> = {
  loading: "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
  off: "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
  starting: "border-[var(--color-state-waking)]/50 bg-[var(--color-state-waking)]/10 text-[var(--color-state-waking)]",
  on: "border-[var(--color-state-up)]/50 bg-[var(--color-state-up)]/10 text-[var(--color-state-up)]",
  error: "border-[var(--color-state-down)]/50 bg-[var(--color-state-down)]/10 text-[var(--color-state-down)]",
};

export function EnterpriseBox({ url }: { url: string }) {
  const [view, setView] = useState<View>({ kind: "loading" });
  const [now, setNow] = useState(() => Date.now());
  const sinceRef = useRef<number | null>(null);

  const apply = useCallback((state: CodespaceState) => {
    if (state === "available") setView({ kind: "on" });
    else if (state === "starting") setView({ kind: "starting", since: sinceRef.current });
    else if (state === "shutdown") {
      // Subito dopo il proprio avvio GitHub può ancora dire «spento»: resta «in accensione» (fino a 90 s).
      const at = sinceRef.current;
      if (at !== null && Date.now() - at < GRACE_MS) setView({ kind: "starting", since: at });
      else setView({ kind: "off" });
    } else setView({ kind: "error", reason: "unknown" });
  }, []);

  const refresh = useCallback(() => {
    call("GET").then(apply, () => setView({ kind: "error", reason: "read" }));
  }, [apply]);

  // Prima lettura: scheletro finché lo stato non arriva.
  useEffect(() => {
    refresh();
  }, [refresh]);

  const starting = view.kind === "starting";
  // Polling ogni 10 s e orologio, solo mentre si accende (Q-674): a vetrina accesa o spenta non parte nessun timer.
  // Tetto di 10 minuti dall'avvio (o dall'ingresso nello stato): poi errore con «Riprova».
  useEffect(() => {
    if (!starting) return;
    const origin = sinceRef.current ?? Date.now();
    const poll = window.setInterval(refresh, POLL_MS);
    const tick = window.setInterval(() => {
      const at = Date.now();
      setNow(at);
      if (at - origin >= MAX_STARTING_MS) setView({ kind: "error", reason: "timeout" });
    }, 1000);
    return () => {
      window.clearInterval(poll);
      window.clearInterval(tick);
    };
  }, [starting, refresh]);

  function start() {
    const at = Date.now();
    sinceRef.current = at;
    setNow(at);
    setView({ kind: "starting", since: at });
    call("POST").then(apply, () => {
      sinceRef.current = null;
      setView({ kind: "error", reason: "start" });
    });
  }

  const elapsedMs = view.kind === "starting" && view.since !== null ? Math.max(0, now - view.since) : null;

  return (
    <Card data-testid="enterprise-box" aria-labelledby="enterprise-box-title" role="region">
      <CardBody className="pt-4">
        <Header pill={view.kind} />
        <Steps />
        <div className="mt-4" aria-live="polite" data-testid="enterprise-action">
          {view.kind === "loading" ? (
            <div aria-busy="true" data-testid="enterprise-skeleton">
              <span className="sr-only">{t.loading}</span>
              <div className="h-9 w-64 animate-pulse rounded-md bg-[var(--color-bo-bg)]" aria-hidden />
            </div>
          ) : null}
          {view.kind === "off" ? (
            <>
              <Button onClick={start}>
                <Power className="h-4 w-4" aria-hidden /> {t.start}
              </Button>
              <p className="mt-1.5 text-xs text-[var(--color-bo-ink-2)]">{t.startNote}</p>
            </>
          ) : null}
          {view.kind === "starting" ? (
            <>
              <Button disabled>
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> {t.starting(elapsedMs === null ? null : formatElapsed(elapsedMs))}
              </Button>
              <div
                className="mt-2 h-2 w-full max-w-xs overflow-hidden rounded-full bg-[var(--color-bo-bg)]"
                role="progressbar"
                aria-label={t.pill.starting}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={elapsedMs === null ? undefined : Math.min(95, Math.round((elapsedMs / EXPECTED_MS) * 100))}
              >
                <div
                  className={cn("h-full rounded-full bg-[var(--color-state-waking)] transition-all", elapsedMs === null && "w-1/3 animate-pulse")}
                  style={elapsedMs === null ? undefined : { width: `${Math.min(95, (elapsedMs / EXPECTED_MS) * 100)}%` }}
                />
              </div>
              <p className="mt-1.5 text-xs text-[var(--color-bo-ink-2)]">{t.startingNote}</p>
            </>
          ) : null}
          {view.kind === "on" ? (
            <>
              <a
                href={url}
                rel="noopener"
                className="inline-flex items-center justify-center gap-2 rounded-md bg-[var(--color-bo-accent)] px-4 py-2 text-sm font-semibold text-white hover:opacity-90"
              >
                {t.open} <ArrowUpRight className="h-4 w-4" aria-hidden />
                <span className="sr-only">{t.openExternal}</span>
              </a>
              <p className="mt-1.5 text-xs text-[var(--color-bo-ink-2)]">{t.openNote}</p>
            </>
          ) : null}
          {view.kind === "error" ? (
            <div role="alert">
              <p className="text-sm text-[var(--color-state-down)]">{t.errors[view.reason]}</p>
              <Button
                variant="ghost"
                className="mt-2"
                onClick={() => {
                  sinceRef.current = null;
                  setView({ kind: "loading" });
                  refresh();
                }}
              >
                <RefreshCw className="h-4 w-4" aria-hidden /> {t.retry}
              </Button>
            </div>
          ) : null}
        </div>
        <GuideLink />
      </CardBody>
    </Card>
  );
}

function Header({ pill }: { pill: View["kind"] | "notConfigured" }) {
  const label = pill === "notConfigured" ? t.pill.notConfigured : PILL[pill];
  const style = pill === "notConfigured" ? PILL_STYLE.off : PILL_STYLE[pill];
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="enterprise-box-title" className="text-lg font-semibold">
          {t.title}
        </h2>
        <span className={cn("rounded-full border px-2.5 py-0.5 text-xs font-semibold", style)} data-testid="enterprise-pill">
          {label}
        </span>
      </div>
      <p className="mt-1 text-sm text-[var(--color-bo-ink-2)]">{t.intro}</p>
    </>
  );
}

function Steps() {
  return (
    <ol className="mt-3 space-y-2 text-sm" aria-label={t.stepsLabel}>
      {t.steps.map((step, i) => (
        <li key={step.title} className="flex gap-2.5">
          <span
            className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white"
            aria-hidden
          >
            {i + 1}
          </span>
          <span>
            <b>{step.title}</b>
            {step.text}
          </span>
        </li>
      ))}
    </ol>
  );
}

export function GuideLink() {
  return (
    <p className="mt-3 text-sm">
      <a
        href={VETRINA_GUIDE_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="font-medium text-[var(--color-bo-accent)] underline"
      >
        {t.guide} <span aria-hidden>↗</span>
        <span className="sr-only">{t.guideExternal}</span>
      </a>
    </p>
  );
}

/** Caso «non configurata» (Q-662, Q-674): nessun token o codespace, resta la nota «disponibile su richiesta». */
export function EnterpriseNotConfigured() {
  return (
    <Card data-testid="enterprise-box" aria-labelledby="enterprise-box-title" role="region">
      <CardBody className="pt-4">
        <Header pill="notConfigured" />
        <Steps />
        <p
          className="mt-4 rounded-md border border-dashed border-[var(--color-bo-border)] px-3 py-2 text-sm"
          data-testid="enterprise-not-configured"
        >
          {t.notConfigured}
        </p>
        <GuideLink />
      </CardBody>
    </Card>
  );
}
