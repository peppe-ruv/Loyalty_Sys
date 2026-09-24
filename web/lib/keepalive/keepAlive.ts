// Keep-alive gentile (docs/07 §8, F-DEMO-07, ADR-014): logica pura, senza React né DOM, per poterla testare con
// timer finti. Ogni 4 min tocca /api/demo/wake SOLO se la scheda è visibile; dopo 45 min senza interazione si ferma
// (nessun timer attivo, nessuna chiamata). Nessun pinger esterno, mai.

export const KEEPALIVE_INTERVAL_MS = 4 * 60_000;
export const KEEPALIVE_IDLE_LIMIT_MS = 45 * 60_000;

export interface KeepAliveEnv {
  now: () => number;
  isVisible: () => boolean;
  wake: () => void;
  /** Notificato quando il keep-alive si ferma per inattività (`true`) o riparte (`false`). */
  onIdleChange?: (idle: boolean) => void;
  intervalMs?: number;
  idleLimitMs?: number;
}

export interface KeepAlive {
  /** Da chiamare a ogni interazione dell'utente (tocco, clic, tasto, rotella). */
  markInteraction: () => void;
  /** Vero dopo 45 min senza interazione: il timer è fermo. */
  isIdle: () => boolean;
  /** Smonta: ferma il timer per sempre. */
  dispose: () => void;
}

export function createKeepAlive(env: KeepAliveEnv): KeepAlive {
  const intervalMs = env.intervalMs ?? KEEPALIVE_INTERVAL_MS;
  const idleLimitMs = env.idleLimitMs ?? KEEPALIVE_IDLE_LIMIT_MS;
  let lastInteraction = env.now();
  let timer: ReturnType<typeof setInterval> | null = null;
  let idle = false;
  let disposed = false;

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function tick() {
    if (env.now() - lastInteraction >= idleLimitMs) {
      // Si ferma davvero: niente timer finché l'utente non torna a interagire.
      stopTimer();
      idle = true;
      env.onIdleChange?.(true);
      return;
    }
    if (!env.isVisible()) return;
    env.wake();
  }

  function startTimer() {
    stopTimer();
    timer = setInterval(tick, intervalMs);
  }

  startTimer();

  return {
    markInteraction() {
      if (disposed) return;
      lastInteraction = env.now();
      if (idle) {
        // SPEC-GAP: Q-C1 — la spec dice solo "si ferma dopo 45 min senza interazione"; scelta: una nuova
        // interazione lo fa ripartire con cadenza da capo (nessun risveglio immediato).
        idle = false;
        env.onIdleChange?.(false);
        startTimer();
      }
    },
    isIdle: () => idle,
    dispose() {
      disposed = true;
      stopTimer();
    },
  };
}
