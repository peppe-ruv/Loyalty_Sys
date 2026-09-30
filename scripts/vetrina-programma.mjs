#!/usr/bin/env node
// Configurazione di programma da seed/ a un'installazione enterprise, tramite le API REST dei servizi con il token
// di un operatore ADMIN (F2-DIST-09, F2-DIST-06, ADR-049, M8.14d; Q-617: default proposto, APERTA).
//
// Nel profilo `enterprise` non esiste un seeder (docs/18 §3.15) e `/v1/demo/**` non esiste: la vetrina parte vuota.
// Questo script legge SOLO la configurazione del programma (docs/10) e la applica con le API che già esistono:
//   - nessun membro (MBR-*), movimento, lotto, vincita, coupon generato, messaggio, consegna, dato personale;
//   - nessun nuovo endpoint, nessun /v1/demo/**; dove l'API di creazione manca l'entità è saltata e riportata
//     (la schermata resta vuota);
//   - regola 22: gli oggetti con flusso di approvazione (premi) nascono in DRAFT e restano lì: lo script non approva
//     né pubblica; campagne, concorsi e contenuti non sono inclusi;
//   - idempotente: legge (GET) e crea solo ciò che manca; ciò che esiste non si modifica mai (se diverge dal seed
//     lo segnala soltanto). Due esecuzioni consecutive non producono doppioni;
//   - regola 21: dopo --apply verifica con GET /v1/audit che ogni scrittura abbia una voce con l'attore reale, cioè
//     l'operatore del token (non demo, non system, non un altro operatore); una voce mancante è un errore (uscita 1).
//     --verify-audit --since <inizio> ripete la sola verifica sulle entità già presenti, dopo un --apply finito
//     con audit mancante (una seconda esecuzione normale non scrive e non verifica nulla);
//
// Sicurezza (regole 18-20): solo https (http solo con --allow-http e solo verso localhost/127.0.0.1/::1); il token arriva
// SOLO da un file con permessi 0600 (--token-file o LH_OPERATOR_TOKEN_FILE) oppure dal Device Authorization Grant
// (RFC 8628) contro l'emittente (--issuer, --client-id, default lh-cli); mai da argomenti o variabili d'ambiente, mai
// stampato né messo in URL o log; header `Authorization: Bearer`; i redirect non si seguono (il token non cambia host).
// Predefinito --dry-run: si legge lo stato e si mostra il piano, senza scritture. Le scritture richiedono --apply.
//
// Uso: node scripts/vetrina-programma.mjs --base-url <url> --token-file <file> [--apply | --verify-audit --since <istante>]
//      node scripts/vetrina-programma.mjs --offline            (piano dal solo seed, senza rete né token)
// Test: node --test scripts/vetrina-programma.test.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Errore di uso o di configurazione (uscita 2): nessuna richiesta è partita. */
export class UsageError extends Error {}

/** Risposta non 2xx di un servizio. `code` è il codice RFC 9457 se c'è. */
export class ApiError extends Error {
  constructor(status, code, detail) {
    super(`HTTP ${status}${code ? ` ${code}` : ''}${detail ? `: ${detail}` : ''}`);
    this.status = status;
    this.code = code;
  }
}

/** Servizi toccati e nome della variabile d'ambiente con l'URL (come il web: LH_SVC_<NOME>_URL, docs/11 §8). */
export const SERVICES = ['ingestion', 'member', 'wallet', 'reward', 'gamification', 'engagement', 'insight'];

const LOOPBACK = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);

/** Entità di seed che per scelta NON si applicano, con il motivo (stampate nel piano). */
export const NOT_INCLUDED = [
  ['members.json, wallets.json, activity-history.json, redemptions.json, inbox.json, gamification-history.json', 'dati dei membri, movimenti, lotti, vincite, richieste, messaggi: mai (regola 9, docs/11 §11)'],
  ['campaigns.json, contests.json, contents.json', 'oggetti con flusso di approvazione (regola 22: lo script non approva né pubblica); Q-617 non li include'],
  ['editions.json', 'POST /v1/editions crea sempre in stato PLANNED e lo stato ACTIVE/CLOSED non si imposta da API: un\'edizione in stato sbagliato sarebbe peggio di nessuna'],
  ['webhooks.json', 'destinazione di rete in uscita e segreto (docs/18: «nuova destinazione di rete in uscita» → STOP); il seed porta un segreto'],
  ['scenarios.json, import-history.json, inbound-history.json, insight-synthetic.json', 'scenari e storici della demo: dati di prova, /v1/demo non esiste in enterprise'],
  ['coupon generati (size/consumed di coupon-pools.json)', 'i coupon sono dati operativi: si crea solo il pool (code, prefix, validityDays)'],
];

// ---------------------------------------------------------------------------------------------------------------
// Validazione di URL e argomenti

/** Normalizza un URL base dei servizi o dell'emittente: https (o http solo loopback con allowHttp), niente credenziali. */
export function validateBaseUrl(raw, { allowHttp = false, label = 'URL' } = {}) {
  let u;
  try {
    u = new URL(String(raw));
  } catch {
    throw new UsageError(`${label}: non è un URL valido`);
  }
  if (u.username || u.password) throw new UsageError(`${label}: niente credenziali nell'URL`);
  if (u.search || u.hash) throw new UsageError(`${label}: niente query né frammento nell'URL`);
  if (u.protocol === 'https:') {
    // ok
  } else if (u.protocol === 'http:') {
    if (!allowHttp) throw new UsageError(`${label}: serve https (http solo con --allow-http e solo verso localhost)`);
    if (!LOOPBACK.has(u.hostname)) {
      throw new UsageError(`${label}: http è ammesso solo verso localhost, 127.0.0.1 o ::1, non verso ${u.hostname}`);
    }
  } else {
    throw new UsageError(`${label}: schema non ammesso (${u.protocol})`);
  }
  return `${u.origin}${u.pathname.replace(/\/+$/, '')}`;
}

const VALUE_OPTIONS = new Set(['--base-url', '--token-file', '--issuer', '--client-id', '--seed-dir', '--audit-timeout', '--audit-interval', '--svc-url', '--since']);
const FLAG_OPTIONS = new Set(['--apply', '--dry-run', '--offline', '--allow-http', '--verify-audit', '--help']);

export function parseArgs(argv) {
  const opts = { apply: false, dryRun: false, offline: false, allowHttp: false, verifyAudit: false, help: false, svcUrl: {}, clientId: 'lh-cli', auditTimeout: 60, auditInterval: 2 };
  for (let i = 0; i < argv.length; i++) {
    let arg = argv[i];
    let value;
    const eq = arg.indexOf('=');
    if (arg.startsWith('--') && eq > 0) {
      value = arg.slice(eq + 1);
      arg = arg.slice(0, eq);
    }
    if (FLAG_OPTIONS.has(arg)) {
      if (value !== undefined) throw new UsageError(`${arg} non vuole un valore`);
      opts[camel(arg)] = true;
    } else if (VALUE_OPTIONS.has(arg)) {
      if (value === undefined) value = argv[++i];
      if (value === undefined || value.startsWith('--')) throw new UsageError(`${arg} richiede un valore`);
      if (arg === '--svc-url') {
        const at = value.indexOf('=');
        const name = at > 0 ? value.slice(0, at).toLowerCase() : '';
        if (!SERVICES.includes(name)) throw new UsageError(`--svc-url: formato <servizio>=<url> con servizio tra ${SERVICES.join(', ')}`);
        opts.svcUrl[name] = value.slice(at + 1);
      } else if (arg === '--since') {
        const t = Date.parse(value);
        if (!Number.isFinite(t) || !/^\d{4}-\d{2}-\d{2}/.test(value)) throw new UsageError('--since: serve un istante ISO 8601, per esempio 2026-09-30T12:00:00Z');
        opts.since = new Date(t);
      } else if (arg === '--audit-timeout' || arg === '--audit-interval') {
        const n = Number(value);
        if (!Number.isFinite(n) || n < 0 || n > 3600) throw new UsageError(`${arg}: numero di secondi tra 0 e 3600`);
        opts[camel(arg)] = n;
      } else {
        opts[camel(arg)] = value;
      }
    } else if (arg === '--token' || arg.startsWith('--token=') || arg === '--password') {
      throw new UsageError('il token non si passa da riga di comando (finirebbe nella cronologia della shell): usa --token-file o --issuer');
    } else {
      throw new UsageError(`opzione sconosciuta: ${arg}`);
    }
  }
  if (opts.apply && opts.dryRun) throw new UsageError('--apply e --dry-run si escludono');
  if (opts.apply && opts.offline) throw new UsageError('--offline non scrive: non si combina con --apply');
  if (opts.verifyAudit && (opts.apply || opts.offline)) throw new UsageError('--verify-audit non scrive e vuole la rete: non si combina con --apply né con --offline');
  if (opts.verifyAudit && !opts.since) throw new UsageError('--verify-audit richiede --since <istante>: l\'inizio dell\'esecuzione --apply da verificare (lo stampa l\'esecuzione stessa)');
  if (opts.since && !opts.verifyAudit) throw new UsageError('--since serve solo con --verify-audit');
  return opts;
}

function camel(flag) {
  return flag.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

export const HELP = `Uso: node scripts/vetrina-programma.mjs [opzioni]

Applica la configurazione di programma di seed/ a un'installazione enterprise con le API del backoffice
(F2-DIST-09, ADR-049, Q-617 default proposto, APERTA). Predefinito: --dry-run (mostra il piano).

  --base-url <url>         URL dell'hub (tutti i servizi); in alternativa LH_BASE_URL, o LH_SVC_<SERVIZIO>_URL per servizio
  --svc-url <servizio>=<url>  URL di un solo servizio (ripetibile): ${SERVICES.join(', ')}
  --token-file <file>      file con il token di un operatore ADMIN, permessi 0600 (o LH_OPERATOR_TOKEN_FILE)
  --issuer <url>           in alternativa al file: emittente OIDC per il Device Authorization Grant
  --client-id <id>         client del device flow (predefinito lh-cli)
  --apply                  scrive davvero (senza, solo piano)
  --offline                piano dal solo seed: nessuna rete, nessun token
  --allow-http             ammette http, ma solo verso localhost/127.0.0.1/::1
  --seed-dir <dir>         cartella dei seed (predefinita: seed/ del repository)
  --audit-timeout <s>      attesa massima delle voci di audit dopo --apply (predefinito 60)
  --audit-interval <s>     intervallo tra due letture dell'audit (predefinito 2)
  --verify-audit           non scrive: verifica su GET /v1/audit le voci delle entità già presenti (regola 21);
                           serve dopo un --apply finito con audit mancante, perché una seconda esecuzione normale non verifica nulla
  --since <istante>        con --verify-audit: inizio (ISO 8601) dell'esecuzione --apply da verificare

Uscita: 0 ok · 1 errore di lettura, scrittura o audit mancante · 2 uso o configurazione non validi.`;

// ---------------------------------------------------------------------------------------------------------------
// Token

/**
 * Legge il token da un file regolare, non collegamento, del proprietario, con permessi 0600 o più stretti.
 * Un solo `open` senza seguire i collegamenti (O_NOFOLLOW), poi ogni controllo sul descrittore (`fstat`) e la lettura
 * dallo stesso descrittore: nessuno scambio del file tra il controllo e la lettura (TOCTOU).
 */
export function readTokenFile(file) {
  const flags = fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0) | (fs.constants.O_NONBLOCK ?? 0);
  let fd;
  try {
    fd = fs.openSync(file, flags);
  } catch (e) {
    if (e?.code === 'ELOOP') throw new UsageError(`${file}: deve essere un file regolare (non un collegamento)`);
    throw new UsageError(`file del token non leggibile: ${file}`);
  }
  try {
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw new UsageError(`${file}: deve essere un file regolare (non un collegamento)`);
    if ((st.mode & 0o077) !== 0) {
      throw new UsageError(`${file}: permessi troppo larghi (${(st.mode & 0o777).toString(8)}); serve 0600 (chmod 600 <file>)`);
    }
    if (typeof process.getuid === 'function' && st.uid !== process.getuid()) {
      throw new UsageError(`${file}: il proprietario deve essere l'utente che esegue lo script`);
    }
    if (st.size > 16384) throw new UsageError(`${file}: troppo grande per un token`);
    const token = fs.readFileSync(fd, 'utf8').trim();
    if (token.length < 8 || /[\s\u0000-\u001f]/.test(token)) {
      throw new UsageError(`${file}: contenuto non valido (atteso un solo token su una riga)`);
    }
    return token;
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * Identità dell'operatore secondo il token, con la stessa precedenza con cui i servizi scelgono il nome dell'attore
 * per l'audit (preferred_username, azp, client_id, sub: `OidcActorFilter`). Si legge il payload SENZA verificare la
 * firma: serve solo a sapere chi ci si aspetta nell'audit, l'autorizzazione resta ai servizi. Token opaco (non JWT)
 * o senza quei claim: `null`, e la verifica dell'audit lo dice.
 */
export function tokenIdentity(token) {
  const parts = String(token).split('.');
  if (parts.length !== 3) return null;
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    for (const claim of ['preferred_username', 'azp', 'client_id', 'sub']) {
      const value = payload?.[claim];
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
  } catch {
    // non è un JWT leggibile
  }
  return null;
}

/** Device Authorization Grant (RFC 8628): l'operatore conferma nel browser; il token resta in memoria. */
export async function deviceFlowToken({ issuer, clientId, allowHttp, fetch: doFetch, sleep, log }) {
  const base = validateBaseUrl(issuer, { allowHttp, label: '--issuer' });
  const origin = new URL(base).origin;
  const discovery = await jsonRequest(doFetch, `${base}/.well-known/openid-configuration`, { method: 'GET' }, 'scoperta OIDC');
  const endpoint = (name) => {
    const value = discovery[name];
    if (typeof value !== 'string') throw new UsageError(`l'emittente non pubblica ${name}: il device flow non è abilitato`);
    const url = validateBaseUrl(value, { allowHttp, label: name });
    if (new URL(url).origin !== origin) throw new UsageError(`${name}: origine diversa dall'emittente, rifiutata`);
    return url;
  };
  const deviceUrl = endpoint('device_authorization_endpoint');
  const tokenUrl = endpoint('token_endpoint');
  const started = await jsonRequest(doFetch, deviceUrl, formPost({ client_id: clientId, scope: 'openid' }), 'avvio del device flow');
  if (typeof started.device_code !== 'string' || typeof started.user_code !== 'string') {
    throw new UsageError('risposta del device flow non valida');
  }
  const where = typeof started.verification_uri_complete === 'string' ? started.verification_uri_complete : started.verification_uri;
  log.secret(started.device_code);
  log.err(`Apri ${where} e conferma il codice ${started.user_code} per autorizzare lo script come operatore.`);
  let interval = Math.max(1, Number(started.interval) || 5);
  const deadline = Math.max(30, Number(started.expires_in) || 600);
  let waited = 0;
  while (waited <= deadline) {
    await sleep(interval * 1000);
    waited += interval;
    const res = await doFetch(tokenUrl, { ...formPost({ grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: started.device_code, client_id: clientId }), redirect: 'manual', signal: AbortSignal.timeout(30000) });
    const body = await safeJson(res);
    if (res.ok && typeof body?.access_token === 'string') {
      log.secret(body.access_token);
      return body.access_token;
    }
    const error = body?.error;
    if (error === 'authorization_pending') continue;
    if (error === 'slow_down') {
      interval += 5;
      continue;
    }
    throw new UsageError(`device flow non riuscito (${error ?? `HTTP ${res.status}`})`);
  }
  throw new UsageError('device flow scaduto prima della conferma');
}

function formPost(fields) {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams(fields).toString(),
  };
}

async function jsonRequest(doFetch, url, init, what) {
  let res;
  try {
    res = await doFetch(url, { headers: { Accept: 'application/json' }, ...init, redirect: 'manual', signal: AbortSignal.timeout(30000) });
  } catch (e) {
    throw new UsageError(`${what}: rete non raggiungibile (${e?.name ?? 'errore'})`);
  }
  const body = await safeJson(res);
  if (!res.ok || body === null || typeof body !== 'object') throw new UsageError(`${what}: risposta non valida (HTTP ${res.status})`);
  return body;
}

async function safeJson(res) {
  try {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Uscita senza segreti

const JWT_LIKE = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]*/g;
const BEARER = /(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi;

/** Logger che toglie da ogni riga i segreti noti (token, device code) e qualunque cosa somigli a un JWT. */
export function makeLogger(stdout, stderr) {
  const secrets = new Set();
  const clean = (s) => {
    let out = String(s);
    for (const secret of secrets) out = out.split(secret).join('***');
    return out.replace(JWT_LIKE, '***').replace(BEARER, '$1***');
  };
  return {
    secret: (s) => { if (typeof s === 'string' && s.length >= 6) secrets.add(s); },
    out: (s = '') => stdout.write(`${clean(s)}\n`),
    err: (s = '') => stderr.write(`${clean(s)}\n`),
    clean,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Client delle API, con le sole richieste ammesse

const READ_RESOURCES = /^\/v1\/(currencies|tiers|event-types|sources|internal-mappings|theme|message-templates|notification-rules|reward-categories|reward-bands|coupon-pools|rewards|attribute-definitions|segments|badges|achievements|leaderboards|audit)$/;
const CREATE_RESOURCES = /^\/v1\/(event-types|sources|message-templates|notification-rules|reward-categories|reward-bands|coupon-pools|rewards|segments|achievements|badges|leaderboards)$/;
const REPLACE_RESOURCES = /^\/v1\/(attribute-definitions|theme)$/;

/** Difesa in profondità: solo queste richieste possono partire, mai membri, movimenti, demo, transizioni. */
export function assertAllowedRequest(method, pathname, body) {
  const ok = (method === 'GET' && READ_RESOURCES.test(pathname))
    || (method === 'POST' && CREATE_RESOURCES.test(pathname))
    || (method === 'PUT' && REPLACE_RESOURCES.test(pathname));
  if (!ok) throw new Error(`richiesta non ammessa dallo script: ${method} ${pathname}`);
  if (body !== undefined && /MBR-\d/.test(JSON.stringify(body))) {
    throw new Error(`il corpo di ${method} ${pathname} contiene un identificativo di membro: i dati dei membri non si applicano`);
  }
}

export class Api {
  constructor({ targets, token, fetch: doFetch, timeoutMs = 30000 }) {
    this.targets = targets;
    this.token = token;
    this.fetch = doFetch;
    this.timeoutMs = timeoutMs;
    this.writes = 0;
  }

  async request(service, method, pathname, { query, body } = {}) {
    assertAllowedRequest(method, pathname, body);
    const base = this.targets[service];
    if (!base) throw new UsageError(`URL del servizio ${service} non configurato`);
    const qs = query ? `?${new URLSearchParams(query).toString()}` : '';
    const headers = { Accept: 'application/json', Authorization: `Bearer ${this.token}` };
    const init = { method, headers, redirect: 'manual', signal: AbortSignal.timeout(this.timeoutMs) };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    if (method !== 'GET') this.writes++;
    const res = await this.fetch(`${base}${pathname}${qs}`, init);
    if (res.status >= 300 && res.status < 400) throw new ApiError(res.status, 'REDIRECT', 'redirect non seguito');
    const json = await safeJson(res);
    if (!res.ok) {
      const detail = typeof json?.detail === 'string' ? json.detail : typeof json?.message === 'string' ? json.message : '';
      throw new ApiError(res.status, typeof json?.code === 'string' ? json.code : null, detail.slice(0, 200));
    }
    return { status: res.status, body: json };
  }
}

function asItems(body) {
  if (Array.isArray(body)) return body;
  if (body && Array.isArray(body.items)) return body.items;
  throw new Error('risposta di elenco non riconosciuta');
}

// ---------------------------------------------------------------------------------------------------------------
// Seed

const SEED_FILES = ['currencies', 'tiers', 'event-types', 'sources', 'internal-mappings', 'theme', 'message-templates',
  'notification-rules', 'reward-categories', 'reward-bands', 'coupon-pools', 'rewards', 'attribute-definitions',
  'segments', 'badges', 'achievements', 'leaderboards'];

export function loadSeed(dir) {
  const seed = {};
  for (const name of SEED_FILES) {
    const file = path.join(dir, `${name}.json`);
    let parsed;
    try {
      parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      throw new UsageError(`seed non leggibile: ${file} (${e.code ?? 'JSON non valido'})`);
    }
    const shapeOk = name === 'theme' ? parsed && typeof parsed === 'object' && !Array.isArray(parsed) : Array.isArray(parsed);
    if (!shapeOk) throw new UsageError(`seed non valido: ${file}`);
    seed[name] = parsed;
  }
  return seed;
}

/** Espressioni di data del seed (docs/10 §1) che servono alla configurazione: @now, @today, @today±Nd. */
export function resolveSeedDate(value, now) {
  if (typeof value !== 'string' || !value.startsWith('@')) return value ?? null;
  if (value === '@now') return new Date(now).toISOString();
  const m = /^@today(?:([+-])(\d{1,4})d)?$/.exec(value);
  if (!m) throw new UsageError(`espressione di data non supportata dallo script: ${value}`);
  const d = new Date(now);
  d.setUTCHours(0, 0, 0, 0);
  if (m[1]) d.setUTCDate(d.getUTCDate() + (m[1] === '-' ? -1 : 1) * Number(m[2]));
  return d.toISOString();
}

const pick = (obj, keys) => Object.fromEntries(keys.filter((k) => obj[k] !== undefined && obj[k] !== null).map((k) => [k, obj[k]]));

function looseEqual(a, b) {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

// ---------------------------------------------------------------------------------------------------------------
// Piano: entità nell'ordine delle dipendenze

const NO_CREATE = 'nessuna API di creazione (le PUT agiscono solo su righe già esistenti)';

/** Specifiche delle collezioni standard: GET elenco, POST per creare ciò che manca. */
const COLLECTIONS = [
  {
    id: 'event-types', service: 'ingestion', file: 'event-types', list: '/v1/event-types', keyOf: (x) => x.code,
    create: '/v1/event-types', audit: { type: 'event_type' },
    build: (s) => (s.origin === 'CUSTOM'
      ? { body: pick(s, ['code', 'name', 'description', 'category', 'icon', 'dataSchema', 'sampleData', 'enabled']) }
      : { skip: 'origine SYSTEM: la POST crea solo tipi CUSTOM e nessun\'altra API crea quelli di sistema' }),
  },
  {
    id: 'sources', service: 'ingestion', file: 'sources', list: '/v1/sources', keyOf: (x) => x.code,
    create: '/v1/sources', audit: { type: 'source' },
    build: (s, ctx) => {
      if (s.kind !== 'HTTP') return { skip: `fonte ${s.kind}: da API si creano solo fonti HTTP` };
      const missing = (s.allowedTypes ?? []).filter((t) => !ctx.known.has(`event-types:${t}`));
      if (missing.length) {
        return { skip: 'tipi azione ammessi non presenti (quelli di sistema non si creano da API): senza, la fonte si aprirebbe a tutti i tipi' };
      }
      return { body: pick(s, ['code', 'name', 'kind', 'enabled', 'allowedTypes', 'description']) };
    },
  },
  { id: 'currencies', service: 'wallet', file: 'currencies', list: '/v1/currencies', keyOf: (x) => x.code, create: null,
    build: () => ({ skip: NO_CREATE }) },
  { id: 'tiers', service: 'wallet', file: 'tiers', list: '/v1/tiers', keyOf: (x) => x.code, create: null,
    build: () => ({ skip: NO_CREATE }) },
  { id: 'internal-mappings', service: 'ingestion', file: 'internal-mappings', list: '/v1/internal-mappings', keyOf: (x) => x.factType.replace(/^(io\.loyaltyhub\.)?fact\./, ''), create: null,
    build: () => ({ skip: NO_CREATE }) },
  {
    id: 'message-templates', service: 'engagement', file: 'message-templates', list: '/v1/message-templates', keyOf: (x) => x.code,
    create: '/v1/message-templates', audit: { type: 'MESSAGE_TEMPLATE' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'channel', 'titleTpl', 'bodyTpl', 'icon', 'linkTarget', 'category']) }),
  },
  {
    id: 'notification-rules', service: 'engagement', file: 'notification-rules', list: '/v1/notification-rules', keyOf: (x) => x.code,
    create: '/v1/notification-rules', audit: { type: 'NOTIFICATION_RULE' },
    build: (s) => ({ body: pick(s, ['code', 'factType', 'condition', 'templateCode', 'enabled']), needs: [`message-templates:${s.templateCode}`] }),
  },
  {
    id: 'reward-categories', service: 'reward', file: 'reward-categories', list: '/v1/reward-categories', keyOf: (x) => x.code,
    create: '/v1/reward-categories', audit: { type: 'REWARD_CATEGORY' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'icon', 'sortOrder']) }),
  },
  {
    id: 'reward-bands', service: 'reward', file: 'reward-bands', list: '/v1/reward-bands', keyOf: (x) => x.code,
    create: '/v1/reward-bands', audit: { type: 'REWARD_BAND' }, order: (a, b) => a.sortOrder - b.sortOrder,
    build: (s) => ({ body: pick(s, ['code', 'name', 'pointsThreshold', 'color', 'sortOrder']) }),
  },
  {
    // Solo il pool: size e consumed del seed sono coupon generati e usati, cioè dati operativi.
    id: 'coupon-pools', service: 'reward', file: 'coupon-pools', list: '/v1/coupon-pools', keyOf: (x) => x.code,
    create: '/v1/coupon-pools', audit: { type: 'COUPON_POOL' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'prefix', 'validityDays']) }),
    remember: (item, ctx) => { if (item.response?.id) ctx.poolIds.set(item.key, item.response.id); },
    rememberExisting: (x, ctx) => { if (x.id) ctx.poolIds.set(x.code, x.id); },
  },
  {
    // Sempre DRAFT (regola 22): l'API ignora lo stato del seed e lo script non fa transizioni.
    id: 'rewards', service: 'reward', file: 'rewards', list: '/v1/rewards', keyOf: (x) => x.code,
    create: '/v1/rewards', audit: { type: 'REWARD' }, compareKeys: ['name', 'type', 'fulfilment'],
    build: (s, ctx) => {
      const needs = [`reward-categories:${s.category}`, `reward-bands:${s.band}`];
      if (s.couponPool) needs.push(`coupon-pools:${s.couponPool}`);
      const base = pick(s, ['code', 'name', 'description', 'terms', 'type', 'category', 'band', 'fulfilment', 'stockTotal', 'perMemberLimit', 'eligibleTiers', 'eligibleSegments']);
      const validFrom = resolveSeedDate(s.validFrom, ctx.now);
      return {
        needs,
        body: () => ({
          ...base,
          ...(validFrom ? { validFrom } : {}),
          ...(s.couponPool ? { couponPoolId: ctx.poolIds.get(s.couponPool) ?? `<id di ${s.couponPool}>` } : {}),
        }),
      };
    },
  },
  {
    id: 'segments', service: 'member', file: 'segments', list: '/v1/segments', paged: true, keyOf: (x) => x.code,
    create: '/v1/segments', audit: { type: 'SEGMENT' },
    // SegmentService.create valida i criteri contro le definizioni degli attributi personalizzati: un segmento che
    // usa `member.<attributo>` dipende dalla PUT degli attributi e, se questa manca o fallisce, si salta (non si tenta).
    build: (s, ctx) => (s.type === 'DYNAMIC'
      ? {
        body: { ...pick(s, ['code', 'name', 'description', 'type', 'criteria']) },
        needs: criteriaAttributeKeys(s.criteria, ctx.seed['attribute-definitions']).map((k) => `attribute-definitions:${k}`),
      }
      : { skip: `segmento ${s.type}: l'elenco è fatto di membri (MBR-*), dato dei membri` }),
  },
  {
    id: 'badges', service: 'gamification', file: 'badges', list: '/v1/badges', keyOf: (x) => x.code,
    create: '/v1/badges', audit: { type: 'BADGE' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'description', 'icon', 'color']) }),
  },
  {
    id: 'achievements', service: 'gamification', file: 'achievements', list: '/v1/achievements', keyOf: (x) => x.code,
    create: '/v1/achievements', audit: { type: 'ACHIEVEMENT' },
    build: (s) => ({
      body: pick(s, ['code', 'name', 'description', 'icon', 'actionTypes', 'filter', 'metric', 'sumField', 'streakUnit', 'target', 'period', 'repeatable', 'badgeCode']),
      needs: s.badgeCode ? [`badges:${s.badgeCode}`] : [],
    }),
  },
  {
    id: 'leaderboards', service: 'gamification', file: 'leaderboards', list: '/v1/leaderboards', keyOf: (x) => x.code,
    create: '/v1/leaderboards', audit: { type: 'LEADERBOARD' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'metric', 'actionTypes', 'period', 'topN', 'status']) }),
  },
];

/** Attributi personalizzati del seed usati da un criterio (`member.<chiave>`), visitando anche i gruppi annidati. */
export function criteriaAttributeKeys(criteria, attributeDefs) {
  const custom = new Set((attributeDefs ?? []).map((a) => a.key));
  const used = new Set();
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if (typeof node.field === 'string' && node.field.startsWith('member.') && custom.has(node.field.slice(7))) used.add(node.field.slice(7));
    for (const child of [...(Array.isArray(node.rules) ? node.rules : []), ...(Array.isArray(node.groups) ? node.groups : [])]) walk(child);
  };
  walk(criteria);
  return [...used].sort();
}

/** Ordine di esecuzione: dipendenze prima (tipi azione → fonti, categorie/fasce/pool → premi, template → regole…). */
const ORDER = ['event-types', 'sources', 'currencies', 'tiers', 'internal-mappings', 'theme', 'message-templates', 'notification-rules',
  'reward-categories', 'reward-bands', 'coupon-pools', 'rewards', 'attribute-definitions', 'segments', 'badges', 'achievements', 'leaderboards'];

async function listAll(api, spec) {
  if (!spec.paged) return asItems((await api.request(spec.service, 'GET', spec.list)).body);
  const out = [];
  for (let page = 0; page < 200; page++) {
    const body = (await api.request(spec.service, 'GET', spec.list, { query: { page: String(page), size: '100' } })).body;
    const items = asItems(body);
    out.push(...items);
    const total = body?.page?.totalPages;
    if (items.length === 0 || (typeof total === 'number' && page + 1 >= total) || (typeof total !== 'number' && items.length < 100)) break;
  }
  return out;
}

async function planCollection(ctx, spec) {
  const items = [];
  let existing = [];
  if (!ctx.offline) {
    try {
      existing = await listAll(ctx.api, spec);
    } catch (e) {
      return [{ entity: spec.id, service: spec.service, key: '*', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
    }
  }
  const byKey = new Map(existing.map((x) => [spec.keyOf(x), x]));
  for (const x of existing) {
    ctx.known.add(`${spec.id}:${spec.keyOf(x)}`);
    spec.rememberExisting?.(x, ctx);
  }
  const seed = spec.order ? [...ctx.seed[spec.file]].sort(spec.order) : ctx.seed[spec.file];
  for (const s of seed) {
    const key = spec.keyOf(s);
    const base = { entity: spec.id, service: spec.service, key };
    const found = byKey.get(key);
    if (found) {
      const built = spec.build(s, ctx);
      const body = typeof built.body === 'function' ? built.body(ctx) : built.body;
      const keys = spec.compareKeys ?? Object.keys(body ?? {});
      const differing = body ? keys.filter((k) => found[k] !== undefined && !looseEqual(found[k], body[k])) : [];
      // Una voce presente e uguale al seed è candidata alla verifica dell'audit (--verify-audit); una divergente no.
      items.push({
        ...base, action: 'present', note: differing.length ? `diverge dal seed (${differing.join(', ')}), non modificato` : undefined,
        audit: !differing.length && spec.audit ? { type: spec.audit.type, ids: [key], actions: ['CREATE', 'UPDATE'] } : undefined,
      });
      continue;
    }
    const built = spec.build(s, ctx);
    if (built.skip) {
      items.push({ ...base, action: 'skip', reason: built.skip });
      continue;
    }
    const unmet = (built.needs ?? []).filter((n) => !ctx.known.has(n));
    if (unmet.length) {
      items.push({ ...base, action: 'skip', reason: `dipendenza non disponibile: ${unmet.join(', ')}` });
      continue;
    }
    ctx.known.add(`${spec.id}:${key}`);
    items.push({
      ...base, action: 'create', method: 'POST', path: spec.create, body: built.body, needs: built.needs ?? [],
      audit: { type: spec.audit.type, ids: [key], actions: ['CREATE', 'UPDATE'] },
      remember: spec.remember,
    });
  }
  return items;
}

/** Attributi personalizzati: la PUT sostituisce l'intero elenco, quindi si rimanda l'esistente più i mancanti. */
async function planAttributes(ctx) {
  const id = 'attribute-definitions';
  let existing = [];
  if (!ctx.offline) {
    try {
      existing = asItems((await ctx.api.request('member', 'GET', '/v1/attribute-definitions')).body);
    } catch (e) {
      return [{ entity: id, service: 'member', key: '*', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
    }
  }
  const have = new Set(existing.map((a) => a.key));
  const missing = ctx.seed[id].filter((a) => !have.has(a.key));
  const present = ctx.seed[id].filter((a) => have.has(a.key));
  for (const a of ctx.seed[id]) ctx.known.add(`${id}:${a.key}`);
  // La PUT sostituisce l'elenco: un'unica voce di audit (entityId `all`) copre tutte le chiavi presenti.
  const items = present.map((a) => ({ entity: id, service: 'member', key: a.key, action: 'present', audit: { type: 'attribute_definition', ids: ['all'], actions: ['UPDATE', 'CREATE'] } }));
  if (missing.length) {
    const merged = [...existing.map((a) => pick(a, ['key', 'label', 'type', 'options'])), ...missing.map((a) => pick(a, ['key', 'label', 'type', 'options']))];
    items.push({
      entity: id, service: 'member', key: 'all', action: 'create', method: 'PUT', path: '/v1/attribute-definitions', body: merged,
      label: `${missing.length} attributi (${missing.map((a) => a.key).join(', ')})`,
      provides: ctx.seed[id].map((a) => `${id}:${a.key}`),
      audit: { type: 'attribute_definition', ids: ['all'], actions: ['UPDATE', 'CREATE'] },
    });
  }
  return items;
}

/** Tema: GET risponde col tema Aurora se la riga manca (updatedAt nullo). Si scrive solo se mai salvato e diverso. */
async function planTheme(ctx) {
  const id = 'theme';
  const s = ctx.seed.theme;
  const body = { ...pick(s, ['programName', 'tagline', 'logoUrl', 'heroTitle', 'heroSubtitle', 'fontDisplay']), colors: s.colors, currencyNames: s.currencyNames };
  if (ctx.offline) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'create', method: 'PUT', path: '/v1/theme', body, audit: { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] } }];
  }
  let current;
  try {
    current = (await ctx.api.request('engagement', 'GET', '/v1/theme')).body;
  } catch (e) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
  }
  const norm = (t) => JSON.stringify({
    ...Object.fromEntries(['programName', 'tagline', 'logoUrl', 'heroTitle', 'heroSubtitle', 'fontDisplay'].map((k) => [k, t?.[k] ?? null])),
    colors: Object.fromEntries(Object.entries(t?.colors ?? {}).sort().map(([k, v]) => [k, String(v).toUpperCase()])),
    currencyNames: Object.fromEntries(Object.entries(t?.currencyNames ?? {}).sort()),
  });
  if (norm(current) === norm(body)) {
    // Con `updatedAt` nullo il tema è quello di ripiego del servizio: nessuna scrittura, quindi nessuna voce di audit.
    return [{ entity: id, service: 'engagement', key: 'default', action: 'present', audit: current?.updatedAt ? { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] } : undefined }];
  }
  if (current?.updatedAt) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'present', note: 'già personalizzato, diverge dal seed, non modificato' }];
  }
  return [{
    entity: id, service: 'engagement', key: 'default', action: 'create', method: 'PUT', path: '/v1/theme', body: { ...body, version: current?.version ?? 0 },
    audit: { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] },
  }];
}

function describeError(e) {
  if (e instanceof ApiError) return e.message;
  return e?.name === 'TimeoutError' ? 'timeout' : (e?.cause?.code ?? e?.code ?? e?.name ?? 'errore di rete');
}

export async function buildPlan({ seed, api, offline, now }) {
  const ctx = { seed, api, offline, now, known: new Set(), poolIds: new Map() };
  const byId = new Map(COLLECTIONS.map((c) => [c.id, c]));
  const plan = [];
  for (const id of ORDER) {
    if (id === 'theme') plan.push(...await planTheme(ctx));
    else if (id === 'attribute-definitions') plan.push(...await planAttributes(ctx));
    else plan.push(...await planCollection(ctx, byId.get(id)));
  }
  // Un corpo che contiene un identificativo di membro non parte mai: lo si verifica già sul piano.
  for (const item of plan) {
    if (item.action !== 'create') continue;
    assertAllowedRequest(item.method, item.path, typeof item.body === 'function' ? item.body(ctx) : item.body);
  }
  return { plan, ctx };
}

// ---------------------------------------------------------------------------------------------------------------
// Esecuzione e verifica dell'audit

export async function applyPlan({ plan, ctx, api, log }) {
  const failed = new Set();
  const results = [];
  for (const item of plan) {
    if (item.action !== 'create') continue;
    const dep = (item.needs ?? []).find((n) => failed.has(n));
    if (dep) {
      item.action = 'skip';
      item.reason = `dipendenza non creata: ${dep}`;
      continue;
    }
    try {
      const body = typeof item.body === 'function' ? item.body(ctx) : item.body;
      const res = await api.request(item.service, item.method, item.path, { body });
      item.response = res.body;
      item.result = 'created';
      item.remember?.(item, ctx);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // Creato nel frattempo da un'altra esecuzione: stato voluto raggiunto, nessuna voce di audit attesa da noi.
        item.result = 'already';
      } else {
        item.result = 'failed';
        item.error = describeError(e);
        failed.add(`${item.entity}:${item.key}`);
        for (const p of item.provides ?? []) failed.add(p);
        log.err(`  ✗ ${item.entity} ${item.key}: ${item.error}`);
      }
    }
    results.push(item);
  }
  return results;
}

const DENY_ACTORS = new Set(['', 'demo', 'system', 'anonymous', 'unknown', '-']);

export function isRealActor(record) {
  const name = String(record.actorName ?? '').trim().toLowerCase();
  const role = String(record.actorRole ?? '').trim().toLowerCase();
  return !DENY_ACTORS.has(name) && role !== 'system' && !name.startsWith('member:') && !name.startsWith('src-');
}

/**
 * Regola 21: per ogni voce da verificare deve esistere su GET /v1/audit (insight) una voce con lo stesso tipo e
 * identificativo dell'entità, successiva a `since`, con attore reale. Se dal token si conosce l'identità
 * dell'operatore (`expectedActor`), l'attore della voce deve essere proprio quella: la scrittura di un altro operatore
 * sulla stessa entità nella stessa finestra non vale. Senza identità (token opaco) si accetta ogni attore reale e
 * l'esito lo dichiara. L'API filtra per `entityType` e `entityId` (uguaglianza esatta): si legge un elenco per tipo
 * di entità (il filtro per identificativo costerebbe una richiesta per scrittura) e si abbina l'identificativo qui.
 * L'audit viaggia sul bus: si riprova fino al timeout.
 */
export async function verifyAudit({ api, items, since, expectedActor = null, timeoutSec, intervalSec, sleep }) {
  const pending = new Map(items.map((item) => [`${item.entity}:${item.key}`, item]));
  const problems = new Map();
  const attempts = Math.max(1, Math.ceil(timeoutSec / Math.max(intervalSec, 0.001)) + 1);
  for (let attempt = 0; attempt < attempts && pending.size; attempt++) {
    if (attempt > 0) await sleep(intervalSec * 1000);
    const byType = new Map();
    for (const item of pending.values()) {
      if (!byType.has(item.audit.type)) byType.set(item.audit.type, await fetchAudit(api, { since, entityType: item.audit.type }));
    }
    for (const [id, item] of [...pending]) {
      const matching = byType.get(item.audit.type).filter((r) => item.audit.ids.includes(r.entityId)
        && String(r.entityType ?? '').toLowerCase() === item.audit.type.toLowerCase()
        && item.audit.actions.includes(String(r.action ?? '').toUpperCase()));
      const real = matching.filter(isRealActor);
      if (real.some((r) => expectedActor === null || String(r.actorName ?? '').trim() === expectedActor)) {
        pending.delete(id);
        problems.delete(id);
      } else {
        problems.set(id, !matching.length ? 'voce di audit mancante'
          : real.length ? 'voce di audit di un altro attore, non dell\'operatore del token'
            : 'voce di audit con attore non reale');
      }
    }
  }
  return { verified: items.length - pending.size, problems: [...pending.keys()].map((id) => ({ id, reason: problems.get(id) })) };
}

/**
 * Voci di audit di un tipo di entità dall'istante `since`, più recenti per prima. Un inserimento durante la lettura
 * sposta le pagine successive in avanti: ripete una voce già letta, non ne salta; si deduplica per `id`.
 */
async function fetchAudit(api, { since, entityType }) {
  const out = new Map();
  for (let page = 0; page < 100; page++) {
    const body = (await api.request('insight', 'GET', '/v1/audit', { query: { from: since.toISOString(), entityType, page: String(page), size: '100' } })).body;
    const items = asItems(body);
    for (const r of items) out.set(r.id ?? `${page}:${out.size}`, r);
    const total = body?.page?.totalPages;
    if (items.length === 0 || (typeof total === 'number' ? page + 1 >= total : items.length < 100)) break;
  }
  return [...out.values()];
}

// ---------------------------------------------------------------------------------------------------------------
// Stampa del piano

function printPlan(log, plan, { apply, offline, verify }) {
  log.out(verify ? 'Piano (--verify-audit: nessuna scrittura)' : offline ? 'Piano dal solo seed (--offline: nessuna lettura, tutto risulta da creare)' : apply ? 'Piano applicato' : 'Piano (dry-run: nessuna scrittura)');
  const entities = [...new Set(plan.map((i) => i.entity))];
  const totals = { create: 0, present: 0, skip: 0, error: 0 };
  for (const entity of entities) {
    const items = plan.filter((i) => i.entity === entity);
    const by = (a) => items.filter((i) => i.action === a);
    const create = by('create');
    totals.create += create.length;
    totals.present += by('present').length;
    totals.skip += by('skip').length;
    totals.error += by('error').length;
    log.out(`[${items[0].service}] ${entity}: ${create.length} da creare · ${by('present').length} presenti · ${by('skip').length} saltati${by('error').length ? ` · ERRORE` : ''}`);
    if (create.length) log.out(`    creare: ${create.map((i) => i.label ?? i.key).join(', ')}`);
    for (const i of by('present').filter((x) => x.note)) log.out(`    ${i.key}: ${i.note}`);
    const reasons = new Map();
    for (const i of by('skip')) reasons.set(i.reason, [...(reasons.get(i.reason) ?? []), i.key]);
    for (const [reason, keys] of reasons) log.out(`    saltati (${keys.length}): ${reason} [${keys.slice(0, 4).join(', ')}${keys.length > 4 ? ', …' : ''}]`);
    for (const i of by('error')) log.out(`    ${i.reason}`);
  }
  log.out(`Totale: ${totals.create} da creare · ${totals.present} presenti · ${totals.skip} saltati · ${totals.error} errori di lettura`);
  return totals;
}

function printNotIncluded(log) {
  log.out('Non incluso per scelta:');
  for (const [what, why] of NOT_INCLUDED) log.out(`  - ${what}: ${why}`);
  log.out('I premi creati restano in DRAFT: lo script non approva né pubblica (regola 22). Q-617: default proposto, APERTA.');
}

// ---------------------------------------------------------------------------------------------------------------
// Programma principale

/**
 * @returns {Promise<number>} codice di uscita: 0 ok, 1 errore di lettura/scrittura/audit, 2 uso non valido.
 */
export async function main(argv, deps = {}) {
  const env = deps.env ?? process.env;
  const doFetch = deps.fetch ?? globalThis.fetch;
  const sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  const now = deps.now ?? (() => Date.now());
  const log = makeLogger(deps.stdout ?? process.stdout, deps.stderr ?? process.stderr);
  try {
    const opts = parseArgs(argv);
    if (opts.help) {
      log.out(HELP);
      return 0;
    }
    const seed = loadSeed(opts.seedDir ?? path.resolve(HERE, '..', 'seed'));
    const apply = opts.apply;
    let api = null;
    let token = null;
    if (!opts.offline) {
      const targets = resolveTargets(opts, env);
      const tokenFile = opts.tokenFile ?? env.LH_OPERATOR_TOKEN_FILE;
      if (tokenFile) token = readTokenFile(tokenFile);
      else if (opts.issuer) token = await deviceFlowToken({ issuer: opts.issuer, clientId: opts.clientId, allowHttp: opts.allowHttp, fetch: doFetch, sleep, log });
      else throw new UsageError('serve un token: --token-file <file> (o LH_OPERATOR_TOKEN_FILE) oppure --issuer <url> per il device flow');
      log.secret(token);
      api = new Api({ targets, token, fetch: doFetch });
    }

    const started = new Date(now());
    const { plan, ctx } = await buildPlan({ seed, api, offline: opts.offline, now: started.getTime() });
    const totals = printPlan(log, plan, { apply, offline: opts.offline, verify: opts.verifyAudit });
    printNotIncluded(log);
    let code = totals.error ? 1 : 0;
    const expectedActor = tokenIdentity(token);
    const audit = (items, since) => verifyAudit({ api, items, since, expectedActor, timeoutSec: opts.auditTimeout, intervalSec: opts.auditInterval, sleep });
    const reportAudit = (result, total) => {
      log.out(`Audit (regola 21): ${result.verified}/${total} scritture con voce e attore reale${expectedActor ? ' (attore = operatore del token)' : ''}`);
      if (!expectedActor) log.out('  Nota: il token non è un JWT leggibile (opaco) o non porta un nome: si accetta ogni attore reale, non si verifica che sia proprio l\'operatore del token.');
      for (const p of result.problems) log.err(`  ✗ ${p.id}: ${p.reason}`);
    };

    if (opts.verifyAudit) {
      // Nessuna scrittura: verifica le voci già presenti dalla finestra indicata (dopo un --apply finito con audit mancante).
      const since = new Date(opts.since.getTime() - 120000);
      const present = plan.filter((i) => i.action === 'present' && i.audit);
      const pendingCreates = plan.filter((i) => i.action === 'create').length;
      if (pendingCreates) {
        log.err(`✗ ${pendingCreates} voci risultano ancora da creare: non c'è nulla da verificare per loro, esegui --apply`);
        code = 1;
      }
      if (!present.length) {
        log.out('Audit: nessuna voce presente da verificare.');
        return 1;
      }
      let result;
      try {
        result = await audit(present, since);
      } catch (e) {
        log.err(`✗ verifica dell'audit non riuscita: ${describeError(e)}`);
        return 1;
      }
      reportAudit(result, present.length);
      return result.problems.length ? 1 : code;
    }
    if (!apply) {
      log.out(opts.offline ? '' : 'Dry-run: nessuna scrittura. Aggiungi --apply per applicare.');
      return code;
    }

    log.out(`Inizio esecuzione: ${started.toISOString()} (per ripetere la sola verifica dell'audit: --verify-audit --since ${started.toISOString()})`);
    const results = await applyPlan({ plan, ctx, api, log });
    const created = results.filter((r) => r.result === 'created');
    const failed = results.filter((r) => r.result === 'failed');
    log.out(`Scritture: ${created.length} create · ${results.filter((r) => r.result === 'already').length} già presenti (409) · ${failed.length} fallite · ${plan.filter((i) => i.action === 'skip' && i.reason?.startsWith('dipendenza non creata')).length} saltate per dipendenza`);
    if (failed.length) code = 1;
    if (created.length) {
      let result;
      try {
        result = await audit(created, new Date(started.getTime() - 120000));
      } catch (e) {
        log.err(`✗ verifica dell'audit non riuscita: ${describeError(e)}`);
        return 1;
      }
      reportAudit(result, created.length);
      if (result.problems.length) code = 1;
    } else {
      log.out('Audit: nessuna scrittura da verificare in questa esecuzione.');
      log.out('  Attenzione: questa esecuzione NON sostituisce la verifica di un --apply precedente finito con audit mancante (regola 21): usa --verify-audit --since <inizio di quell\'esecuzione>.');
    }
    return code;
  } catch (e) {
    if (e instanceof UsageError) {
      log.err(`✗ ${e.message}`);
      return 2;
    }
    log.err(`✗ ${describeError(e)}${e?.message && !(e instanceof ApiError) ? `: ${e.message}` : ''}`);
    return 1;
  }
}

/** URL dei servizi: --svc-url, poi LH_SVC_<NOME>_URL, poi --base-url o LH_BASE_URL. */
export function resolveTargets(opts, env) {
  const fallback = opts.baseUrl ?? env.LH_BASE_URL;
  const targets = {};
  const missing = [];
  for (const name of SERVICES) {
    const raw = opts.svcUrl[name] ?? env[`LH_SVC_${name.toUpperCase()}_URL`] ?? fallback;
    if (!raw) {
      missing.push(name);
      continue;
    }
    targets[name] = validateBaseUrl(raw, { allowHttp: opts.allowHttp, label: `URL di ${name}` });
  }
  if (missing.length) throw new UsageError(`URL mancante per: ${missing.join(', ')} (usa --base-url, --svc-url o LH_SVC_<SERVIZIO>_URL)`);
  return targets;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = await main(process.argv.slice(2));
}
