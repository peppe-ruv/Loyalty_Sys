import { allPending, type Checks, type CheckState } from "@/lib/hub/readiness";

// HUB-01 — sonda di prontezza della vetrina (Q-728, ADR-051 decisione 9, F2-DIST-09). SOLO LATO SERVER.
// Quando GitHub dice che il codespace è `available`, la route della demo (Vercel) verifica che la vetrina sia davvero
// pronta. Il browser non può farlo (CORS), quindi lo fa il server. Seconde destinazioni in uscita, e SOLO queste:
//   https://<codespace>-8000.<dominio di inoltro>/api/demo/status                                          (web)
//   https://<codespace>-8001.<dominio di inoltro>/realms/loyaltyhub/.well-known/openid-configuration         (operatori)
//   https://<codespace>-8001.<dominio di inoltro>/realms/loyaltyhub-members/.well-known/openid-configuration (membri)
// I due host derivano dal nome del codespace (già validato da `codespaceConfig`) e dal dominio di inoltro di GitHub
// (`LH_VETRINA_FORWARD_DOMAIN`, default `app.github.dev`, validato qui): nessun valore arriva dal browser. Mai un
// reindirizzamento seguito (`redirect: "manual"`): una porta privata risponde 3xx verso il login di GitHub e vale
// «porta non ancora pubblica». Della risposta si legge solo quello che serve a decidere ok/pending, e si ritorna
// soltanto `Checks` (mai corpi, intestazioni o indirizzi). Nessun segreto viaggia in queste richieste.

export const PROBE_TIMEOUT_MS = 5_000;
/** Corpo massimo letto da una sonda: lo stato del web e la discovery stanno ben sotto. */
export const MAX_BODY_BYTES = 262_144;
export const DEFAULT_FORWARD_DOMAIN = "app.github.dev";
export const WEB_PORT = 8000;
export const IDP_PORT = 8001;

export type Env = Readonly<Record<string, string | undefined>>;

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const NAME_RE = /^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$/;

let domainWarned = false;

/** Dominio di inoltro delle porte: `app.github.dev` o il valore di `LH_VETRINA_FORWARD_DOMAIN` se è un sottodominio di `github.dev`. */
export function forwardDomain(env: Env): string | null {
  const raw = (env.LH_VETRINA_FORWARD_DOMAIN ?? "").trim();
  if (raw === "") return DEFAULT_FORWARD_DOMAIN;
  if (raw.length <= 100 && DOMAIN_RE.test(raw) && raw.endsWith(".github.dev")) return raw;
  if (!domainWarned) {
    domainWarned = true;
    console.warn("LH_VETRINA_FORWARD_DOMAIN ignorata: serve un sottodominio di github.dev (minuscole, cifre, trattini, punti).");
  }
  return null;
}

export interface Hosts {
  web: string;
  idp: string;
}

/** I due host inoltrati del codespace, o `null` se nome o dominio non passano la validazione (allora non si sonda nulla). */
export function forwardedHosts(name: string, domain: string | null): Hosts | null {
  if (domain === null || !NAME_RE.test(name) || !DOMAIN_RE.test(domain)) return null;
  // Un'etichetta DNS è lunga al più 63 caratteri.
  if (`${name}-${WEB_PORT}`.length > 63) return null;
  return { web: `${name}-${WEB_PORT}.${domain}`, idp: `${name}-${IDP_PORT}.${domain}` };
}

type Answer = { status: number; body: unknown } | null;

/** Una GET senza reindirizzamenti, con timeout. `null` se la rete non risponde; il corpo è JSON o `null`. */
async function get(fetchImpl: typeof fetch, host: string, path: string): Promise<Answer> {
  let url: URL;
  try {
    url = new URL(`https://${host}${path}`);
  } catch {
    return null;
  }
  // Difesa in profondità: l'URL costruito deve avere esattamente l'host atteso.
  if (url.protocol !== "https:" || url.hostname !== host || url.port !== "" || url.username !== "" || url.password !== "") return null;
  try {
    const res = await fetchImpl(url.href, {
      method: "GET",
      headers: { Accept: "application/json" },
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
    });
    const status = res.status;
    if (status === 0) return { status: 302, body: null }; // risposta opaca di un reindirizzamento
    if (status !== 200) return { status, body: null };
    const text = await res.text();
    if (text.length > MAX_BODY_BYTES) return { status, body: null };
    try {
      return { status, body: JSON.parse(text) as unknown };
    } catch {
      return { status, body: null };
    }
  } catch {
    return null;
  }
}

/**
 * Porta pubblica: ha risposto un'applicazione. Non lo è un 3xx verso il login di GitHub (porta privata), né 401 o 403
 * (accesso negato dall'inoltro), né un 5xx (nessuno in ascolto ancora: dall'esterno non si distingue dalla porta privata).
 */
const reached = (a: Answer): boolean =>
  a !== null && (a.status < 300 || (a.status >= 400 && a.status < 500 && a.status !== 401 && a.status !== 403));
const obj = (v: unknown): Record<string, unknown> | null =>
  v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
const st = (b: boolean): CheckState => (b ? "ok" : "pending");

/** Discovery OIDC valida del realm: `issuer` stringa che termina con `/realms/<nome>`. */
function discoveryOk(a: Answer, realm: string): boolean {
  const issuer = obj(a?.body)?.issuer;
  return a !== null && a.status === 200 && typeof issuer === "string" && issuer.endsWith(`/realms/${realm}`);
}

/** `ready` solo con la stringa esatta; qualunque altra cosa (campo assente: immagine più vecchia) è «in attesa». */
function testMembersOk(body: unknown): boolean {
  return obj(obj(body)?.vetrina)?.testMembers === "ready";
}

function infraUp(body: unknown, key: "db" | "kafka"): boolean {
  return obj(obj(body)?.[key])?.state === "UP";
}

function hubUp(body: unknown): boolean {
  const services = obj(body)?.services;
  return Array.isArray(services) && services.length > 0 && services.every((s) => obj(s)?.state === "UP");
}

/**
 * Le risorse della vetrina (il codespace è acceso, lo dice GitHub). Non lancia mai: ogni guasto diventa `pending`.
 * Con host non validi non fa alcuna richiesta e restituisce tutto `pending` tranne `codespace`.
 */
export async function probeVetrina(fetchImpl: typeof fetch, hosts: Hosts | null): Promise<Checks> {
  if (hosts === null) return { ...allPending(), codespace: "ok" };
  const [status, ops, members] = await Promise.all([
    get(fetchImpl, hosts.web, "/api/demo/status"),
    get(fetchImpl, hosts.idp, "/realms/loyaltyhub/.well-known/openid-configuration"),
    get(fetchImpl, hosts.idp, "/realms/loyaltyhub-members/.well-known/openid-configuration"),
  ]);
  // Le porte sono pubbliche se entrambi gli host rispondono con un'applicazione, non con un 3xx verso il login di GitHub.
  const ports = reached(status) && reached(ops) && reached(members);
  const web = ports && status !== null && status.status === 200 && typeof obj(status.body)?.checkedAt === "string";
  const body = web ? status?.body : null;
  return {
    codespace: "ok",
    ports: st(ports),
    web: st(web),
    hub: st(web && hubUp(body)),
    db: st(web && infraUp(body, "db")),
    kafka: st(web && infraUp(body, "kafka")),
    idpOperators: st(ports && discoveryOk(ops, "loyaltyhub")),
    idpMembers: st(ports && discoveryOk(members, "loyaltyhub-members")),
    testMembers: st(web && testMembersOk(body)),
  };
}
