"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { UseQueryResult } from "@tanstack/react-query";
import type { LhError } from "@/lib/api/client";
import { formatTime } from "@/lib/format/dates";

// Stati loading / empty / error / degraded (docs/07 §6): un solo punto per tutte le viste, condiviso da backoffice e
// portale (che non si importano a vicenda, CLAUDE.md §5).

/** Azione primaria dello stato vuoto (docs/07 §6 Empty: "Nessuna campagna in bozza. Crea la prima"). */
export interface EmptyAction {
  label: string;
  href?: string;
  onClick?: () => void;
}

export function QueryState<T>({
  query,
  service,
  isEmpty,
  emptyTitle = "Nessun dato",
  emptyHint,
  emptyAction,
  skeletonRows = 8,
  children,
}: {
  query: UseQueryResult<T, LhError>;
  service: string;
  isEmpty?: (data: T) => boolean;
  emptyTitle?: string;
  emptyHint?: string;
  /** Azione primaria del vuoto; senza, il vuoto offre *Aggiorna* (i dati arrivano dagli eventi, possono comparire). */
  emptyAction?: EmptyAction;
  /** Righe dello scheletro: 8 per le tabelle (docs/07 §6 Loading). */
  skeletonRows?: number;
  children: (data: T) => React.ReactNode;
}) {
  if (query.isLoading) {
    return <SkeletonRows rows={skeletonRows} />;
  }
  if (query.isError) {
    if (query.error.asleep) {
      const degraded = <DegradedBox service={service} onRetry={() => query.refetch()} autoRetry />;
      // Con un dato già noto la vista resta piena (docs/09 §2: "la tessera usa l'ultimo saldo noto con l'etichetta
      // «aggiornato alle 10:42»"): riquadro degraded in testa, poi l'ultimo dato con l'ora dell'aggiornamento.
      if (query.data !== undefined) {
        return (
          <div className="space-y-2">
            {degraded}
            <p className="text-xs text-[var(--color-bo-ink-2)]">aggiornato alle {formatTime(query.dataUpdatedAt || Date.now())}</p>
            {children(query.data as T)}
          </div>
        );
      }
      return degraded;
    }
    return <ErrorBox error={query.error} onRetry={() => query.refetch()} />;
  }
  const data = query.data as T;
  if (isEmpty && isEmpty(data)) {
    return (
      <EmptyState
        title={emptyTitle}
        hint={emptyHint}
        action={emptyAction ?? { label: "Aggiorna", onClick: () => void query.refetch() }}
      />
    );
  }
  return <>{children(data)}</>;
}

/** Errore applicativo (docs/07 §6 Error): `title` del problema RFC 9457, `detail`, `correlationId` copiabile, Riprova. */
function ErrorBox({ error, onRetry }: { error: LhError; onRetry: () => void }) {
  const [copied, setCopied] = useState(false);
  const title = error.title ?? `Errore: ${error.code}`;
  const copy = () => {
    if (!error.correlationId) return;
    void navigator.clipboard?.writeText(error.correlationId).then(
      () => setCopied(true),
      () => undefined,
    );
  };
  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      <p className="font-medium">{title}</p>
      {error.detail && error.detail !== title ? <p className="mt-1 text-xs">{error.detail}</p> : null}
      {error.correlationId ? (
        <p className="mt-1 flex flex-wrap items-center gap-1 text-xs">
          <span>Correlazione:</span>
          <code className="select-all font-mono">{error.correlationId}</code>
          <button type="button" onClick={copy} className="underline" aria-label="Copia l'identificativo di correlazione">
            {copied ? "copiato" : "copia"}
          </button>
        </p>
      ) : null}
      <button onClick={onRetry} className="mt-2 rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100">
        Riprova
      </button>
    </div>
  );
}

function SkeletonRows({ rows }: { rows: number }) {
  return (
    <div className="space-y-2" aria-busy="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="h-9 animate-pulse rounded bg-slate-100" />
      ))}
    </div>
  );
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: EmptyAction }) {
  return (
    <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-8 text-center">
      <p aria-hidden className="text-2xl text-slate-300">∅</p>
      <p className="text-sm font-medium text-[var(--color-bo-ink)]">{title}</p>
      {hint ? <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{hint}</p> : null}
      {action ? (
        action.href ? (
          <Link
            href={action.href}
            className="mt-3 inline-block rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-xs font-medium text-white"
          >
            {action.label}
          </Link>
        ) : (
          <button
            type="button"
            onClick={action.onClick}
            className="mt-3 rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-xs font-medium hover:bg-slate-50"
          >
            {action.label}
          </button>
        )
      ) : null}
    </div>
  );
}

/** Riprova automatica del degraded (docs/07 §6): ogni 5 s, per al massimo 90 s. */
export const WAKE_RETRY_MS = 5_000;
export const WAKE_RETRY_MAX_MS = 90_000;

export function DegradedBox({
  service,
  onRetry,
  autoRetry = false,
}: {
  service: string;
  onRetry?: () => void;
  /** Riprova da sola ogni 5 s fino a 90 s (il servizio gratuito impiega fino a un minuto a svegliarsi). */
  autoRetry?: boolean;
}) {
  const retry = useRef(onRetry);
  useEffect(() => {
    retry.current = onRetry;
  }, [onRetry]);
  const [gaveUp, setGaveUp] = useState(false);

  useEffect(() => {
    if (!autoRetry || !retry.current) return;
    let elapsed = 0;
    const timer = setInterval(() => {
      elapsed += WAKE_RETRY_MS;
      retry.current?.();
      if (elapsed >= WAKE_RETRY_MAX_MS) {
        clearInterval(timer);
        setGaveUp(true);
      }
    }, WAKE_RETRY_MS);
    return () => clearInterval(timer);
  }, [autoRetry]);

  return (
    <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800">
      <p className="font-medium">Il servizio {service} si sta svegliando…</p>
      {gaveUp || !autoRetry ? (
        <p className="mt-1 text-xs">
          Sul piano gratuito può restare addormentato: accendi la demo dal Demo Hub, poi riprova.
        </p>
      ) : (
        <>
          <div className="mt-2 h-1 overflow-hidden rounded bg-amber-100" aria-hidden>
            <div className="h-full w-1/3 animate-pulse rounded bg-amber-400" />
          </div>
          <p className="mt-1 text-xs">Riprovo da solo ogni 5 secondi; il resto della pagina resta utilizzabile.</p>
        </>
      )}
      {onRetry ? (
        <button onClick={onRetry} className="mt-2 rounded border border-amber-300 px-2 py-1 text-xs hover:bg-amber-100">
          Riprova
        </button>
      ) : null}
    </div>
  );
}
