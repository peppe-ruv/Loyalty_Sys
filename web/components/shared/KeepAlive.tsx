"use client";

import { createContext, useContext } from "react";
import { useKeepAlive } from "@/lib/hooks/useKeepAlive";

const IdleContext = createContext(false);

/**
 * Monta il keep-alive gentile nei layout delle aree (docs/07 §8, F-DEMO-07). Senza figli non rende nulla; con i
 * figli espone anche lo stato "fermo per inattività" (`useKeepAliveIdle`), usato dal Demo Hub per sospendere il
 * proprio polling dello stato.
 */
export function KeepAlive({ children }: { children?: React.ReactNode }) {
  const idle = useKeepAlive();
  return children === undefined ? null : <IdleContext.Provider value={idle}>{children}</IdleContext.Provider>;
}

/** `true` se il keep-alive dell'area si è fermato dopo 45 min senza interazione. */
export function useKeepAliveIdle(): boolean {
  return useContext(IdleContext);
}
