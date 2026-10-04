"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowUpRight, Loader2, Power, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardBody } from "@/components/ui/card";
import { formatElapsed } from "@/lib/api/status";
import { cn } from "@/lib/cn";
import { VETRINA_GUIDE_URL } from "@/lib/hub/links";
import {
  CHECK_KEYS,
  allPending,
  allReady,
  itemPhase,
  parseChecks,
  pendingKeys,
  readyCount,
  type CheckKey,
  type Checks,
} from "@/lib/hub/readiness";
import { it } from "@/lib/i18n/it";

// HUB-01 — riquadro «Modalità Enterprise» accanto al pannello di stato (Q-674, Q-728, ADR-051 decisione 9, F2-DIST-09).
// Client: legge lo stato da `GET /api/vetrina/codespace` e avvia il codespace con `POST` (nessun dato dal browser oltre
// all'azione). Il polling ogni 10 s esiste SOLO mentre la vetrina si sta accendendo. L'indirizzo della vetrina arriva
// come prop dal server component (`LH_HUB_ENTERPRISE_URL`, mai NEXT_PUBLIC). Senza token o codespace il server rende
// `EnterpriseNotConfigured` e questo componente non viene montato.
// «Apri la vetrina» compare SOLO quando tutte e nove le risorse sono pronte (codespace, porte pubbliche, web, servizi,
// Postgres, Kafka, accesso dei due realm, membri di test registrati): le legge il server della demo (la route aggiunge
// `checks` a codespace acceso, il browser non può sondare la vetrina per il CORS) e questo componente le mostra in
// un elenco con uno spinner. Dopo 15 minuti senza che siano tutte pronte compare «Non pronta» con le risorse che mancano
// e «Controlla di nuovo» (non riaccende niente). Dopo il proprio POST GitHub può dire «spento» per qualche secondo: per
// 90 s si tratta come «in accensione» e si continua a leggere.

const t = it.enterpriseBox;
export const POLL_MS = 10_000;
/** Le risorse da attendere (mockup HUB-01): il pulsante compare solo quando sono tutte pronte. */
export const TOTAL_CHECKS = CHECK_KEYS.length;
/** Dopo il proprio POST, «spento» di GitHub vale ancora «in accensione» per questo tempo. */
export const GRACE_MS = 90_000;
/** Tetto dell'attesa (Q-728): oltre, «Non pronta» con la risorsa bloccata e «Controlla di nuovo». */
export const MAX_STARTING_MS = 900_000;

type CodespaceState = "available" | "starting" | "shutdown" | "unknown";
type ErrorReason = "read" | "start" | "unknown";
type Times = Partial<Record<CheckKey, number>>;
interface Reading {
  state: CodespaceState;
  checks: Checks | null;
}
type View =
  | { kind: "loading" }
  | { kind: "off" }
  | { kind: "starting"; checks: Checks; times: Times; showElapsed: boolean }
  | { kind: "ready"; checks: Checks; times: Times; totalMs: number | null }
  | { kind: "stuck"; checks: Checks; times: Times }
  | { kind: "error"; reason: ErrorReason };

function parse(body: unknown): Reading {
  const o = body !== null && typeof body === "object" ? (body as { state?: unknown; checks?: unknown }) : {};
  const s = o.state;
  const state: CodespaceState = s === "available" || s === "starting" || s === "shutdown" ? s : "unknown";
  return { state, checks: parseChecks(o.checks) };
}

async function call(method: "GET" | "POST"): Promise<Reading> {
  const res = await fetch("/api/vetrina/codespace", { method, cache: "no-store" });
  if (!res.ok) throw new Error("risposta non valida");
  return parse(await res.json());
}

interface Ctx {
  /** Istante della lettura. */
  at: number;
  /** Istante del proprio avvio (clic su «Accendi»), `null` se la pagina è stata aperta a vetrina già in accensione. */
  started: number | null;
}

const timesOf = (v: View): Times => (v.kind === "starting" || v.kind === "ready" || v.kind === "stuck" ? v.times : {});

/** Tempi di comparsa di ogni risorsa pronta, dal proprio avvio; senza avvio proprio non si conoscono e restano vuoti. */
function withTimes(prev: Times, checks: Checks, ctx: Ctx): Times {
  if (ctx.started === null) return prev;
  const next: Times = { ...prev };
  for (const k of CHECK_KEYS) if (checks[k] === "ok" && next[k] === undefined) next[k] = Math.max(0, ctx.at - ctx.started);
  return next;
}

/** Nuova vista da una lettura del server (funzione pura: si prova da sé). `null` = ancora «spento»: lo decide il chiamante. */
function fromReading(prev: View, r: Reading, ctx: Ctx): View | "shutdown" {
  if (r.state === "available") {
    // Senza `checks` (risposta di un POST a vetrina già accesa) non si sa ancora nulla delle risorse: si resta in attesa.
    const checks = r.checks ?? { ...allPending(), codespace: "ok" as const };
    const times = withTimes(timesOf(prev), checks, ctx);
    if (allReady(checks)) {
      return { kind: "ready", checks, times, totalMs: ctx.started === null ? null : Math.max(0, ctx.at - ctx.started) };
    }
    return { kind: "starting", checks, times, showElapsed: ctx.started !== null };
  }
  if (r.state === "starting") {
    return { kind: "starting", checks: allPending(), times: timesOf(prev), showElapsed: ctx.started !== null };
  }
  if (r.state === "shutdown") return "shutdown";
  return { kind: "error", reason: "unknown" };
}

const PILL: Record<View["kind"], string> = {
  loading: t.pill.loading,
  off: t.pill.off,
  starting: t.pill.starting,
  ready: t.pill.on,
  stuck: t.pill.stuck,
  error: t.pill.error,
};

const PILL_STYLE: Record<View["kind"], string> = {
  loading: "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
  off: "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
  starting: "border-[var(--color-state-waking)]/50 bg-[var(--color-state-waking)]/10 text-[var(--color-state-waking)]",
  ready: "border-[var(--color-state-up)]/50 bg-[var(--color-state-up)]/10 text-[var(--color-state-up)]",
  stuck: "border-[var(--color-state-down)]/50 bg-[var(--color-state-down)]/10 text-[var(--color-state-down)]",
  error: "border-[var(--color-state-down)]/50 bg-[var(--color-state-down)]/10 text-[var(--color-state-down)]",
};

const NOTE = "mt-1.5 text-xs text-[var(--color-bo-ink-2)]";

export function EnterpriseBox({ url }: { url: string }) {
  const [view, setView] = useState<View>({ kind: "loading" });
  const [now, setNow] = useState(() => Date.now());
  /** Proprio avvio (clic su «Accendi») o `null`. */
  const startedRef = useRef<number | null>(null);
  /** Da quando si aspetta (proprio avvio, prima volta che si vede «in accensione», «Controlla di nuovo»): serve al tetto. */
  const originRef = useRef<number | null>(null);

  const apply = useCallback((r: Reading) => {
    const at = Date.now();
    // L'attesa comincia la prima volta che si vede la vetrina accendersi (se non c'è già un proprio avvio).
    if (r.state === "available" || r.state === "starting") originRef.current ??= at;
    setView((prev) => {
      const out = fromReading(prev, r, { at, started: startedRef.current });
      if (out === "shutdown") {
        // Subito dopo il proprio avvio GitHub può ancora dire «spento»: resta «in accensione» (fino a 90 s).
        const s = startedRef.current;
        if (s !== null && at - s < GRACE_MS) {
          return { kind: "starting", checks: allPending(), times: timesOf(prev), showElapsed: true };
        }
        return { kind: "off" };
      }
      return out;
    });
  }, []);

  const refresh = useCallback(() => {
    call("GET").then((r) => apply(r), () => setView({ kind: "error", reason: "read" }));
  }, [apply]);

  // Prima lettura: «Controllo le risorse…» finché lo stato non arriva.
  useEffect(() => {
    refresh();
  }, [refresh]);

  const starting = view.kind === "starting";
  // Polling ogni 10 s e orologio, solo mentre si accende (Q-674): a vetrina pronta, spenta o bloccata non parte nessun
  // timer. Tetto di 15 minuti dall'origine dell'attesa: poi «Non pronta» con «Controlla di nuovo» (Q-728).
  useEffect(() => {
    if (!starting) return;
    const origin = originRef.current ?? Date.now();
    const poll = window.setInterval(refresh, POLL_MS);
    const tick = window.setInterval(() => {
      const at = Date.now();
      setNow(at);
      if (at - origin >= MAX_STARTING_MS) setView((v) => (v.kind === "starting" ? { kind: "stuck", checks: v.checks, times: v.times } : v));
    }, 1000);
    return () => {
      window.clearInterval(poll);
      window.clearInterval(tick);
    };
  }, [starting, refresh]);

  function start() {
    const at = Date.now();
    startedRef.current = at;
    originRef.current = at;
    setNow(at);
    setView({ kind: "starting", checks: allPending(), times: {}, showElapsed: true });
    call("POST").then(
      (r) => {
        apply(r);
        // Vetrina già accesa: il POST non porta le risorse, le chiede subito una lettura.
        if (r.state === "available" && r.checks === null) refresh();
      },
      () => {
        startedRef.current = null;
        originRef.current = null;
        setView({ kind: "error", reason: "start" });
      },
    );
  }

  function recheck() {
    // «Controlla di nuovo» non riaccende niente: rilegge e riparte il tetto di 15 minuti (flusso del mockup).
    originRef.current = Date.now();
    setView({ kind: "loading" });
    refresh();
  }

  const elapsedMs = view.kind === "starting" && view.showElapsed && startedRef.current !== null ? Math.max(0, now - startedRef.current) : null;
  const done = view.kind === "starting" ? readyCount(view.checks) : 0;
  const status =
    view.kind === "starting" ? t.progressStatus(done, TOTAL_CHECKS) : view.kind === "ready" ? t.readyStatus : "";

  return (
    <Card data-testid="enterprise-box" aria-labelledby="enterprise-box-title" role="region">
      <CardBody className="pt-4">
        <Header pill={view.kind} />
        <Steps />
        {/* Annuncio educato dei soli cambi di avanzamento (non dell'orologio che scorre ogni secondo). */}
        <p className="sr-only" role="status" aria-live="polite" data-testid="enterprise-status">
          {status}
        </p>
        <div className="mt-4" data-testid="enterprise-action" aria-busy={view.kind === "loading" || view.kind === "starting"}>
          {view.kind === "loading" ? (
            <div data-testid="enterprise-skeleton">
              <span className="sr-only">{t.loading}</span>
              <Button disabled>
                <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> {t.checking}
              </Button>
              <p className={NOTE}>{t.checkingNote}</p>
            </div>
          ) : null}
          {view.kind === "off" ? (
            <>
              <Button onClick={start}>
                <Power className="h-4 w-4" aria-hidden /> {t.start}
              </Button>
              <p className={NOTE}>{t.startNote}</p>
            </>
          ) : null}
          {view.kind === "starting" ? (
            <>
              <Button disabled>
                <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />{" "}
                {t.starting(elapsedMs === null ? null : formatElapsed(elapsedMs))}
              </Button>
              <div
                className="mt-2 h-2 w-full max-w-xs overflow-hidden rounded-full bg-[var(--color-bo-bg)]"
                role="progressbar"
                aria-label={t.progressLabel}
                aria-valuemin={0}
                aria-valuemax={TOTAL_CHECKS}
                aria-valuenow={done}
              >
                <div
                  className="h-full rounded-full bg-[var(--color-state-waking)] transition-all"
                  style={{ width: `${Math.round((done / TOTAL_CHECKS) * 100)}%` }}
                />
              </div>
              <Checklist checks={view.checks} times={view.times} />
              <p className={NOTE}>{view.checks.codespace === "ok" ? t.resourcesNote : t.startingNote}</p>
            </>
          ) : null}
          {view.kind === "ready" ? (
            <>
              <a
                href={url}
                rel="noopener"
                className="inline-flex items-center justify-center gap-2 rounded-md bg-[var(--color-bo-accent)] px-4 py-2 text-sm font-semibold text-white hover:opacity-90"
              >
                {t.open} <ArrowUpRight className="h-4 w-4" aria-hidden />
                <span className="sr-only">{t.openExternal}</span>
              </a>
              <ul className="mt-3 grid gap-1.5 text-sm" data-testid="enterprise-checks">
                <li className="grid grid-cols-[18px_1fr_auto] items-center gap-2">
                  <Icon phase="ok" />
                  <span>{t.allReady(TOTAL_CHECKS)}</span>
                  <span className="font-mono text-xs tabular-nums text-[var(--color-bo-ink-2)]">
                    {view.totalMs === null ? "" : formatElapsed(view.totalMs)}
                  </span>
                </li>
              </ul>
              <p className={NOTE}>{t.openNote}</p>
            </>
          ) : null}
          {view.kind === "stuck" ? <Stuck checks={view.checks} times={view.times} onRecheck={recheck} /> : null}
          {view.kind === "error" ? (
            <div role="alert">
              <p className="text-sm text-[var(--color-state-down)]">{t.errors[view.reason]}</p>
              <Button
                variant="ghost"
                className="mt-2"
                onClick={() => {
                  startedRef.current = null;
                  originRef.current = null;
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

type Phase = "ok" | "wait" | "todo" | "ko";

function Icon({ phase }: { phase: Phase }) {
  const style: Record<Phase, string> = {
    ok: "bg-[var(--color-state-up)] text-white",
    ko: "bg-[var(--color-state-down)] text-white",
    wait: "border-2 border-[var(--color-state-waking)]",
    todo: "border-2 border-[var(--color-bo-border)]",
  };
  return (
    <span className={cn("grid h-4 w-4 place-items-center rounded-full text-[10px] font-bold", style[phase])} aria-hidden>
      {phase === "ok" ? "✓" : phase === "ko" ? "!" : ""}
    </span>
  );
}

/** Elenco delle nove risorse (mockup HUB-01): spunta, in corso o in attesa, con il tempo di comparsa se noto. */
function Checklist({ checks, times }: { checks: Checks; times: Times }) {
  return (
    <ul className="mt-3 grid gap-1.5 text-sm" aria-label={t.checksLabel} data-testid="enterprise-checks">
      {CHECK_KEYS.map((k) => {
        const phase = itemPhase(checks, k);
        const time = phase === "ok" ? (times[k] === undefined ? "" : formatElapsed(times[k])) : phase === "wait" ? t.item.wait : "";
        return (
          <li key={k} className="grid grid-cols-[18px_1fr_auto] items-center gap-2" data-testid={`check-${k}`} data-phase={phase}>
            <Icon phase={phase} />
            <span>
              {t.checks[k]}
              <span className="sr-only">: {t.item[phase]}</span>
            </span>
            <span className="font-mono text-xs tabular-nums text-[var(--color-bo-ink-2)]" aria-hidden>
              {time}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** «Non pronta» dopo 15 minuti (mockup, frame 5): le risorse già pronte in una riga, quelle che mancano una per una. */
function Stuck({ checks, times, onRecheck }: { checks: Checks; times: Times; onRecheck: () => void }) {
  const missing = pendingKeys(checks);
  const ready = CHECK_KEYS.filter((k) => checks[k] === "ok");
  const readyAt = ready.map((k) => times[k]).filter((x): x is number => x !== undefined);
  const readyTime = readyAt.length > 0 ? formatElapsed(Math.max(...readyAt)) : "";
  const names = ready.map((k) => t.checksShort[k]).join(", ");
  const first = missing[0];
  return (
    <div role="alert" data-testid="enterprise-stuck">
      <ul className="grid gap-1.5 text-sm" data-testid="enterprise-checks">
        {ready.length > 0 ? (
          <li className="grid grid-cols-[18px_1fr_auto] items-center gap-2">
            <Icon phase="ok" />
            <span>{names.charAt(0).toUpperCase() + names.slice(1)}</span>
            <span className="font-mono text-xs tabular-nums text-[var(--color-bo-ink-2)]">{readyTime}</span>
          </li>
        ) : null}
        {missing.map((k) => (
          <li key={k} className="grid grid-cols-[18px_1fr_auto] items-center gap-2" data-testid={`check-${k}`} data-phase="ko">
            <Icon phase="ko" />
            <span>{t.checks[k]}</span>
            <span className="font-mono text-xs text-[var(--color-state-down)]">{t.stuckState[k]}</span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-sm text-[var(--color-state-down)]">{t.stuckText[first]}</p>
      <Button variant="ghost" className="mt-2" onClick={onRecheck}>
        <RefreshCw className="h-4 w-4" aria-hidden /> {t.recheck}
      </Button>
      <p className={NOTE}>{t.stuckHint}</p>
    </div>
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
