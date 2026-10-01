import { expect, test } from "vitest";
import { INFRA_STALE_MS, infraOnProbeFailure } from "./status";

const last = { at: 1_000, kafka: "UP" as const, db: "UP" as const };

test("ingestion UP e lettura recente: Kafka e Postgres restano all'ultimo stato letto", () => {
  expect(infraOnProbeFailure("UP", last, 1_000 + INFRA_STALE_MS)).toEqual({ kafka: "UP", db: "UP" });
});

test("ingestion UP ma lettura troppo vecchia: SLEEPING", () => {
  expect(infraOnProbeFailure("UP", last, 1_001 + INFRA_STALE_MS)).toEqual({ kafka: "SLEEPING", db: "SLEEPING" });
});

test("ingestion non UP: SLEEPING anche con una lettura recente", () => {
  expect(infraOnProbeFailure("SLEEPING", last, 2_000)).toEqual({ kafka: "SLEEPING", db: "SLEEPING" });
  expect(infraOnProbeFailure("DOWN", last, 2_000)).toEqual({ kafka: "SLEEPING", db: "SLEEPING" });
});

test("nessuna lettura precedente: SLEEPING", () => {
  expect(infraOnProbeFailure("UP", null, 2_000)).toEqual({ kafka: "SLEEPING", db: "SLEEPING" });
});
