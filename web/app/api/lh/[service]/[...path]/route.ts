import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { isServiceCode, serviceBaseUrl } from "@/lib/api/services";
import { actorHeader, parsePersona, PERSONA_COOKIE } from "@/lib/persona/cookie";
import { ulid } from "@/lib/ids";
import { fetchNicknames, nicknameRoute, resolveNicknames } from "@/lib/api/memberNicknames";
import { DOWNLOAD_HEADERS, MAX_PROXY_BODY_BYTES, isCsvDownload, readCappedBody } from "@/lib/api/proxyBody";

// Proxy verso i microservizi (docs/07 §3): il browser chiama SEMPRE /api/lh/<service>/v1/...
// Copiamo metodo/query/corpo e aggiungiamo X-LH-Actor (dal cookie persona) e X-Correlation-Id.
// Soprannomi (Q-368, ADR-032): classifiche del portale, ranking e vincitori del backoffice si chiedono a gamification
// con `resolve=ids` e si completano qui, lato server, coi soprannomi di member-service (lib/api/memberNicknames.ts):
// al browser del portale non arrivano mai i memberId degli altri membri.

export const dynamic = "force-dynamic";

const TIMEOUT_MS = 25_000;

async function handle(req: NextRequest, ctx: { params: Promise<{ service: string; path: string[] }> }) {
  const { service, path } = await ctx.params;
  if (!isServiceCode(service)) {
    return NextResponse.json({ type: "UNKNOWN_SERVICE", service }, { status: 404 });
  }

  const target = new URL(`${serviceBaseUrl(service)}/${(path ?? []).join("/")}`);
  target.search = req.nextUrl.search;
  const nicknames = nicknameRoute(service, req.method, path ?? []);
  if (nicknames) target.searchParams.set("resolve", "ids");

  const persona = parsePersona((await cookies()).get(PERSONA_COOKIE)?.value);
  const correlationId = req.headers.get("x-correlation-id") ?? ulid();

  const headers = new Headers();
  const contentType = req.headers.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  headers.set("accept", "application/json");
  headers.set("x-lh-actor", actorHeader(persona));
  headers.set("x-correlation-id", correlationId);
  // Import file (BO-32, Q-353): stessa chiave → stesso lavoro, anche se il browser ripete l'invio.
  const idempotencyKey = req.headers.get("idempotency-key");
  if (idempotencyKey) headers.set("idempotency-key", idempotencyKey);

  const hasBody = req.method !== "GET" && req.method !== "DELETE";
  // Corpo con un tetto (lib/api/proxyBody.ts): oltre, 413 senza chiamare il servizio. Un file caricato (multipart,
  // BO-32) passa com'è, byte per byte; il JSON resta testo.
  let body: string | Uint8Array<ArrayBuffer> | undefined;
  if (hasBody) {
    const bytes = await readCappedBody(req);
    if (!bytes) {
      return NextResponse.json(
        {
          type: "PAYLOAD_TOO_LARGE",
          code: "PAYLOAD_TOO_LARGE",
          title: "Richiesta troppo grande",
          detail: `Il corpo supera ${MAX_PROXY_BODY_BYTES / (1024 * 1024)} MB: dividi il file o l'invio.`,
        },
        { status: 413 },
      );
    }
    const multipart = (contentType ?? "").toLowerCase().startsWith("multipart/");
    body = multipart ? bytes : new TextDecoder().decode(bytes);
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

  try {
    const upstream = await fetch(target, {
      method: req.method,
      headers,
      body,
      signal: controller.signal,
      cache: "no-store",
    });

    // 502/503/504 → servizio addormentato: l'UI mostra lo stato degraded e innesca il wake.
    if (upstream.status === 502 || upstream.status === 503 || upstream.status === 504) {
      return NextResponse.json({ type: "SERVICE_ASLEEP", service }, { status: 503 });
    }

    let text = await upstream.text();
    let degraded = false;
    if (nicknames && upstream.status === 200) {
      const forward = { "x-lh-actor": headers.get("x-lh-actor") ?? "", "x-correlation-id": correlationId };
      const resolved = await resolveNicknames(nicknames, text, req.nextUrl.searchParams.get("memberId"), (ids) => fetchNicknames(ids, forward));
      // Corpo di gamification inatteso: niente inoltro (potrebbe contenere id altrui).
      if (!resolved) return NextResponse.json({ type: "UPSTREAM_INVALID", service }, { status: 502 });
      text = resolved.body;
      degraded = resolved.degraded;
    }
    const res = new NextResponse(text, { status: upstream.status });
    const ct = upstream.headers.get("content-type");
    if (ct) res.headers.set("content-type", ct);
    // File CSV scaricati (vincitori BO-14, rapporto import BO-32): nome del file e nosniff arrivano al browser.
    if (isCsvDownload(path ?? [])) {
      for (const name of DOWNLOAD_HEADERS) {
        const value = upstream.headers.get(name);
        if (value) res.headers.set(name, value);
      }
    }
    // member-service non disponibile: soprannomi segnaposto, la pagina resta utilizzabile.
    if (degraded) res.headers.set("x-lh-degraded", "nicknames");
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
