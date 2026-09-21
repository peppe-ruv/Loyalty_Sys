"use client";

import { useEffect } from "react";

// Keep-alive gentile (docs/07 §8, F-DEMO-07): ogni 4 min tocca /api/demo/wake SOLO se la scheda è
// visibile; si ferma dopo 45 min senza interazione. Nessun pinger esterno, mai.
export function useKeepAlive() {
  useEffect(() => {
    let lastInteraction = Date.now();
    const mark = () => {
      lastInteraction = Date.now();
    };
    window.addEventListener("pointerdown", mark);
    window.addEventListener("keydown", mark);

    const id = window.setInterval(
      () => {
        if (document.visibilityState !== "visible") return;
        if (Date.now() - lastInteraction > 45 * 60_000) return;
        void fetch("/api/demo/wake", { method: "POST" }).catch(() => undefined);
      },
      4 * 60_000,
    );

    return () => {
      window.clearInterval(id);
      window.removeEventListener("pointerdown", mark);
      window.removeEventListener("keydown", mark);
    };
  }, []);
}
