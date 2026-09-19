"use client";

import { useQuery, useMutation, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import type { ServiceCode } from "./services";

// Client del proxy (docs/07 §3): il browser chiama sempre /api/lh/<service>/v1/...
// Distingue l'errore "servizio addormentato" (503 SERVICE_ASLEEP) dagli errori applicativi (RFC 9457).

export class LhError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly asleep: boolean,
  ) {
    super(detail || code);
  }
}

export interface Page<T> {
  items: T[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
}

function url(service: ServiceCode, path: string, query?: Record<string, string | number | undefined>): string {
  const clean = path.startsWith("/") ? path : `/${path}`;
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v !== undefined && v !== "") search.set(k, String(v));
  }
  const qs = search.toString();
  return `/api/lh/${service}${clean}${qs ? `?${qs}` : ""}`;
}

export async function lhFetch<T>(
  service: ServiceCode,
  path: string,
  init?: RequestInit & { query?: Record<string, string | number | undefined> },
): Promise<T> {
  const { query, ...rest } = init ?? {};
  const res = await fetch(url(service, path, query), {
    ...rest,
    headers: { "content-type": "application/json", ...(rest.headers ?? {}) },
  });
  const text = await res.text();
  const body = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const asleep = res.status === 503 && body?.type === "SERVICE_ASLEEP";
    throw new LhError(
      res.status,
      body?.code ?? body?.type ?? `HTTP_${res.status}`,
      body?.detail ?? body?.title ?? text,
      asleep,
    );
  }
  return body as T;
}

function safeJson(text: string): { [key: string]: unknown } & Record<string, string> {
  try {
    return JSON.parse(text);
  } catch {
    return {} as never;
  }
}

/** Query verso un servizio; la chiave include servizio, path e parametri. */
export function useLhQuery<T>(
  service: ServiceCode,
  path: string,
  query?: Record<string, string | number | undefined>,
  options?: { enabled?: boolean; refetchInterval?: number },
): UseQueryResult<T, LhError> {
  return useQuery<T, LhError>({
    queryKey: [service, path, query ?? {}],
    queryFn: () => lhFetch<T>(service, path, { query }),
    enabled: options?.enabled,
    refetchInterval: options?.refetchInterval,
  });
}

/** Mutazione POST/PATCH/PUT; invalida le query del servizio al successo. */
export function useLhMutation<TResult, TBody>(
  service: ServiceCode,
  method: "POST" | "PATCH" | "PUT",
  pathFor: (body: TBody) => string,
  options?: { onSuccess?: (result: TResult) => void; invalidate?: boolean },
) {
  const qc = useQueryClient();
  return useMutation<TResult, LhError, TBody>({
    mutationFn: (body: TBody) =>
      lhFetch<TResult>(service, pathFor(body), { method, body: JSON.stringify(body) }),
    onSuccess: (result) => {
      if (options?.invalidate !== false) qc.invalidateQueries({ queryKey: [service] });
      options?.onSuccess?.(result);
    },
  });
}
