import { NextResponse } from "next/server";
import { SERVICES, serviceBaseUrl, type ServiceCode } from "@/lib/api/services";
import {
  infraFromHealth,
  infraOnProbeFailure,
  type DemoStatus,
  type InfraReading,
  type ServiceState,
  type ServiceStatus,
} from "@/lib/api/status";

// Stato aggregato dei servizi (docs/07 §8). Cache 2 s per non martellare gli health.

export const dynamic = "force-dynamic";

const PROBE_TIMEOUT_MS = 2500;
// /actuator/health completo esegue i controlli di DB e Kafka (Neon, Aiven): più lento di /liveness, timeout più ampio.
const INFRA_PROBE_TIMEOUT_MS = 6000;
const CACHE_MS = 2000;

let cache: { at: number; value: DemoStatus } | null = null;
// Ultima lettura riuscita di Kafka e Postgres (per istanza): copre una sonda scaduta con ingestion sveglio.
let lastInfra: InfraReading | null = null;

async function probe(code: ServiceCode): Promise<ServiceStatus> {
  const svc = SERVICES.find((s) => s.code === code)!;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  const start = Date.now();
  try {
    const res = await fetch(`${serviceBaseUrl(code)}/actuator/health/liveness`, {
      signal: controller.signal,
      cache: "no-store",
    });
    const latencyMs = Date.now() - start;
    return { code, name: svc.name, state: res.ok ? "UP" : "DOWN", latencyMs };
  } catch {
    // Irraggiungibile: servizio serverless addormentato.
    return { code, name: svc.name, state: "SLEEPING", latencyMs: null };
  } finally {
    clearTimeout(timer);
  }
}

async function kafkaAndDb(): Promise<{ kafka: ServiceState; db: ServiceState } | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), INFRA_PROBE_TIMEOUT_MS);
  try {
    const res = await fetch(`${serviceBaseUrl("ingestion")}/actuator/health`, {
      signal: controller.signal,
      cache: "no-store",
    });
    // Anche con 503 (avvio in corso: readinessState OUT_OF_SERVICE) il corpo porta i componenti db e kafka.
    const body: unknown = await res.json().catch(() => null);
    const infra = infraFromHealth(body);
    lastInfra = { at: Date.now(), ...infra };
    return infra;
  } catch {
    // Sonda scaduta o ingestion irraggiungibile: decide infraOnProbeFailure, con lo stato di ingestion.
    return null;
  } finally {
    clearTimeout(timer);
  }
}

export async function GET() {
  if (cache && Date.now() - cache.at < CACHE_MS) {
    return NextResponse.json(cache.value);
  }

  const [services, probed] = await Promise.all([
    Promise.all(SERVICES.map((s) => probe(s.code))),
    kafkaAndDb(),
  ]);
  const ingestion = services.find((s) => s.code === "ingestion")?.state ?? "SLEEPING";
  const infra = probed ?? infraOnProbeFailure(ingestion, lastInfra, Date.now());

  const value: DemoStatus = {
    services,
    kafka: { state: infra.kafka },
    db: { state: infra.db },
    readyCount:
      services.filter((s) => s.state === "UP").length +
      (infra.kafka === "UP" ? 1 : 0) +
      (infra.db === "UP" ? 1 : 0),
    totalCount: SERVICES.length + 2,
    checkedAt: new Date().toISOString(),
  };

  cache = { at: Date.now(), value };
  return NextResponse.json(value);
}
