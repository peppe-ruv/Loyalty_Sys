import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createKeepAlive, type KeepAlive } from "./keepAlive";

// Testbook TB-WEB §KA: keep-alive gentile (docs/07 §8, F-DEMO-07): ogni 4 min chiama il risveglio SOLO se la scheda è
// visibile; si ferma dopo 45 min senza interazione; una nuova interazione lo fa ripartire (Q-132).

const MIN = 60_000;
let visible: boolean;
let wake: ReturnType<typeof vi.fn>;
let ka: KeepAlive;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-09-18T10:00:00Z"));
  visible = true;
  wake = vi.fn();
  ka = createKeepAlive({ now: () => Date.now(), isVisible: () => visible, wake });
});
afterEach(() => {
  ka.dispose();
  vi.useRealTimers();
});

it("[TB-WEB-KA-001] scheda visibile: nessuna chiamata a 3:59, una a 4:00", () => {
  vi.advanceTimersByTime(4 * MIN - 1_000);
  expect(wake).toHaveBeenCalledTimes(0);
  vi.advanceTimersByTime(1_000);
  expect(wake).toHaveBeenCalledTimes(1);
});

it("[TB-WEB-KA-002] scheda nascosta → nessuna chiamata a 4 min", () => {
  visible = false;
  vi.advanceTimersByTime(4 * MIN);
  expect(wake).not.toHaveBeenCalled();
});

it("[TB-WEB-KA-003] senza interazioni: l'ultimo risveglio è a 44 min (sotto i 45)", () => {
  vi.advanceTimersByTime(44 * MIN);
  expect([wake.mock.calls.length, ka.isIdle()]).toEqual([11, false]);
});

it("[TB-WEB-KA-004] oltre 45 min senza interazioni (controllo dei 48 min) → fermo, nessun'altra chiamata", () => {
  vi.advanceTimersByTime(48 * MIN);
  expect(ka.isIdle()).toBe(true);
  vi.advanceTimersByTime(60 * MIN);
  expect(wake).toHaveBeenCalledTimes(11);
});

it("[TB-WEB-KA-005] interazione dopo lo stop → riparte, senza risveglio immediato, poi ogni 4 min (Q-132)", () => {
  vi.advanceTimersByTime(48 * MIN);
  ka.markInteraction();
  expect([ka.isIdle(), wake.mock.calls.length]).toEqual([false, 11]);
  vi.advanceTimersByTime(4 * MIN);
  expect(wake).toHaveBeenCalledTimes(12);
});

it("[TB-WEB-KA-006] un'interazione a 40 min sposta la finestra: a 48 min si chiama ancora", () => {
  vi.advanceTimersByTime(40 * MIN);
  ka.markInteraction();
  vi.advanceTimersByTime(8 * MIN);
  expect([ka.isIdle(), wake.mock.calls.length]).toEqual([false, 12]);
});

it("[TB-WEB-KA-007] smontato (dispose) → nessuna chiamata", () => {
  ka.dispose();
  vi.advanceTimersByTime(20 * MIN);
  expect(wake).not.toHaveBeenCalled();
});
