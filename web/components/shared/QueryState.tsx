"use client";

import type { UseQueryResult } from "@tanstack/react-query";
import type { LhError } from "@/lib/api/client";

// Stati loading / empty / error / degraded (docs/07 §6): un solo punto per tutte le viste, condiviso da backoffice e
// portale (che non si importano a vicenda, CLAUDE.md §5).
export function QueryState<T>({
  query,
  service,
  isEmpty,
  emptyTitle = "Nessun dato",
  emptyHint,
  children,
}: {
  query: UseQueryResult<T, LhError>;
  service: string;
  isEmpty?: (data: T) => boolean;
  emptyTitle?: string;
  emptyHint?: string;
  children: (data: T) => React.ReactNode;
}) {
  if (query.isLoading) {
    return <SkeletonRows />;
  }
  if (query.isError) {
    if (query.error.asleep) {
      return <DegradedBox service={service} onRetry={() => query.refetch()} />;
    }
    return (
      <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        <p className="font-medium">Errore: {query.error.code}</p>
        <p className="mt-1 text-xs">{query.error.detail}</p>
        <button
          onClick={() => query.refetch()}
          className="mt-2 rounded border border-red-300 px-2 py-1 text-xs hover:bg-red-100"
        >
          Riprova
        </button>
      </div>
    );
  }
  const data = query.data as T;
  if (isEmpty && isEmpty(data)) {
    return <EmptyState title={emptyTitle} hint={emptyHint} />;
  }
  return <>{children(data)}</>;
}

function SkeletonRows() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-9 animate-pulse rounded bg-slate-100" />
      ))}
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="rounded-md border border-dashed border-[var(--color-bo-border)] p-8 text-center">
      <p className="text-sm font-medium text-[var(--color-bo-ink)]">{title}</p>
      {hint ? <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{hint}</p> : null}
    </div>
  );
}

export function DegradedBox({ service, onRetry }: { service: string; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800">
      <p className="font-medium">Servizio «{service}» non raggiungibile</p>
      <p className="mt-1 text-xs">
        Probabilmente è addormentato (piano gratuito). Accendi la demo dal Demo Hub, poi riprova.
      </p>
      {onRetry ? (
        <button onClick={onRetry} className="mt-2 rounded border border-amber-300 px-2 py-1 text-xs hover:bg-amber-100">
          Riprova
        </button>
      ) : null}
    </div>
  );
}
