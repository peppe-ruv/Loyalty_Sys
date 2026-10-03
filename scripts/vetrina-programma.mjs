#!/usr/bin/env node
// Configurazione di programma da seed/ a un'installazione enterprise, tramite le API REST dei servizi con il token
// di un operatore ADMIN (F2-DIST-09, F2-DIST-06, ADR-049, M8.14d; Q-617 decisa il 2026-09-30).
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
//      node scripts/vetrina-programma.mjs --emit-seed           (rigenera lo snapshot del seed per il web, vedi sotto)
// Test: node --test scripts/vetrina-programma.test.mjs
//
// V10 (ADR-051, Q-722, Q-723): il NUCLEO PURO (piano, scrittura, verifica dell'audit) sta in web/lib/vetrina/programma-core.mjs
// ed è lo stesso che usa il BFF del web per il pulsante «Carica il programma di esempio» del backoffice. Qui restano solo le
// parti della riga di comando: argomenti, file del token, device flow, controllo degli URL, lettura del seed dal filesystem,
// stampa. La riga di comando NON crea campagne, fonte di test né storie (ambito CLI_SCOPE, invariato da M8.14d): sono del
// programma dal backoffice, che le crea in DRAFT e le verifica nell'audit con l'operatore del token.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  Api, ApiError, applyPlan, assertAllowedRequest, buildPlan, CLI_SCOPE, criteriaAttributeKeys, describeError, fetchTransport, isRealActor,
  NOT_INCLUDED, resolveSeedDate, safeJson, SERVICES, UsageError, verifyAudit,
} from '../web/lib/vetrina/programma-core.mjs';

// Il resto del codice e i test importano questi simboli da qui, com'erano prima dello spostamento nel nucleo.
export { Api, ApiError, applyPlan, assertAllowedRequest, buildPlan, criteriaAttributeKeys, isRealActor, NOT_INCLUDED, resolveSeedDate, SERVICES, UsageError, verifyAudit };

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB_SEED_SNAPSHOT = path.resolve(HERE, '..', 'web', 'lib', 'vetrina', 'programma-seed.generated.json');

const LOOPBACK = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);


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
const FLAG_OPTIONS = new Set(['--apply', '--dry-run', '--offline', '--allow-http', '--verify-audit', '--emit-seed', '--help']);

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
(F2-DIST-09, ADR-049, Q-617 decisa il 2026-09-30). Predefinito: --dry-run (mostra il piano).

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
  --emit-seed              rigenera web/lib/vetrina/programma-seed.generated.json da seed/ (lo snapshot del programma dal backoffice)
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

/**
 * Seed del programma dal backoffice (V10): quello della riga di comando più le campagne e la fonte di test con le storie
 * (seed/vetrina-test.json, Q-722). È ciò che il web impacchetta nello snapshot generato.
 */
export function loadProgramSeed(dir) {
  const seed = loadSeed(dir);
  for (const [name, key] of [['campaigns', 'campaigns'], ['vetrina-test', 'vetrinaTest']]) {
    const file = path.join(dir, `${name}.json`);
    try {
      seed[key] = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      throw new UsageError(`seed non leggibile: ${file} (${e.code ?? 'JSON non valido'})`);
    }
  }
  if (!Array.isArray(seed.campaigns) || !Array.isArray(seed.vetrinaTest?.stories) || !seed.vetrinaTest?.source) throw new UsageError('seed delle campagne o della fonte di test non valido');
  return seed;
}

/** Testo dello snapshot per il web: JSON stabile (chiavi nell'ordine del seed), senza `_note` né elenchi di membri (`memberIds` dei segmenti statici: mai nel web). Il controllo di deriva lo confronta byte per byte. */
export function serializeSeedSnapshot(seed) {
  return `${JSON.stringify(seed, (key, value) => (key === '_note' || key === 'memberIds' ? undefined : value), 2)}\n`;
}

/** Percorso dello snapshot generato (letto dal web e da scripts/check-vetrina.mjs). */
export const SEED_SNAPSHOT_FILE = WEB_SEED_SNAPSHOT;




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
  log.out('I premi creati restano in DRAFT: lo script non approva né pubblica (regola 22). Q-617 decisa il 2026-09-30.');
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
    if (opts.emitSeed) {
      // Rigenera lo snapshot del seed che il web impacchetta (l'immagine del ruolo web non contiene seed/).
      const text = serializeSeedSnapshot(loadProgramSeed(opts.seedDir ?? path.resolve(HERE, '..', 'seed')));
      fs.writeFileSync(WEB_SEED_SNAPSHOT, text);
      log.out(`Snapshot del seed scritto: ${path.relative(path.resolve(HERE, '..'), WEB_SEED_SNAPSHOT)} (${text.length} byte)`);
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
      api = new Api({ transport: fetchTransport({ targets, getToken: () => token, fetch: doFetch }), scope: CLI_SCOPE });
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
