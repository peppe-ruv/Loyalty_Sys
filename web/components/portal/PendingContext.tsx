"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";

// "Il saldo non mente" (docs/09 §2): dopo un'azione demo il portale mostra "in arrivo…" e, senza SSE (M2),
// fa polling per qualche secondo finché il fatto del wallet non arriva. Qui coordiniamo quella finestra.

interface PendingState {
  pending: boolean;
  markPending: (labels: string[]) => void;
  labels: string[];
}

const PENDING_WINDOW_MS = 30_000;
const Ctx = createContext<PendingState>({ pending: false, markPending: () => {}, labels: [] });

export function PendingProvider({ children }: { children: React.ReactNode }) {
  const [until, setUntil] = useState(0);
  const [labels, setLabels] = useState<string[]>([]);
  const qc = useQueryClient();

  const markPending = useCallback((next: string[]) => {
    setLabels(next);
    setUntil(Date.now() + PENDING_WINDOW_MS);
  }, []);

  const pending = until > Date.now();

  // Finché è pending, invalida periodicamente le query di wallet e attività (polling 5 s).
  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => {
      qc.invalidateQueries({ queryKey: ["wallet"] });
    }, 5000);
    const stop = setTimeout(() => {
      setLabels([]);
      setUntil(0);
    }, Math.max(0, until - Date.now()));
    return () => {
      clearInterval(timer);
      clearTimeout(stop);
    };
  }, [pending, until, qc]);

  return <Ctx.Provider value={{ pending, markPending, labels }}>{children}</Ctx.Provider>;
}

export function usePending(): PendingState {
  return useContext(Ctx);
}
