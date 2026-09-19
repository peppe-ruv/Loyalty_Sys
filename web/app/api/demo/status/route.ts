import { NextResponse } from "next/server";
import { SERVICES, serviceBaseUrl, type ServiceCode } from "@/lib/api/services";
import type { DemoStatus, ServiceState, ServiceStatus } from "@/lib/api/status";

// Stato aggregato dei servizi (docs/07 §8). Cache 2 s per non martellare gli health.

export const dynamic = "force-dynamic";

const PROBE_TIMEOUT_MS = 2500;
const CACHE_MS = 2000;

let cache: { at: number; value: DemoStatus } | null = null;

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

async function kafkaAndDb(): Promise<{ kafka: ServiceState; db: ServiceState }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const res = await fetch(`${serviceBaseUrl("ingestion")}/actuator/health`, {
      signal: controller.signal,
      cache: "no-store",
    });
    if (!res.ok) return { kafka: "DOWN", db: "DOWN" };
    const body = (await res.json()) as { components?: Record<string, { status?: string }> };
    const toState = (name: string): ServiceState =>
      body.components?.[name]?.status === "UP" ? "UP" : "DOWN";
    return { kafka: toState("kafka"), db: toState("db") };
  } catch {
    return { kafka: "SLEEPING", db: "SLEEPING" };
  } finally {
    clearTimeout(timer);
  }
}

export async function GET() {
  if (cache && Date.now() - cache.at < CACHE_MS) {
    return NextResponse.json(cache.value);
  }

  const [services, infra] = await Promise.all([
    Promise.all(SERVICES.map((s) => probe(s.code))),
    kafkaAndDb(),
  ]);

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
