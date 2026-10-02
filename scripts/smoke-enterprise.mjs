#!/usr/bin/env node
// Smoke del profilo enterprise (F2-QA-04, M8.2, ADR-049, M8.14 V6). Due modi, stesso codice:
//
//   compose  — nel job CI `smoke enterprise (compose, OIDC)`, contro il compose di riferimento con l'overlay di test
//              del realm (scripts/smoke-enterprise.sh). Login OIDC reale di un operatore attraverso il BFF e il client
//              `web` di produzione (Authorization Code + PKCE, pagina di login di Keycloak, cambio della password
//              temporanea, configurazione dell'OTP e codice TOTP: la MFA vera del realm, nessun direct access grant),
//              una scrittura di configurazione dal proxy del BFF (con CSRF) e la sua voce in GET /v1/audit con
//              l'attore reale; 401 senza token sull'hub e senza sessione sul BFF; 404 PERSONA_DISABLED su
//              POST /api/persona; un membro di prova non passa sulle API del backoffice (403 FORBIDDEN_ROLE).
//   vetrina  — nel workflow pianificato smoke-vetrina.yml, contro la vetrina online, SENZA credenziali e solo con GET:
//              health del web, HUB-02 raggiungibile con banner e tessere attive, login avviato dal BFF verso il
//              realm `loyaltyhub`, discovery OIDC e JWKS, pagina di login senza registrazione (Q-619), console
//              /admin e realm master non esposti, API del BFF chiuse senza sessione.
//   in entrambi — realm dei membri `loyaltyhub-members` (ADR-051): login dei membri avviato dal BFF
//              (GET /api/auth/login?realm=members → 303 verso il suo endpoint di autorizzazione, client `portal`,
//              PKCE S256, callback /api/auth/callback/members), discovery OIDC e JWKS del realm, pagina di login
//              raggiungibile (nel modo compose anche con la registrazione aperta dei membri).
//
// Sicurezza (regole 18-20): solo https (http solo verso loopback, e per web e IdP solo con --allow-http, che usano i
// test). Le password arrivano solo da file 0600 (formato di deploy/idp/bootstrap.sh: `utente: password`), mai da
// argomenti o variabili d'ambiente. Nessuna password, codice OTP, segreto TOTP, cookie, token, codice di
// autorizzazione o corpo di pagina finisce nell'output: si stampano solo esiti, codici HTTP, codici di errore e
// percorsi senza query. La password cambiata al primo accesso è casuale e resta in memoria.
//
// Uso: node scripts/smoke-enterprise.mjs vetrina --web https://web.example.org
//      node scripts/smoke-enterprise.mjs compose --web https://web.lh.test:8443 --hub http://127.0.0.1:8080 \
//        --operator marta.admin --operator-credentials <file> [--member testmember --member-credentials <file>]
// Test: node --test scripts/smoke-enterprise.test.mjs
import crypto from 'node:crypto';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Errore di uso (uscita 2): nessuna richiesta è partita. */
export class UsageError extends Error {}
/** Controllo fallito (uscita 1). Il messaggio non contiene mai segreti. */
export class SmokeError extends Error {}

export const REALM = 'loyaltyhub';
/** Realm dei membri del portale e dei widget (ADR-051): client `portal`, login dal BFF con `?realm=members`. */
export const MEMBER_REALM = 'loyaltyhub-members';
export const SESSION_COOKIE = '__Host-lh_session';
export const CSRF_COOKIE = '__Host-lh_csrf';
export const AUTH_FLOW_COOKIE = '__Host-lh_auth';
const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]']);

const HELP = `Uso:
  node scripts/smoke-enterprise.mjs vetrina --web <origine https> [--registration closed|open|any] [--settle <s>]
  node scripts/smoke-enterprise.mjs compose --web <origine> --hub <url loopback> --operator <utente>
       --operator-credentials <file 0600> [--member <utente> --member-credentials <file 0600>]
       [--registration closed|open|any] [--settle <s>] [--audit-timeout <s>]
Opzioni comuni: --allow-http (solo loopback, per i test), --help.
I file delle credenziali hanno righe \`utente: password\` (formato di deploy/idp/bootstrap.sh) e permessi 0600.`;

// ---------------------------------------------------------------------------------------------------------------
// Argomenti e URL

/** Origine di web o IdP: https, oppure http solo verso loopback con allowHttp; niente percorso, query o credenziali. */
export function validateOrigin(raw, { allowHttp = false, label = 'URL' } = {}) {
  let u;
  try {
    u = new URL(String(raw));
  } catch {
    throw new UsageError(`${label}: non è un URL valido`);
  }
  if (u.username || u.password) throw new UsageError(`${label}: niente credenziali nell'URL`);
  if (u.search || u.hash || (u.pathname !== '/' && u.pathname !== '')) {
    throw new UsageError(`${label}: serve un'origine, senza percorso, query o frammento`);
  }
  if (u.protocol === 'http:') {
    if (!allowHttp || !LOOPBACK.has(u.hostname)) {
      throw new UsageError(`${label}: serve https (http solo verso localhost con --allow-http)`);
    }
  } else if (u.protocol !== 'https:') {
    throw new UsageError(`${label}: serve https`);
  }
  return u.origin;
}

/** URL dell'hub: solo loopback (nel compose è pubblicato su 127.0.0.1), http o https. */
export function validateHub(raw) {
  let u;
  try {
    u = new URL(String(raw));
  } catch {
    throw new UsageError('--hub: non è un URL valido');
  }
  if (!['http:', 'https:'].includes(u.protocol) || !LOOPBACK.has(u.hostname) || u.username || u.password || u.search) {
    throw new UsageError('--hub: solo un URL verso localhost, 127.0.0.1 o ::1, senza credenziali né query');
  }
  return u.origin;
}

export function parseArgs(argv) {
  const opts = { mode: null, allowHttp: false, help: false };
  const args = [...argv];
  const value = (name) => {
    const v = args.shift();
    if (v === undefined || v.startsWith('--')) throw new UsageError(`${name} vuole un valore`);
    return v;
  };
  while (args.length) {
    const a = args.shift();
    switch (a) {
      case 'vetrina':
      case 'compose':
        if (opts.mode) throw new UsageError('un solo modo: vetrina oppure compose');
        opts.mode = a;
        break;
      case '--web': opts.web = value(a); break;
      case '--hub': opts.hub = value(a); break;
      case '--operator': opts.operator = value(a); break;
      case '--operator-credentials': opts.operatorCredentials = value(a); break;
      case '--member': opts.member = value(a); break;
      case '--member-credentials': opts.memberCredentials = value(a); break;
      case '--registration': opts.registration = value(a); break;
      case '--settle': opts.settle = Number(value(a)); break;
      case '--audit-timeout': opts.auditTimeout = Number(value(a)); break;
      case '--allow-http': opts.allowHttp = true; break;
      case '--help': case '-h': opts.help = true; break;
      default: throw new UsageError(`argomento sconosciuto: ${a}`);
    }
  }
  if (opts.help) return opts;
  if (!opts.mode) throw new UsageError('manca il modo: vetrina oppure compose');
  if (!opts.web) throw new UsageError('manca --web');
  opts.web = validateOrigin(opts.web, { allowHttp: opts.allowHttp, label: '--web' });
  opts.registration ??= opts.mode === 'vetrina' ? 'closed' : 'any';
  if (!['open', 'closed', 'any'].includes(opts.registration)) throw new UsageError('--registration: open, closed o any');
  opts.settle ??= opts.mode === 'vetrina' ? 60 : 180;
  opts.auditTimeout ??= 120;
  for (const k of ['settle', 'auditTimeout']) {
    if (!Number.isFinite(opts[k]) || opts[k] < 0 || opts[k] > 3600) throw new UsageError(`--${k}: secondi tra 0 e 3600`);
  }
  if (opts.mode === 'vetrina') {
    for (const k of ['hub', 'operator', 'operatorCredentials', 'member', 'memberCredentials']) {
      if (opts[k] !== undefined) throw new UsageError('il modo vetrina gira senza credenziali e senza hub: togli le opzioni del modo compose');
    }
  } else {
    if (!opts.hub) throw new UsageError('manca --hub');
    opts.hub = validateHub(opts.hub);
    if (!opts.operator || !opts.operatorCredentials) throw new UsageError('servono --operator e --operator-credentials');
    if (Boolean(opts.member) !== Boolean(opts.memberCredentials)) throw new UsageError('--member e --member-credentials vanno insieme');
  }
  return opts;
}

/**
 * Password di `username` da un file `utente: password` (deploy/idp/bootstrap.sh). Il file deve essere regolare, non
 * un collegamento simbolico, con permessi 0600 o più stretti; la password non viene mai stampata.
 */
export function readCredentials(file, username) {
  let fd;
  try {
    fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0));
  } catch (e) {
    throw new UsageError(`file delle credenziali non leggibile: ${file} (${e.code === 'ELOOP' ? 'collegamento simbolico rifiutato' : e.code})`);
  }
  try {
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw new UsageError(`file delle credenziali non regolare: ${file}`);
    if ((st.mode & 0o077) !== 0) throw new UsageError(`file delle credenziali con permessi troppo larghi (serve 0600): ${file}`);
    if (st.size > 64 * 1024) throw new UsageError(`file delle credenziali troppo grande: ${file}`);
    const text = fs.readFileSync(fd, 'utf8');
    for (const line of text.split('\n')) {
      if (line.startsWith('#')) continue;
      const sep = line.indexOf(': ');
      if (sep <= 0 || line.slice(0, sep) !== username) continue;
      const pw = line.slice(sep + 2).replace(/\r$/, '');
      if (!pw || pw.startsWith('(')) break;
      return pw;
    }
    throw new UsageError(`nessuna password per ${username} in ${file}`);
  } finally {
    fs.closeSync(fd);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// HTML, cookie e TOTP

export function decodeEntities(s) {
  return String(s)
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

/** Attributi di un tag di apertura (nomi in minuscolo, valori decodificati; attributo senza valore = ''). */
export function parseAttrs(tag) {
  const out = {};
  const body = tag.replace(/^<[a-zA-Z0-9]+/, '').replace(/\/?>$/, '');
  for (const m of body.matchAll(/([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g)) {
    out[m[1].toLowerCase()] = decodeEntities(m[2] ?? m[3] ?? m[4] ?? '');
  }
  return out;
}

const SKIPPED_INPUTS = new Set(['submit', 'button', 'image', 'reset', 'file']);

/** Moduli di una pagina: id, action (decodificata), metodo e campi che un browser invierebbe senza interazione. */
export function parseForms(html) {
  const forms = [];
  for (const m of String(html).matchAll(/<form\b[^>]*>([\s\S]*?)<\/form>/gi)) {
    const attrs = parseAttrs(m[0].slice(0, m[0].indexOf('>') + 1));
    const inputs = [];
    for (const i of m[1].matchAll(/<input\b[^>]*>/gi)) {
      const a = parseAttrs(i[0]);
      const type = (a.type || 'text').toLowerCase();
      if (!a.name || SKIPPED_INPUTS.has(type)) continue;
      if ((type === 'checkbox' || type === 'radio') && !('checked' in a)) continue;
      inputs.push({ name: a.name, type, value: a.value ?? ((type === 'checkbox' || type === 'radio') ? 'on' : '') });
    }
    forms.push({ id: attrs.id ?? '', action: attrs.action ?? '', method: (attrs.method || 'get').toLowerCase(), inputs });
  }
  return forms;
}

/** Messaggio d'errore mostrato da Keycloak (testo, senza tag, al più 200 caratteri) o ''. */
export function pageFeedback(html) {
  const m = String(html).match(/<(span|div|p)\b[^>]*(?:id="input-error[^"]*"|class="[^"]*(?:kc-feedback-text|alert__title|alert-error)[^"]*")[^>]*>([\s\S]*?)<\/\1>/i);
  if (!m) return '';
  return decodeEntities(m[2].replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim().slice(0, 200);
}

/** Cookie per host (porta inclusa): basta per un login fra due origini; percorso e dominio non servono qui. */
export class CookieJar {
  #byHost = new Map();

  store(url, setCookies) {
    const host = new URL(url).host;
    const jar = this.#byHost.get(host) ?? new Map();
    for (const raw of setCookies) {
      const [pair, ...attrs] = raw.split(';');
      const eq = pair.indexOf('=');
      if (eq <= 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      const expired = attrs.some((a) => {
        const [k, v = ''] = a.split('=').map((x) => x.trim());
        if (k.toLowerCase() === 'max-age') return Number(v) <= 0;
        if (k.toLowerCase() === 'expires') return Date.parse(v) <= Date.now();
        return false;
      });
      if (expired || value === '') jar.delete(name);
      else jar.set(name, value);
    }
    this.#byHost.set(host, jar);
  }

  get(url, name) {
    return this.#byHost.get(new URL(url).host)?.get(name) ?? null;
  }

  header(url) {
    const jar = this.#byHost.get(new URL(url).host);
    return jar && jar.size ? [...jar].map(([k, v]) => `${k}=${v}`).join('; ') : null;
  }
}

/**
 * TOTP (RFC 6238) come lo calcola Keycloak: chiave = byte UTF-8 del segreto mostrato nella pagina di configurazione
 * (`totpSecret`), HMAC-SHA1, 6 cifre, periodo 30 s (politica OTP predefinita: il realm non la cambia).
 */
export function totp(secret, timeMs, { digits = 6, period = 30, algorithm = 'sha1' } = {}) {
  const counter = Math.floor(timeMs / 1000 / period);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const mac = crypto.createHmac(algorithm, Buffer.isBuffer(secret) ? secret : Buffer.from(String(secret), 'utf8')).update(msg).digest();
  const off = mac[mac.length - 1] & 0x0f;
  const bin = ((mac[off] & 0x7f) << 24) | (mac[off + 1] << 16) | (mac[off + 2] << 8) | mac[off + 3];
  return String(bin % 10 ** digits).padStart(digits, '0');
}

/** Tessere di HUB-02 (`data-testid="tile-<chiave>"` e `data-state`), anche dalla parte trasmessa in streaming. */
export function hubTiles(html) {
  const out = {};
  for (const m of String(html).matchAll(/<li\b[^>]*>/g)) {
    const a = parseAttrs(m[0]);
    if (a['data-testid']?.startsWith('tile-')) out[a['data-testid'].slice(5)] = a['data-state'] ?? '';
  }
  return out;
}

const DENY_ACTORS = new Set(['', 'demo', 'system', 'anonymous', 'unknown', '-']);

/** Attore reale di una voce di audit (stesso criterio di scripts/vetrina-programma.mjs, regola 21). */
export function isRealActor(record) {
  const name = String(record.actorName ?? '').trim().toLowerCase();
  const role = String(record.actorRole ?? '').trim().toLowerCase();
  return !DENY_ACTORS.has(name) && role !== 'system' && !name.startsWith('member:') && !name.startsWith('src-');
}

// ---------------------------------------------------------------------------------------------------------------
// HTTP

const isRedirect = (s) => s >= 300 && s < 400;
/** URL senza query né frammento: il codice di autorizzazione, lo state e i parametri non vanno nei log. */
export const redact = (url) => {
  const u = new URL(url);
  return `${u.origin}${u.pathname}`;
};
const parseJson = (text) => {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};
const problemCode = (res) => {
  const body = parseJson(res.text);
  const code = body && typeof body === 'object' ? (body.code ?? body.error ?? body.type) : null;
  return typeof code === 'string' && /^[A-Za-z0-9_.:-]{1,64}$/.test(code) ? ` ${code}` : '';
};

async function http(ctx, url, { method = 'GET', headers = {}, body, jar } = {}) {
  const h = new Headers(headers);
  const cookie = jar?.header(url);
  if (cookie) h.set('cookie', cookie);
  let lastErr;
  // Solo le GET si ripetono su errore di rete (mai una scrittura).
  const attempts = method === 'GET' ? ctx.retries + 1 : 1;
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (attempt > 0) await ctx.sleep(1000 * attempt);
    try {
      const res = await ctx.fetch(url, { method, headers: h, body, redirect: 'manual', signal: AbortSignal.timeout(ctx.timeoutMs) });
      if (jar) jar.store(url, typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : []);
      return { status: res.status, headers: res.headers, text: await res.text(), url: String(url) };
    } catch (e) {
      lastErr = e;
    }
  }
  const why = lastErr?.cause?.code ?? lastErr?.code ?? lastErr?.name ?? 'errore';
  throw new SmokeError(`${method} ${redact(url)}: nessuna risposta (${why})`);
}

function expect(cond, message) {
  if (!cond) throw new SmokeError(message);
}

async function eventually(ctx, seconds, fn) {
  const deadline = ctx.now() + seconds * 1000;
  for (;;) {
    try {
      return await fn();
    } catch (e) {
      if (!(e instanceof SmokeError) || ctx.now() >= deadline) throw e;
      await ctx.sleep(Math.min(5000, Math.max(0, deadline - ctx.now())));
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Controlli senza credenziali (entrambi i modi)

const EXPECTED_TILES = { hub: 'UP', web: 'UP', idp: 'UP', cms: 'NOT_INSTALLED', db: 'UP', kafka: 'UP' };

/** Restituisce `{ idp, issuer }` ricavati dal login avviato dal BFF. */
export async function checkPublic(ctx, opts) {
  const { web } = opts;
  const https = web.startsWith('https:');

  // Health del web: lo stesso controllo dell'HEALTHCHECK dell'immagine (deploy/image/healthcheck.sh).
  // SPEC-GAP: Q-650 — la vetrina non pubblica /actuator/health dell'hub né la porta di gestione di Keycloak: la health
  // di hub, Postgres, Kafka e IdP si legge dalle tessere di HUB-02 e dalla discovery, senza endpoint pubblici nuovi.
  await eventually(ctx, opts.settle, async () => {
    const res = await http(ctx, `${web}/api/demo/status`);
    expect(res.status === 200 && typeof parseJson(res.text)?.checkedAt === 'string', `web: /api/demo/status HTTP ${res.status}`);
  });
  ctx.ok('web: /api/demo/status 200 (health del ruolo web)');

  // HUB-02: pagina iniziale del profilo enterprise, con banner, portale chiuso (Q-619) e tessere attive.
  await eventually(ctx, opts.settle, async () => {
    const res = await http(ctx, `${web}/`);
    expect(res.status === 200, `HUB-02: HTTP ${res.status}`);
    expect(/<h1\b/.test(res.text), 'HUB-02: manca il titolo H1');
    expect(res.text.includes('data-testid="showcase-banner"'), 'HUB-02: manca il banner della vetrina (profilo diverso da enterprise?)');
    expect(res.text.includes('data-testid="portal-closed"'), 'HUB-02: manca il riquadro del portale chiuso (Q-619)');
    if (https) expect(/max-age=\d+/.test(res.headers.get('strict-transport-security') ?? ''), 'HUB-02: manca Strict-Transport-Security dal proxy');
    const tiles = hubTiles(res.text);
    const wrong = Object.entries(EXPECTED_TILES).filter(([k, v]) => tiles[k] !== v);
    expect(!wrong.length, `HUB-02: tessere non attive: ${wrong.map(([k]) => `${k}=${tiles[k] ?? 'assente'}`).join(', ')}`);
  });
  ctx.ok('HUB-02: banner, portale chiuso e tessere hub, web, idp, db e kafka attive (cms non installato)');

  // Login avviato dal BFF: redirect verso l'endpoint di autorizzazione del realm, client `web`, PKCE S256.
  const jar = new CookieJar();
  const start = await http(ctx, `${web}/api/auth/login`, { jar });
  expect(isRedirect(start.status) && start.headers.get('location'), `login: /api/auth/login HTTP ${start.status}, atteso un redirect`);
  const auth = new URL(start.headers.get('location'), web);
  const idp = validateIdp(auth.origin, opts);
  const issuer = `${idp}/realms/${REALM}`;
  const p = auth.searchParams;
  expect(auth.pathname === `/realms/${REALM}/protocol/openid-connect/auth`, `login: redirect verso ${auth.pathname}, atteso l'endpoint di autorizzazione del realm ${REALM}`);
  expect(p.get('client_id') === 'web', 'login: client_id diverso da web');
  expect(p.get('response_type') === 'code', 'login: response_type diverso da code');
  expect(p.get('code_challenge_method') === 'S256' && p.get('code_challenge'), 'login: manca PKCE S256');
  expect(p.get('state') && p.get('nonce'), 'login: mancano state o nonce');
  expect((p.get('scope') ?? '').split(' ').includes('openid'), 'login: scope senza openid');
  expect(p.get('redirect_uri') === `${web}/api/auth/callback`, 'login: redirect_uri diversa da <web>/api/auth/callback');
  const flowCookie = (start.headers.getSetCookie?.() ?? []).find((c) => c.startsWith(`${AUTH_FLOW_COOKIE}=`)) ?? '';
  expect(flowCookie && /;\s*httponly/i.test(flowCookie) && /;\s*secure/i.test(flowCookie), `login: cookie ${AUTH_FLOW_COOKIE} assente o senza HttpOnly e Secure`);
  ctx.ok(`login: il BFF avvia Authorization Code con PKCE S256 verso il realm ${REALM}`);

  // Discovery OIDC e chiavi pubbliche.
  const disc = await http(ctx, `${issuer}/.well-known/openid-configuration`);
  const doc = parseJson(disc.text);
  expect(disc.status === 200 && doc, `discovery: HTTP ${disc.status}`);
  expect(doc.issuer === issuer, 'discovery: issuer diverso dall\'URL pubblico del realm');
  expect(doc.authorization_endpoint === `${issuer}/protocol/openid-connect/auth`, 'discovery: authorization_endpoint diverso da quello usato dal BFF');
  for (const k of ['token_endpoint', 'jwks_uri', 'end_session_endpoint']) {
    expect(typeof doc[k] === 'string' && doc[k].startsWith(`${issuer}/`), `discovery: ${k} fuori dal realm o assente`);
  }
  expect((doc.code_challenge_methods_supported ?? []).includes('S256'), 'discovery: S256 non supportato');
  const jwks = await http(ctx, doc.jwks_uri);
  const keys = parseJson(jwks.text)?.keys;
  expect(jwks.status === 200 && Array.isArray(keys) && keys.some((k) => k.use === 'sig'), `JWKS: HTTP ${jwks.status} o nessuna chiave di firma`);
  expect(keys.every((k) => !('d' in k) && !('p' in k) && !('k' in k)), 'JWKS: contiene materiale di chiave privata o simmetrica');
  ctx.ok(`discovery OIDC del realm ${REALM} e JWKS (solo chiavi pubbliche)`);

  // Pagina di login (stessa sessione del redirect): Keycloak accetta la redirect_uri del BFF.
  const page = await http(ctx, auth.href, { jar });
  expect(page.status === 200, `pagina di login: HTTP ${page.status}${pageFeedback(page.text) ? ` (${pageFeedback(page.text)})` : ''}`);
  const form = parseForms(page.text).find((f) => f.method === 'post' && f.inputs.some((i) => i.name === 'username'));
  expect(form, 'pagina di login: manca il modulo con nome utente e password');
  const registration = page.text.includes('login-actions/registration');
  if (opts.registration === 'closed') expect(!registration, 'pagina di login: la registrazione è aperta (Q-619 la vuole chiusa)');
  if (opts.registration === 'open') expect(registration, 'pagina di login: manca la registrazione');
  ctx.ok(`pagina di login del realm raggiungibile${opts.registration === 'closed' ? ', registrazione chiusa (Q-619)' : ''}`);

  // Superfici che il proxy non espone (docs/18 §3.15 punto 2): console e realm master.
  for (const path of ['/admin/', '/realms/master/.well-known/openid-configuration']) {
    const res = await http(ctx, `${idp}${path}`);
    expect(res.status === 404, `IdP: ${path} risponde ${res.status}, atteso 404 (non esposto dal proxy)`);
  }
  ctx.ok('IdP: console /admin e realm master non esposti (404)');

  // API del backoffice dal BFF senza sessione.
  const anon = await http(ctx, `${web}/api/lh/reward/v1/reward-categories`);
  expect(anon.status === 401 && problemCode(anon) === ' UNAUTHENTICATED', `BFF senza sessione: HTTP ${anon.status}${problemCode(anon)}, atteso 401 UNAUTHENTICATED`);
  ctx.ok('BFF: API del backoffice senza sessione → 401 UNAUTHENTICATED');

  await checkMemberRealm(ctx, opts, idp);
  return { idp, issuer };
}

/** Discovery OIDC di un realm: emittente, endpoint dentro il realm, S256 e JWKS con sole chiavi pubbliche. */
async function checkDiscovery(ctx, issuer, realm) {
  const disc = await http(ctx, `${issuer}/.well-known/openid-configuration`);
  const doc = parseJson(disc.text);
  expect(disc.status === 200 && doc, `discovery del realm ${realm}: HTTP ${disc.status}`);
  expect(doc.issuer === issuer, `discovery del realm ${realm}: issuer diverso dall'URL pubblico del realm`);
  expect(doc.authorization_endpoint === `${issuer}/protocol/openid-connect/auth`, `discovery del realm ${realm}: authorization_endpoint diverso da quello usato dal BFF`);
  for (const k of ['token_endpoint', 'jwks_uri', 'end_session_endpoint']) {
    expect(typeof doc[k] === 'string' && doc[k].startsWith(`${issuer}/`), `discovery del realm ${realm}: ${k} fuori dal realm o assente`);
  }
  expect((doc.code_challenge_methods_supported ?? []).includes('S256'), `discovery del realm ${realm}: S256 non supportato`);
  const jwks = await http(ctx, doc.jwks_uri);
  const keys = parseJson(jwks.text)?.keys;
  expect(jwks.status === 200 && Array.isArray(keys) && keys.some((k) => k.use === 'sig'), `JWKS del realm ${realm}: HTTP ${jwks.status} o nessuna chiave di firma`);
  expect(keys.every((k) => !('d' in k) && !('p' in k) && !('k' in k)), `JWKS del realm ${realm}: contiene materiale di chiave privata o simmetrica`);
}

/**
 * Realm dei membri (ADR-051): il BFF avvia il login dei membri nel loro realm, con il client `portal`, e il realm
 * risponde con discovery, JWKS e pagina di login. Solo GET, nessuna credenziale.
 */
async function checkMemberRealm(ctx, opts, idp) {
  const { web } = opts;
  const issuer = `${idp}/realms/${MEMBER_REALM}`;
  const jar = new CookieJar();
  const start = await http(ctx, `${web}/api/auth/login?realm=members`, { jar });
  expect(start.status === 303 && start.headers.get('location'), `login dei membri: /api/auth/login?realm=members HTTP ${start.status}, atteso 303`);
  const auth = new URL(start.headers.get('location'), web);
  const p = auth.searchParams;
  expect(auth.origin === idp, 'login dei membri: redirect verso un IdP diverso da quello degli operatori');
  expect(auth.pathname === `/realms/${MEMBER_REALM}/protocol/openid-connect/auth`, `login dei membri: redirect verso ${auth.pathname}, atteso l'endpoint di autorizzazione del realm ${MEMBER_REALM}`);
  expect(p.get('client_id') === 'portal', 'login dei membri: client_id diverso da portal');
  expect(p.get('response_type') === 'code', 'login dei membri: response_type diverso da code');
  expect(p.get('code_challenge_method') === 'S256' && p.get('code_challenge'), 'login dei membri: manca PKCE S256');
  expect(p.get('state') && p.get('nonce'), 'login dei membri: mancano state o nonce');
  expect(p.get('redirect_uri') === `${web}/api/auth/callback/members`, 'login dei membri: redirect_uri diversa da <web>/api/auth/callback/members');
  ctx.ok(`login dei membri: il BFF risponde 303 verso il realm ${MEMBER_REALM}, client portal, PKCE S256`);

  await checkDiscovery(ctx, issuer, MEMBER_REALM);
  ctx.ok(`discovery OIDC del realm ${MEMBER_REALM} e JWKS (solo chiavi pubbliche)`);

  const page = await http(ctx, auth.href, { jar });
  expect(page.status === 200, `pagina di login dei membri: HTTP ${page.status}${pageFeedback(page.text) ? ` (${pageFeedback(page.text)})` : ''}`);
  expect(parseForms(page.text).some((f) => f.method === 'post' && f.inputs.some((i) => i.name === 'username')), 'pagina di login dei membri: manca il modulo');
  // Registrazione aperta nel realm dei membri (realm-members.json). Nella vetrina non si verifica: la chiude l'overlay
  // di vetrina dei due realm (ADR-051 decisione 2, fetta V8).
  if (opts.mode === 'compose') expect(page.text.includes('login-actions/registration'), 'pagina di login dei membri: manca la registrazione');
  ctx.ok(`pagina di login del realm ${MEMBER_REALM} raggiungibile${opts.mode === 'compose' ? ', con la registrazione dei membri' : ''}`);
}

function validateIdp(origin, opts) {
  try {
    return validateOrigin(origin, { allowHttp: opts.allowHttp, label: 'IdP' });
  } catch (e) {
    throw new SmokeError(`login: l'IdP del redirect non è un'origine ammessa (${e.message})`);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Login OIDC reale attraverso il BFF (solo modo compose)

const STEP_LABEL = {
  login: 'nome utente e password',
  password: 'cambio della password temporanea (UPDATE_PASSWORD)',
  'totp-setup': 'configurazione dell\'OTP (CONFIGURE_TOTP)',
  otp: 'codice OTP',
};

async function freshTotp(ctx, seed, lastCounter) {
  for (let i = 0; i < 4; i++) {
    const nowS = ctx.now() / 1000;
    const counter = Math.floor(nowS / 30);
    const left = 30 - (nowS % 30);
    // Un codice non si riusa (Keycloak lo rifiuta) e non si invia a ridosso della scadenza.
    if (counter > lastCounter && left > 3) return { code: totp(seed, ctx.now()), counter };
    await ctx.sleep(Math.ceil(left * 1000) + 250);
  }
  throw new SmokeError('OTP: orologio fermo');
}

/**
 * Login come lo fa un browser: /api/auth/login del BFF → pagine di Keycloak (credenziali, eventuali azioni richieste
 * e OTP) → /api/auth/callback del BFF. Restituisce il cookie jar con la sessione e i passi attraversati.
 */
export async function oidcLogin(ctx, { web, username, password }) {
  const jar = new CookieJar();
  const start = await http(ctx, `${web}/api/auth/login?returnTo=${encodeURIComponent('/backoffice')}`, { jar });
  expect(isRedirect(start.status), `login di ${username}: /api/auth/login HTTP ${start.status}`);
  let next = { method: 'GET', url: new URL(start.headers.get('location'), web).href };
  const steps = [];
  const seen = new Map();
  let seed = null;
  let lastCounter = -1;
  for (let i = 0; i < 15; i++) {
    const res = await http(ctx, next.url, { method: next.method, body: next.body, headers: next.headers, jar });
    if (isRedirect(res.status)) {
      const loc = new URL(res.headers.get('location'), next.url);
      if (loc.origin === web) {
        expect(loc.pathname === '/api/auth/callback', `login di ${username}: ritorno al web su ${loc.pathname}, atteso /api/auth/callback`);
        const cb = await http(ctx, loc.href, { jar });
        expect(isRedirect(cb.status), `login di ${username}: callback del BFF HTTP ${cb.status}${problemCode(cb)}`);
        const target = new URL(cb.headers.get('location'), web);
        expect(target.pathname !== '/auth/error', `login di ${username}: rifiutato dal BFF (${target.searchParams.get('reason') ?? 'motivo non indicato'})`);
        expect(jar.get(web, SESSION_COOKIE) && jar.get(web, CSRF_COOKIE), `login di ${username}: il BFF non ha aperto la sessione`);
        return { jar, steps };
      }
      next = { method: 'GET', url: loc.href };
      continue;
    }
    const form = parseForms(res.text).find((f) => f.method === 'post');
    const feedback = pageFeedback(res.text);
    if (!form) throw new SmokeError(`login di ${username}: pagina dell'IdP senza modulo (HTTP ${res.status}${feedback ? `, ${feedback}` : ''})`);
    const names = new Set(form.inputs.map((x) => x.name));
    const fields = Object.fromEntries(form.inputs.map((x) => [x.name, x.value]));
    let kind;
    // Prima le azioni richieste: la pagina del cambio password di Keycloak porta anche `username` e `password`
    // nascosti (per i gestori di password), quindi il modulo di login si riconosce per ultimo.
    if (names.has('password-new')) {
      kind = 'password';
      // Nuova password casuale, solo in memoria: l'utenza di prova non serve dopo il job.
      password = `${crypto.randomBytes(18).toString('base64url')}-Aa1`;
      fields['password-new'] = password;
      fields['password-confirm'] = password;
    } else if (names.has('totpSecret') && names.has('totp')) {
      kind = 'totp-setup';
      seed = fields.totpSecret;
      expect(seed, `login di ${username}: pagina di configurazione dell'OTP senza segreto`);
      const t = await freshTotp(ctx, seed, lastCounter);
      lastCounter = t.counter;
      fields.totp = t.code;
      if (!fields.userLabel) fields.userLabel = 'smoke-ci';
    } else if (names.has('otp')) {
      kind = 'otp';
      expect(seed, `login di ${username}: l'IdP chiede un OTP di un dispositivo che lo smoke non conosce`);
      const t = await freshTotp(ctx, seed, lastCounter);
      lastCounter = t.counter;
      fields.otp = t.code;
    } else if (names.has('username') && names.has('password')) {
      kind = 'login';
      fields.username = username;
      fields.password = password;
    } else {
      throw new SmokeError(`login di ${username}: pagina dell'IdP non riconosciuta (campi: ${[...names].join(', ') || 'nessuno'})`);
    }
    const count = (seen.get(kind) ?? 0) + 1;
    seen.set(kind, count);
    if (count > 1) throw new SmokeError(`login di ${username}: l'IdP ripropone il passo «${STEP_LABEL[kind]}»${feedback ? `: ${feedback}` : ''}`);
    steps.push(kind);
    ctx.log(`     ${username}: ${STEP_LABEL[kind]}`);
    next = {
      method: 'POST',
      url: new URL(form.action || res.url, res.url).href,
      body: new URLSearchParams(fields).toString(),
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
    };
  }
  throw new SmokeError(`login di ${username}: non concluso entro 15 passi`);
}

// ---------------------------------------------------------------------------------------------------------------
// Controlli con login (solo modo compose)

async function checkCompose(ctx, opts) {
  const { web, hub } = opts;

  const health = await http(ctx, `${hub}/actuator/health`);
  expect(health.status === 200 && parseJson(health.text)?.status === 'UP', `hub: /actuator/health HTTP ${health.status}`);
  const noToken = await http(ctx, `${hub}/v1/reward-categories`);
  expect(noToken.status === 401, `hub senza token: HTTP ${noToken.status}, atteso 401`);
  const badToken = await http(ctx, `${hub}/v1/reward-categories`, { headers: { authorization: 'Bearer non-un-token' } });
  expect(badToken.status === 401, `hub con un token non valido: HTTP ${badToken.status}, atteso 401`);
  ctx.ok('hub: health UP; API senza token o con un token non valido → 401');

  const persona = await http(ctx, `${web}/api/persona`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: web },
    body: JSON.stringify({ kind: 'BO', username: opts.operator }),
  });
  expect(persona.status === 404 && parseJson(persona.text)?.error === 'PERSONA_DISABLED', `POST /api/persona: HTTP ${persona.status}, atteso 404 PERSONA_DISABLED`);
  expect(!persona.headers.get('set-cookie'), 'POST /api/persona: imposta un cookie nel profilo enterprise');
  ctx.ok('POST /api/persona → 404 PERSONA_DISABLED (nessuna persona simulata)');

  // Operatore: login reale con la MFA del realm.
  const { jar, steps } = await oidcLogin(ctx, { web, username: opts.operator, password: readCredentials(opts.operatorCredentials, opts.operator) });
  expect(steps.includes('totp-setup') || steps.includes('otp'), `login di ${opts.operator}: concluso senza OTP, la MFA degli operatori non è scattata`);
  ctx.ok(`login OIDC reale di ${opts.operator} con il client web, PKCE e OTP (${steps.length} passi sull'IdP)`);

  const categories = `${web}/api/lh/reward/v1/reward-categories`;
  const list = await http(ctx, categories, { jar });
  expect(list.status === 200 && Array.isArray(parseJson(list.text)), `GET categorie con la sessione: HTTP ${list.status}${problemCode(list)}`);

  const code = `SMOKE${ctx.now().toString(36).toUpperCase()}`;
  const body = JSON.stringify({ code, name: 'Smoke enterprise', icon: 'sparkles', sortOrder: 99 });
  const noCsrf = await http(ctx, categories, { method: 'POST', jar, headers: { 'content-type': 'application/json', origin: web }, body });
  expect(noCsrf.status === 403 && problemCode(noCsrf) === ' CSRF_REJECTED', `scrittura senza X-LH-CSRF: HTTP ${noCsrf.status}${problemCode(noCsrf)}, atteso 403 CSRF_REJECTED`);
  ctx.ok('BFF: scrittura senza X-LH-CSRF → 403 CSRF_REJECTED');

  const since = new Date(ctx.now() - 60_000).toISOString();
  const created = await http(ctx, categories, {
    method: 'POST',
    jar,
    headers: { 'content-type': 'application/json', origin: web, 'x-lh-csrf': jar.get(web, CSRF_COOKIE) },
    body,
  });
  expect(created.status === 201, `scrittura di configurazione (POST categoria premi): HTTP ${created.status}${problemCode(created)}`);
  ctx.ok(`scrittura di configurazione dal backoffice: categoria premi ${code} creata (201)`);

  // Regola 21: voce di audit con l'attore reale, dal bus all'insight.
  const query = new URLSearchParams({ entityType: 'REWARD_CATEGORY', entityId: code, from: since });
  let last = 'nessuna voce';
  await eventually(ctx, opts.auditTimeout, async () => {
    const res = await http(ctx, `${web}/api/lh/insight/v1/audit?${query}`, { jar });
    expect(res.status === 200, `GET /v1/audit: HTTP ${res.status}${problemCode(res)}`);
    const items = parseJson(res.text)?.items ?? [];
    const mine = items.filter((r) => r.entityId === code && String(r.action ?? '').toUpperCase() === 'CREATE');
    if (mine.length) last = `attori: ${[...new Set(mine.map((r) => r.actorName ?? '?'))].join(', ')}`;
    expect(mine.some((r) => isRealActor(r) && r.actorName === opts.operator), `GET /v1/audit: nessuna voce CREATE di ${code} con attore ${opts.operator} (${last})`);
  });
  ctx.ok(`GET /v1/audit: voce CREATE di REWARD_CATEGORY ${code} con attore reale ${opts.operator}`);

  if (opts.member) {
    const member = await oidcLogin(ctx, { web, username: opts.member, password: readCredentials(opts.memberCredentials, opts.member) });
    const res = await http(ctx, categories, { jar: member.jar });
    expect(res.status === 403 && problemCode(res) === ' FORBIDDEN_ROLE', `membro sulle API del backoffice: HTTP ${res.status}${problemCode(res)}, atteso 403 FORBIDDEN_ROLE`);
    ctx.ok(`login OIDC reale del membro di prova ${opts.member}; API del backoffice → 403 FORBIDDEN_ROLE`);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Programma principale

/** @returns {Promise<number>} 0 superato, 1 controllo fallito, 2 uso non valido. */
export async function main(argv, deps = {}) {
  const out = deps.stdout ?? process.stdout;
  const err = deps.stderr ?? process.stderr;
  let passed = 0;
  const ctx = {
    fetch: deps.fetch ?? globalThis.fetch,
    sleep: deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms))),
    now: deps.now ?? (() => Date.now()),
    retries: deps.retries ?? 2,
    timeoutMs: deps.timeoutMs ?? 20_000,
    log: (m) => out.write(`${m}\n`),
    ok: (m) => {
      passed++;
      out.write(`ok   ${m}\n`);
    },
  };
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (e) {
    err.write(`${e.message}\n${HELP}\n`);
    return 2;
  }
  if (opts.help) {
    out.write(`${HELP}\n`);
    return 0;
  }
  try {
    ctx.log(`smoke enterprise (${opts.mode}) su ${opts.web}`);
    await checkPublic(ctx, opts);
    if (opts.mode === 'compose') await checkCompose(ctx, opts);
    ctx.log(`smoke enterprise (${opts.mode}) superato: ${passed} controlli`);
    return 0;
  } catch (e) {
    if (e instanceof UsageError) {
      err.write(`${e.message}\n`);
      return 2;
    }
    const msg = e instanceof SmokeError ? e.message : `errore inatteso (${e?.name ?? 'sconosciuto'})`;
    err.write(`FALLITO dopo ${passed} controlli: ${msg}\n`);
    if (process.env.GITHUB_ACTIONS === 'true') err.write(`::error title=Smoke enterprise (${opts.mode})::${msg}\n`);
    return 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
