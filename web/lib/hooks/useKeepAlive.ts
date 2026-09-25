"use client";

import { useEffect, useState } from "react";
import { createKeepAlive } from "@/lib/keepalive/keepAlive";

// Keep-alive gentile (docs/07 §8, F-DEMO-07): ogni 4 min tocca /api/demo/wake SOLO se la scheda è
// visibile; si ferma dopo 45 min senza interazione. Nessun pinger esterno, mai. Logica in lib/keepalive.

/** Eventi che contano come interazione dell'utente. */
const INTERACTION_EVENTS = ["pointerdown", "keydown", "wheel"] as const;

/** Monta il keep-alive; restituisce `true` quando si è fermato per inattività. */
export function useKeepAlive(): boolean {
  const [idle, setIdle] = useState(false);

  useEffect(() => {
    const ka = createKeepAlive({
      now: () => Date.now(),
      isVisible: () => document.visibilityState === "visible",
      wake: () => {
        void fetch("/api/demo/wake", { method: "POST" }).catch(() => undefined);
      },
      onIdleChange: setIdle,
    });
    const mark = () => ka.markInteraction();
    for (const ev of INTERACTION_EVENTS) window.addEventListener(ev, mark, { passive: true });

    return () => {
      ka.dispose();
      for (const ev of INTERACTION_EVENTS) window.removeEventListener(ev, mark);
    };
  }, []);

  return idle;
}
