"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { streamUrl, type LiveEvent } from "@/lib/realtime/sse";

// "Il saldo non mente" (docs/09 §2): dopo un'azione demo il portale mostra "in arrivo…" e si chiude appena
// arriva il fatto del wallet. Da M2.3 ascolta l'SSE di insight filtrato per correlationId (usePendingTrace);
// il polling del wallet resta come fallback se l'SSE non è disponibile.

interface PendingState {
  pending: boolean;
  markPending: (labels: string[], correlationId?: string) => void;
  labels: string[];
}

const PENDING_WINDOW_MS = 30_000;
const Ctx = createContext<PendingState>({ pending: false, markPending: () => {}, labels: [] });

export function PendingProvider({ children }: { children: React.ReactNode }) {
  const [until, setUntil] = useState(0);
  const [labels, setLabels] = useState<string[]>([]);
  const correlationId = useRef<string | null>(null);
  const qc = useQueryClient();

  const markPending = useCallback((next: string[], correlation?: string) => {
    correlationId.current = correlation ?? null;
    setLabels(next);
    setUntil(Date.now() + PENDING_WINDOW_MS);
  }, []);

  const pending = until > Date.now();

  useEffect(() => {
    if (!pending) return;

    const resolve = () => {
      qc.invalidateQueries({ queryKey: ["wallet"] });
      setLabels([]);
      setUntil(0);
    };

    // Via principale (M2.3): SSE filtrato per correlationId, si chiude al fatto wallet.points.earned.
    let source: EventSource | null = null;
    if (correlationId.current && typeof window !== "undefined") {
      try {
        source = new EventSource(streamUrl({ correlationId: correlationId.current }));
        source.addEventListener("lh-event", (e: MessageEvent) => {
          try {
            const ev = JSON.parse(e.data) as LiveEvent;
            // Ogni fatto del giro aggiorna il saldo; il "earned" chiude l'attesa.
            qc.invalidateQueries({ queryKey: ["wallet"] });
            if (ev.shortType === "wallet.points.earned") resolve();
          } catch {
            // riga malformata ignorata
          }
        });
      } catch {
        source = null;
      }
    }

    // Fallback: polling del wallet (5 s) e chiusura della finestra allo scadere.
    const timer = setInterval(() => qc.invalidateQueries({ queryKey: ["wallet"] }), 5000);
    const stop = setTimeout(() => {
      setLabels([]);
      setUntil(0);
    }, Math.max(0, until - Date.now()));

    return () => {
      source?.close();
      clearInterval(timer);
      clearTimeout(stop);
    };
  }, [pending, until, qc]);

  return <Ctx.Provider value={{ pending, markPending, labels }}>{children}</Ctx.Provider>;
}

export function usePending(): PendingState {
  return useContext(Ctx);
}
