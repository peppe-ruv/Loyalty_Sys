import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { isServiceCode, serviceBaseUrl } from "@/lib/api/services";
import { actorHeader, parsePersona, PERSONA_COOKIE } from "@/lib/persona/cookie";
import { ulid } from "@/lib/ids";

// Proxy verso i microservizi (docs/07 §3): il browser chiama SEMPRE /api/lh/<service>/v1/...
// Copiamo metodo/query/corpo e aggiungiamo X-LH-Actor (dal cookie persona) e X-Correlation-Id.

export const dynamic = "force-dynamic";

const TIMEOUT_MS = 25_000;

async function handle(req: NextRequest, ctx: { params: Promise<{ service: string; path: string[] }> }) {
  const { service, path } = await ctx.params;
  if (!isServiceCode(service)) {
    return NextResponse.json({ type: "UNKNOWN_SERVICE", service }, { status: 404 });
  }

  const target = new URL(`${serviceBaseUrl(service)}/${(path ?? []).join("/")}`);
  target.search = req.nextUrl.search;

  const persona = parsePersona((await cookies()).get(PERSONA_COOKIE)?.value);
  const correlationId = req.headers.get("x-correlation-id") ?? ulid();

  const headers = new Headers();
  const contentType = req.headers.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  headers.set("accept", "application/json");
  headers.set("x-lh-actor", actorHeader(persona));
  headers.set("x-correlation-id", correlationId);

  const hasBody = req.method !== "GET" && req.method !== "DELETE";
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

  try {
    const upstream = await fetch(target, {
      method: req.method,
      headers,
      body: hasBody ? await req.text() : undefined,
      signal: controller.signal,
      cache: "no-store",
    });

    // 502/503/504 → servizio addormentato: l'UI mostra lo stato degraded e innesca il wake.
    if (upstream.status === 502 || upstream.status === 503 || upstream.status === 504) {
      return NextResponse.json({ type: "SERVICE_ASLEEP", service }, { status: 503 });
    }

    const body = await upstream.text();
    const res = new NextResponse(body, { status: upstream.status });
    const ct = upstream.headers.get("content-type");
    if (ct) res.headers.set("content-type", ct);
    res.headers.set("x-correlation-id", correlationId);
    return res;
  } catch {
    // Timeout o errore di rete: trattato come servizio addormentato.
    return NextResponse.json({ type: "SERVICE_ASLEEP", service }, { status: 503 });
  } finally {
    clearTimeout(timeout);
  }
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
