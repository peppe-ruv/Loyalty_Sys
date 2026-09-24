import { describe, it, expect } from "vitest";
import {
  displayState,
  entrancesReady,
  formatElapsed,
  kafkaLongDown,
  statusPollInterval,
  trackKafkaDown,
  wakeComplete,
  type DemoStatus,
  type ServiceState,
} from "./status";

const ALL = ["ingestion", "member", "campaign", "wallet", "reward", "gamification", "engagement", "insight"];

function status(
  upCodes: string[],
  opts: { kafka?: ServiceState; db?: ServiceState; at?: string; downState?: ServiceState } = {},
): DemoStatus {
  const kafka = opts.kafka ?? "UP";
  const db = opts.db ?? "UP";
  return {
    services: ALL.map((code) => ({
      code, name: code, state: upCodes.includes(code) ? "UP" : (opts.downState ?? "SLEEPING"), latencyMs: null,
    })),
    kafka: { state: kafka },
    db: { state: db },
    readyCount: upCodes.length + (kafka === "UP" ? 1 : 0) + (db === "UP" ? 1 : 0),
    totalCount: 10,
    checkedAt: opts.at ?? "2026-09-24T10:00:00.000Z",
  };
}

describe("api/status — ingressi (HUB-01)", () => {
  it("attiva gli ingressi solo con i 4 servizi core UP", () => {
    expect(entrancesReady(status(["ingestion", "member", "campaign"]))).toBe(false);
    expect(entrancesReady(status(["ingestion", "member", "campaign", "wallet"]))).toBe(true);
  });

  it("ogni servizio core mancante blocca gli ingressi; quelli non core no", () => {
    for (const missing of ["ingestion", "member", "campaign", "wallet"]) {
      const up = ["ingestion", "member", "campaign", "wallet"].filter((c) => c !== missing);
      expect(entrancesReady(status([...up, "reward", "insight"]))).toBe(false);
    }
    expect(entrancesReady(status(["ingestion", "member", "campaign", "wallet"], { kafka: "DOWN", db: "SLEEPING" }))).toBe(true);
  });

  it("un core DOWN non conta come pronto; stato ignoto ⇒ ingressi spenti", () => {
    expect(entrancesReady(status(["ingestion", "member", "campaign"], { downState: "DOWN" }))).toBe(false);
    expect(entrancesReady(undefined)).toBe(false);
  });
});

describe("api/status — risveglio e tessere", () => {
  it("durante il risveglio ciò che non è UP appare WAKING", () => {
    expect(displayState("SLEEPING", true)).toBe("WAKING");
    expect(displayState("DOWN", true)).toBe("WAKING");
    expect(displayState("UP", true)).toBe("UP");
    expect(displayState("SLEEPING", false)).toBe("SLEEPING");
    expect(displayState("DOWN", false)).toBe("DOWN");
  });

  it("il risveglio è concluso solo a 10/10", () => {
    expect(wakeComplete(status(ALL.slice(0, 7)))).toBe(false);
    expect(wakeComplete(status(ALL, { kafka: "DOWN" }))).toBe(false);
    expect(wakeComplete(status(ALL))).toBe(true);
  });

  it("polling a 3 s durante il risveglio, 8 s a regime, fermo se inattivo", () => {
    expect(statusPollInterval({ waking: true, idle: false })).toBe(3000);
    expect(statusPollInterval({ waking: false, idle: false })).toBe(8000);
    expect(statusPollInterval({ waking: true, idle: true })).toBe(false);
    expect(statusPollInterval({ waking: false, idle: true })).toBe(false);
  });

  it("tempo trascorso in m:ss", () => {
    expect(formatElapsed(0)).toBe("0:00");
    expect(formatElapsed(9_999)).toBe("0:09");
    expect(formatElapsed(61_000)).toBe("1:01");
    expect(formatElapsed(185_500)).toBe("3:05");
    expect(formatElapsed(-5)).toBe("0:00");
  });
});

describe("api/status — Kafka DOWN oltre 2 minuti", () => {
  const at = (sec: number) => new Date(Date.parse("2026-09-24T10:00:00.000Z") + sec * 1000).toISOString();
  const core = ["ingestion", "member", "campaign", "wallet"];

  function run(states: [number, ServiceState][]): { since: number | null; alert: boolean } {
    let since: number | null = null;
    let alert = false;
    for (const [sec, kafka] of states) {
      const s = status(core, { kafka, at: at(sec) });
      since = trackKafkaDown(since, s);
      alert = kafkaLongDown(since, s);
    }
    return { since, alert };
  }

  it("nessun avviso fino a 2 minuti, avviso oltre", () => {
    expect(run([[0, "DOWN"], [60, "DOWN"], [120, "DOWN"]]).alert).toBe(false);
    expect(run([[0, "DOWN"], [60, "DOWN"], [123, "DOWN"]]).alert).toBe(true);
  });

  it("un UP intermedio azzera il conteggio", () => {
    expect(run([[0, "DOWN"], [100, "UP"], [110, "DOWN"], [200, "DOWN"]]).alert).toBe(false);
    expect(run([[0, "DOWN"], [100, "UP"], [110, "DOWN"], [231, "DOWN"]]).alert).toBe(true);
  });

  it("SLEEPING (ingestion irraggiungibile) non conta come DOWN", () => {
    const r = run([[0, "SLEEPING"], [300, "SLEEPING"]]);
    expect(r.since).toBeNull();
    expect(r.alert).toBe(false);
  });
});
