import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { isServiceCode, serviceBaseUrl } from "@/lib/api/services";
import { actorHeader, parsePersona, PERSONA_COOKIE } from "@/lib/persona/cookie";
import { ulid } from "@/lib/ids";
import { fetchNicknames, nicknameRoute, resolveNicknames } from "@/lib/api/memberNicknames";
import { DOWNLOAD_HEADERS, MAX_PROXY_BODY_BYTES, isCsvDownload, readCappedBody, upstreamAccept } from "@/lib/api/proxyBody";
import { correlationIdFrom, upstreamHeaders, type UpstreamIdentity } from "@/lib/api/proxyHeaders";
import { upstreamUrl } from "@/lib/api/proxyPath";
import { problem, resolveBff } from "@/lib/auth/bff";
import { checkPortalBody } from "@/lib/auth/memberScope";
import { demoSourceActor } from "@/lib/api/demoSource";
import { authorizeProxy } from "@/lib/auth/proxyAuth";

// Proxy verso i microservizi (docs/07 §3): il browser chiama SEMPRE /api/lh/<service>/v1/...
// Copiamo metodo/query/corpo e aggiungiamo l'identità e X-Correlation-Id (elenco chiuso: lib/api/proxyHeaders.ts).
// - Profilo demo: X-LH-Actor dal cookie persona, come in Fase 1.
// - Profilo enterprise (BFF, ADR-027, docs/07 §4-bis): sessione dal cookie `__Host-lh_session`, controllo CSRF sulle
//   richieste che cambiano stato, `Authorization: Bearer` aggiunto lato server; sulle API del portale il membro viene
//   solo dal token (lib/auth/memberScope.ts). Profilo enterprise mal configurato ⇒ 500 INSECURE_CONFIG, mai il demo.
// Soprannomi (Q-368, ADR-032): classifiche del portale, ranking e vincitori del backoffice si chiedono a gamification
// con `resolve=ids` e si completano qui, lato server, coi soprannomi di member-service (lib/api/memberNicknames.ts):
// al browser del portale non arrivano mai i memberId degli altri membri.

export const dynamic = "force-dynamic";

const TIMEOUT_MS = 25_000;
/** Stati che per HTTP non hanno corpo: la risposta al browser si costruisce con corpo `null`. */
const NULL_BODY_STATUSES = new Set([204, 205, 304]);

async function handle(req: NextRequest, ctx: { params: Promise<{ service: string; path: string[] }> }) {
  const { service, path } = await ctx.params;
  if (!isServiceCode(service)) {
    return NextResponse.json({ type: "UNKNOWN_SERVICE", service }, { status: 404 });
  }

  const resolved = resolveBff();
  if (resolved.mode === "error") return resolved.response;

  let target: URL;
  let identity: UpstreamIdentity;
  // API del portale in enterprise: nessun memberId scelto dal browser (query e percorso in authorizeProxy, corpo sotto).
  let memberFromToken = false;
  if (resolved.mode === "enterprise") {
    // Percorso a valle identico a quello chiesto: niente `..`, `%2F`, `;` o segmenti vuoti (lib/api/proxyPath.ts).
    const safe = upstreamUrl(serviceBaseUrl(service), path ?? []);
    if (!safe) return problem(400, "INVALID_PATH", "Percorso non valido", "L'indirizzo della richiesta contiene caratteri non ammessi.");
    target = safe;
    const auth = await authorizeProxy(req, path ?? [], resolved.bff);
    if (!auth.ok) return auth.response;
    identity = { authorization: auth.authorization };
    memberFromToken = auth.portal;
  } else {
    target = new URL(`${serviceBaseUrl(service)}/${(path ?? []).join("/")}`);
    const persona = parsePersona((await cookies()).get(PERSONA_COOKIE)?.value);
    identity = { "x-lh-actor": actorHeader(persona) };
  }
  target.search = req.nextUrl.search;
  const nicknames = nicknameRoute(service, req.method, path ?? []);
  if (nicknames) target.searchParams.set("resolve", "ids");

  const correlationId = correlationIdFrom(req.headers) ?? ulid();
  const contentType = req.headers.get("content-type");
  const headers = upstreamHeaders(req.headers, identity, correlationId, upstreamAccept(path ?? []));

  // GET e HEAD non hanno corpo: `fetch` rifiuterebbe anche un corpo vuoto (TypeError ⇒ finto 503 SERVICE_ASLEEP).
  const hasBody = req.method !== "GET" && req.method !== "HEAD" && req.method !== "DELETE";
  // Corpo con un tetto (lib/api/proxyBody.ts): oltre, 413 senza chiamare il servizio. Un file caricato (multipart,
  // BO-32) passa com'è, byte per byte; il JSON resta testo.
  let body: string | Uint8Array<ArrayBuffer> | undefined;
  if (hasBody) {
    let bytes: Uint8Array<ArrayBuffer> | null;
    try {
      bytes = await readCappedBody(req);
    } catch {
      // Il browser ha interrotto l'invio (pagina chiusa, rete caduta): nessuno leggerà la risposta, il servizio non
      // si chiama e si chiude senza errore non gestito (499, «client closed request»).
      return new NextResponse(null, { status: 499 });
    }
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
    if (memberFromToken) {
      const rejected = checkPortalBody(contentType, bytes);
      if (rejected) return problem(rejected.status, rejected.code, "Richiesta non valida", rejected.detail);
    }
    const multipart = (contentType ?? "").toLowerCase().startsWith("multipart/");
    body = multipart ? bytes : new TextDecoder().decode(bytes);
  }
  // Profilo demo, Q-492: l'ingresso delle azioni accetta solo il ruolo SOURCE; il pannello demo del portale invia
  // «dalla fonte» che l'evento dichiara, con l'identità simulata `SOURCE:src-<codice>` (lib/api/demoSource.ts).
  if (resolved.mode !== "enterprise") {
    const source = demoSourceActor(service, req.method, path ?? [], typeof body === "string" ? body : undefined);
    if (source) headers.set("x-lh-actor", source);
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

    // Risposte senza corpo: HEAD e 204/205/304. `new NextResponse("", { status: 204 })` lancia anche con corpo vuoto
    // e il catch sotto lo trasformerebbe in un finto 503 SERVICE_ASLEEP.
    const isHead = req.method === "HEAD";
    const noBody = isHead || NULL_BODY_STATUSES.has(upstream.status);
    let text: string | null = null;
    if (noBody) await upstream.body?.cancel();
    else text = await upstream.text();
    let degraded = false;
    if (nicknames && upstream.status === 200 && text !== null) {
      // Stessa identità della richiesta. In enterprise il memberId del portale è stato tolto sopra: nessuna riga
      // «tua» evidenziata e, con un token di solo membro, soprannomi degradati (SPEC-GAP: Q-411).
      const forward = { ...identity, "x-correlation-id": correlationId };
      const withNames = await resolveNicknames(nicknames, text, target.searchParams.get("memberId"), (ids) => fetchNicknames(ids, forward));
      // Corpo di gamification inatteso: niente inoltro (potrebbe contenere id altrui).
      if (!withNames) return NextResponse.json({ type: "UPSTREAM_INVALID", service }, { status: 502 });
      text = withNames.body;
      degraded = withNames.degraded;
    }
    const res = new NextResponse(text, { status: upstream.status });
    const ct = upstream.headers.get("content-type");
    if (ct) res.headers.set("content-type", ct);
    // HEAD: la lunghezza che avrebbe il GET, solo se il proxy non riscrive il corpo (coi soprannomi cambierebbe).
    const length = upstream.headers.get("content-length");
    if (isHead && !nicknames && length) res.headers.set("content-length", length);
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
    // Dati personali dietro una sessione: mai in una cache condivisa (solo enterprise; la demo resta com'era).
    if (resolved.mode === "enterprise") res.headers.set("cache-control", "private, no-store");
    return res;
  } catch {
    // Timeout o errore di rete: trattato come servizio addormentato.
    return NextResponse.json({ type: "SERVICE_ASLEEP", service }, { status: 503 });
  } finally {
    clearTimeout(timeout);
  }
}

export const GET = handle;
// HEAD inoltrato come HEAD (docs/07 §3: si copia il metodo); Spring MVC risponde a HEAD su ogni mappatura GET.
export const HEAD = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
