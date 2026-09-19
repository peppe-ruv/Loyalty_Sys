import { NextResponse } from "next/server";
import { SERVICES, serviceBaseUrl } from "@/lib/api/services";

// Risveglio gentile (docs/07 §8, F-DEMO-07): lancia in parallelo un liveness verso tutti i servizi
// SENZA attendere la risposta; serve solo a "toccare" i servizi serverless perché si accendano.

export const dynamic = "force-dynamic";

export async function POST() {
  for (const svc of SERVICES) {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 3000);
    // Fire-and-forget: ignoriamo esito ed errori.
    void fetch(`${serviceBaseUrl(svc.code)}/actuator/health/liveness`, {
      signal: controller.signal,
      cache: "no-store",
    }).catch(() => undefined);
  }
  return NextResponse.json({ woken: SERVICES.map((s) => s.code) }, { status: 202 });
}
