import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createKeepAlive, KEEPALIVE_IDLE_LIMIT_MS, KEEPALIVE_INTERVAL_MS, type KeepAlive } from "./keepAlive";

const MIN = 60_000;

describe("keepalive/createKeepAlive (F-DEMO-07)", () => {
  let visible: boolean;
  let wake: ReturnType<typeof vi.fn>;
  let onIdleChange: ReturnType<typeof vi.fn>;
  let ka: KeepAlive;

  beforeEach(() => {
    vi.useFakeTimers();
    visible = true;
    wake = vi.fn();
    onIdleChange = vi.fn();
    ka = createKeepAlive({ now: () => Date.now(), isVisible: () => visible, wake, onIdleChange });
  });

  afterEach(() => {
    ka.dispose();
    vi.useRealTimers();
  });

  it("usa i tempi della spec: 4 min di cadenza, 45 min di inattività", () => {
    expect(KEEPALIVE_INTERVAL_MS).toBe(4 * MIN);
    expect(KEEPALIVE_IDLE_LIMIT_MS).toBe(45 * MIN);
  });

  it("chiama wake ogni 4 minuti, non prima", () => {
    vi.advanceTimersByTime(4 * MIN - 1);
    expect(wake).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(wake).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(4 * MIN);
    expect(wake).toHaveBeenCalledTimes(2);
  });

  it("con la scheda nascosta non chiama mai wake", () => {
    visible = false;
    vi.advanceTimersByTime(20 * MIN);
    expect(wake).not.toHaveBeenCalled();
    visible = true;
    vi.advanceTimersByTime(4 * MIN);
    expect(wake).toHaveBeenCalledTimes(1);
  });

  it("si ferma dopo 45 min senza interazione e non chiama più wake", () => {
    vi.advanceTimersByTime(44 * MIN);
    expect(wake).toHaveBeenCalledTimes(11); // 4, 8, …, 44
    expect(ka.isIdle()).toBe(false);
    vi.advanceTimersByTime(4 * MIN); // tick a 48 min: oltre il limite
    expect(wake).toHaveBeenCalledTimes(11);
    expect(ka.isIdle()).toBe(true);
    expect(onIdleChange).toHaveBeenCalledWith(true);
    // Fermo davvero: nessun timer residuo.
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(120 * MIN);
    expect(wake).toHaveBeenCalledTimes(11);
  });

  it("un'interazione sposta in avanti la finestra di 45 minuti", () => {
    vi.advanceTimersByTime(40 * MIN);
    ka.markInteraction();
    vi.advanceTimersByTime(40 * MIN); // 80 min dall'apertura, 40 dall'ultima interazione
    expect(ka.isIdle()).toBe(false);
    expect(wake).toHaveBeenCalledTimes(20);
    vi.advanceTimersByTime(8 * MIN); // 48 dall'ultima interazione
    expect(ka.isIdle()).toBe(true);
    expect(wake).toHaveBeenCalledTimes(21); // il tick a 84 min (44 dall'interazione) passa ancora
  });

  it("dopo lo stop un'interazione lo fa ripartire con cadenza da capo, senza risveglio immediato", () => {
    vi.advanceTimersByTime(48 * MIN);
    expect(ka.isIdle()).toBe(true);
    const before = wake.mock.calls.length;
    ka.markInteraction();
    expect(ka.isIdle()).toBe(false);
    expect(onIdleChange).toHaveBeenLastCalledWith(false);
    expect(wake).toHaveBeenCalledTimes(before);
    vi.advanceTimersByTime(4 * MIN);
    expect(wake).toHaveBeenCalledTimes(before + 1);
  });

  it("dispose ferma tutto e ignora interazioni successive", () => {
    ka.dispose();
    ka.markInteraction();
    vi.advanceTimersByTime(60 * MIN);
    expect(wake).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});
