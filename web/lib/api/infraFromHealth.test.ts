import { expect, it } from "vitest";
import { infraFromHealth } from "./status";

// Pannello HUB-01 (docs/07 §8): Kafka e Postgres si leggono dai componenti di /actuator/health, anche su 503.

it("avvio in corso: stato complessivo OUT_OF_SERVICE (503) ma db e kafka UP → UP", () => {
  const body = {
    status: "OUT_OF_SERVICE",
    components: { db: { status: "UP" }, kafka: { status: "UP" }, readinessState: { status: "OUT_OF_SERVICE" } },
  };
  expect(infraFromHealth(body)).toEqual({ kafka: "UP", db: "UP" });
});

it("broker giù e database su → solo Kafka DOWN", () => {
  expect(infraFromHealth({ status: "DOWN", components: { db: { status: "UP" }, kafka: { status: "DOWN" } } }))
    .toEqual({ kafka: "DOWN", db: "UP" });
});

it("corpo illeggibile o senza componenti → DOWN", () => {
  expect(infraFromHealth(null)).toEqual({ kafka: "DOWN", db: "DOWN" });
  expect(infraFromHealth({ status: "UP" })).toEqual({ kafka: "DOWN", db: "DOWN" });
});
