import { createHash } from "node:crypto";
import { readFileSync, statSync } from "node:fs";

// Configurazione del BFF (ADR-027, docs/18 §3.2, CLAUDE.md regole 6-bis, 20, 22). SOLO LATO SERVER.
// - Profilo `demo` (predefinito, `LH_PROFILE` assente o `demo`): identità simulata come in Fase 1, nessuna variabile OIDC.
// - Profilo `enterprise` (`LH_PROFILE=enterprise`): login OIDC obbligatorio. Una configurazione assente o insicura non
//   ricade mai sul profilo demo: il server rifiuta di avviarsi (instrumentation.ts) e ogni richiesta autenticata
//   risponde `INSECURE_CONFIG` (regola 22).
// I segreti si leggono da `VAR` oppure da `VAR_FILE` (stessa convenzione di deploy/image/entrypoint.sh: `VAR` vince).

export type Env = Readonly<Record<string, string | undefined>>;

export interface DemoAuthConfig {
  mode: "demo";
}

export interface EnterpriseAuthConfig {
  mode: "enterprise";
  /**
   * Impronta SHA-256 delle variabili lette: identifica la configurazione tra istanze diverse dello stesso modulo
   * (Next carica config.ts in più copie, una per i route handler e una per i layout RSC). Mai l'identità dell'oggetto.
   */
  fingerprint: string;
  /** Emittente OIDC (`LH_OIDC_ISSUER`): discovery in `<issuer>/.well-known/openid-configuration`. */
  issuer: URL;
  clientId: string;
  clientSecret: string;
  /** Origine pubblica del web (`LH_WEB_URL`): redirect URI, controllo `Origin`, ritorno dal logout. */
  publicUrl: URL;
  /** Chiave maestra da 32 byte (`LH_WEB_SESSION_KEY`): da qui si derivano le chiavi di cifratura e CSRF. */
  sessionKey: Buffer;
  /** Inattività massima di una sessione, in secondi (Q-354: 30 minuti). */
  idleSeconds: number;
  /** Durata massima di una sessione, in secondi (Q-354: 10 ore). */
  maxSeconds: number;
  /** Sessioni tenute in memoria al massimo; oltre, esce quella usata meno di recente (Q-409). */
  maxSessions: number;
  /** HTTP in chiaro verso l'emittente ammesso solo se l'emittente è su loopback (sviluppo locale). */
  allowInsecureIssuer: boolean;
}

export type AuthConfig = DemoAuthConfig | EnterpriseAuthConfig;

/** Configurazione rifiutata nel profilo enterprise: codice stabile `INSECURE_CONFIG` (docs/18 §3.15 punto 2). */
export class InsecureConfigError extends Error {
  readonly code = "INSECURE_CONFIG";
  constructor(readonly problems: readonly string[]) {
    super(`Configurazione del web rifiutata (INSECURE_CONFIG): ${problems.join("; ")}`);
    this.name = "InsecureConfigError";
  }
}

const SECRET_FILE_MAX_BYTES = 64 * 1024;
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
/** Valori da esempio o segnaposto non sostituiti: mai accettati come segreto. */
const WEAK_SECRETS = new Set(["changeme", "change-me", "change_me", "secret", "password", "web", "test", "example"]);

// Stesse durate per membri e operatori, quelle del backoffice (Q-354). SPEC-GAP: Q-413.
const DEFAULTS = { idleSeconds: 30 * 60, maxSeconds: 10 * 60 * 60, maxSessions: 10_000 } as const;

/** Variabili lette da questo modulo: servono anche come chiave della cache di `getAuthConfig`. */
const WATCHED = [
  "LH_PROFILE",
  "LH_OIDC_ISSUER",
  "LH_WEB_CLIENT_ID",
  "LH_WEB_CLIENT_SECRET",
  "LH_WEB_CLIENT_SECRET_FILE",
  "LH_WEB_URL",
  "LH_WEB_SESSION_KEY",
  "LH_WEB_SESSION_KEY_FILE",
  "LH_WEB_SESSION_IDLE_SECONDS",
  "LH_WEB_SESSION_MAX_SECONDS",
  "LH_WEB_SESSION_MAX_COUNT",
] as const;

/** Profilo richiesto; un valore sconosciuto è un errore (mai un ripiego silenzioso sul demo). */
export function profileOf(env: Env): "demo" | "enterprise" {
  const raw = (env.LH_PROFILE ?? "").trim();
  if (raw === "" || raw === "demo") return "demo";
  if (raw === "enterprise") return "enterprise";
  throw new InsecureConfigError([`LH_PROFILE deve essere 'demo' o 'enterprise' (trovato '${raw}')`]);
}

/**
 * Legge e valida la configurazione. Profilo demo ⇒ `{mode: "demo"}` senza altre verifiche (la demo ospitata non cambia).
 * Profilo enterprise ⇒ tutti i problemi insieme in un solo `InsecureConfigError`, così l'operatore li corregge in un giro.
 */
export function parseAuthConfig(env: Env, readFile: (path: string) => string = readSecretFile): AuthConfig {
  if (profileOf(env) === "demo") return { mode: "demo" };

  const problems: string[] = [];

  const issuer = parseUrl(env.LH_OIDC_ISSUER, "LH_OIDC_ISSUER", problems);
  const publicUrl = parseUrl(env.LH_WEB_URL, "LH_WEB_URL", problems);
  if (publicUrl && (publicUrl.pathname !== "/" || publicUrl.search || publicUrl.hash)) {
    problems.push("LH_WEB_URL deve essere un'origine senza percorso (es. https://loyalty.example.org)");
  }

  const clientId = (env.LH_WEB_CLIENT_ID ?? "web").trim();
  if (!clientId) problems.push("LH_WEB_CLIENT_ID vuoto");

  const clientSecret = secret(env, "LH_WEB_CLIENT_SECRET", readFile, problems);
  if (clientSecret !== null) {
    if (clientSecret.length < 16) problems.push("LH_WEB_CLIENT_SECRET troppo corto (almeno 16 caratteri)");
    else if (isWeak(clientSecret)) problems.push("LH_WEB_CLIENT_SECRET è un valore d'esempio o un segnaposto");
  }

  const rawKey = secret(env, "LH_WEB_SESSION_KEY", readFile, problems);
  const sessionKey = rawKey === null ? null : decodeKey(rawKey, problems);

  const idleSeconds = integer(env, "LH_WEB_SESSION_IDLE_SECONDS", DEFAULTS.idleSeconds, 60, 24 * 60 * 60, problems);
  const maxSeconds = integer(env, "LH_WEB_SESSION_MAX_SECONDS", DEFAULTS.maxSeconds, 5 * 60, 7 * 24 * 60 * 60, problems);
  const maxSessions = integer(env, "LH_WEB_SESSION_MAX_COUNT", DEFAULTS.maxSessions, 1, 1_000_000, problems);
  if (idleSeconds > maxSeconds) problems.push("LH_WEB_SESSION_IDLE_SECONDS non può superare LH_WEB_SESSION_MAX_SECONDS");

  // Trasporto: HTTPS obbligatorio, tranne su loopback (sviluppo locale: il browser accetta i cookie Secure da
  // http://localhost). Un emittente o un'origine in chiaro su rete non è un'impostazione ammessa (regola 22).
  if (issuer && issuer.protocol !== "https:" && !isLoopback(issuer)) {
    problems.push("LH_OIDC_ISSUER deve usare https (http solo verso localhost)");
  }
  if (publicUrl && publicUrl.protocol !== "https:" && !isLoopback(publicUrl)) {
    problems.push("LH_WEB_URL deve usare https (http solo per localhost)");
  }

  if (problems.length || !issuer || !publicUrl || clientSecret === null || sessionKey === null) {
    throw new InsecureConfigError(problems.length ? problems : ["configurazione OIDC incompleta"]);
  }
  const fingerprint = createHash("sha256")
    .update(JSON.stringify([issuer.href, clientId, clientSecret, publicUrl.href, sessionKey.toString("base64"), idleSeconds, maxSeconds, maxSessions]))
    .digest("base64url");
  return {
    mode: "enterprise",
    fingerprint,
    issuer,
    clientId,
    clientSecret,
    publicUrl,
    sessionKey,
    idleSeconds,
    maxSeconds,
    maxSessions,
    allowInsecureIssuer: issuer.protocol === "http:",
  };
}

type Cached = { key: string; value: AuthConfig } | { key: string; error: InsecureConfigError };
// Cache sul processo, non sul modulo: Next carica questo file in più istanze (route handler, layout RSC) e ognuna
// avrebbe la sua copia. Chiave e contenuto sono confrontati per valore.
const CACHE_KEY = Symbol.for("io.loyaltyhub.web.authConfig");
type CacheHolder = { [CACHE_KEY]?: Cached };

/**
 * Configurazione corrente da `process.env`, rivalutata solo se cambia una delle variabili lette (i file `*_FILE` si
 * leggono una volta). Lancia `InsecureConfigError` nel profilo enterprise mal configurato.
 */
export function getAuthConfig(env: Env = process.env): AuthConfig {
  const holder = globalThis as CacheHolder;
  const key = createHash("sha256")
    .update(WATCHED.map((name) => `${name}=${env[name] ?? ""}`).join("\n"))
    .digest("base64url");
  let cached = holder[CACHE_KEY];
  if (!cached || cached.key !== key) {
    try {
      cached = { key, value: parseAuthConfig(env) };
    } catch (err) {
      if (!(err instanceof InsecureConfigError)) throw err;
      cached = { key, error: err };
    }
    holder[CACHE_KEY] = cached;
  }
  if ("error" in cached) throw cached.error;
  return cached.value;
}

/** `true` nel profilo enterprise (anche se mal configurato: in quel caso le richieste falliscono, mai demo). */
export function isEnterprise(env: Env = process.env): boolean {
  try {
    return profileOf(env) === "enterprise";
  } catch {
    // LH_PROFILE sconosciuto: trattato come enterprise, così nessun percorso demo resta aperto per errore.
    return true;
  }
}

function parseUrl(raw: string | undefined, name: string, problems: string[]): URL | null {
  const value = (raw ?? "").trim();
  if (!value) {
    problems.push(`${name} mancante`);
    return null;
  }
  try {
    const url = new URL(value.replace(/\/+$/, ""));
    if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("schema");
    if (url.username || url.password) {
      problems.push(`${name} non deve contenere credenziali`);
      return null;
    }
    return url;
  } catch {
    problems.push(`${name} non è un URL valido`);
    return null;
  }
}

function secret(env: Env, name: string, readFile: (path: string) => string, problems: string[]): string | null {
  const direct = env[name];
  if (direct !== undefined && direct !== "") return direct.trim() || reportEmpty(name, problems);
  const file = env[`${name}_FILE`];
  if (file) {
    try {
      return readFile(file).trim() || reportEmpty(name, problems);
    } catch {
      problems.push(`${name}_FILE non leggibile`);
      return null;
    }
  }
  problems.push(`${name} mancante (oppure ${name}_FILE)`);
  return null;
}

function reportEmpty(name: string, problems: string[]): null {
  problems.push(`${name} vuoto`);
  return null;
}

function readSecretFile(path: string): string {
  if (statSync(path).size > SECRET_FILE_MAX_BYTES) throw new Error("file troppo grande per un segreto");
  return readFileSync(path, "utf8");
}

function isWeak(value: string): boolean {
  return value.startsWith("${") || WEAK_SECRETS.has(value.toLowerCase());
}

/** 32 byte in base64 o base64url (es. `openssl rand -base64 32`); rifiutate chiavi a byte tutti uguali. */
function decodeKey(raw: string, problems: string[]): Buffer | null {
  const normalized = raw.replace(/-/g, "+").replace(/_/g, "/");
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(normalized)) {
    problems.push("LH_WEB_SESSION_KEY deve essere base64 di 32 byte (openssl rand -base64 32)");
    return null;
  }
  const key = Buffer.from(normalized, "base64");
  if (key.length !== 32) {
    problems.push(`LH_WEB_SESSION_KEY deve valere 32 byte (trovati ${key.length})`);
    return null;
  }
  if (key.every((b) => b === key[0])) {
    problems.push("LH_WEB_SESSION_KEY non è casuale");
    return null;
  }
  return key;
}

function integer(env: Env, name: string, fallback: number, min: number, max: number, problems: string[]): number {
  const raw = (env[name] ?? "").trim();
  if (!raw) return fallback;
  if (!/^\d+$/.test(raw) || Number(raw) < min || Number(raw) > max) {
    problems.push(`${name} deve essere un intero tra ${min} e ${max}`);
    return fallback;
  }
  return Number(raw);
}

function isLoopback(url: URL): boolean {
  return LOOPBACK_HOSTS.has(url.hostname);
}
