// Test di scripts/vetrina-programma.mjs (F2-DIST-09, ADR-049, Q-617 decisa il 2026-09-30): node --test, con un hub
// finto in memoria. Nessuna rete. Copre: piano dal seed reale, idempotenza, esclusione dei dati dei membri, dry-run senza
// scritture, token mai stampato, rifiuto di http non locale, permessi del file del token, device flow, verifica
// dell'audit (regola 21) con uscita diversa da zero.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  assertAllowedRequest, criteriaAttributeKeys, isRealActor, main, makeLogger, parseArgs, readTokenFile, resolveSeedDate, tokenIdentity, UsageError, validateBaseUrl,
} from './vetrina-programma.mjs';

const SEED_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'seed');
const TOKEN = 'SENTINEL-SENTINEL-SENTINEL-0000';
const NOW = Date.parse('2026-09-30T12:00:00Z');
const BASE = 'https://hub.example.org';

const COLLECTIONS = {
  '/v1/event-types': ['event-types', 'event_type'],
  '/v1/sources': ['sources', 'source'],
  '/v1/message-templates': ['message-templates', 'MESSAGE_TEMPLATE'],
  '/v1/notification-rules': ['notification-rules', 'NOTIFICATION_RULE'],
  '/v1/reward-categories': ['reward-categories', 'REWARD_CATEGORY'],
  '/v1/reward-bands': ['reward-bands', 'REWARD_BAND'],
  '/v1/coupon-pools': ['coupon-pools', 'COUPON_POOL'],
  '/v1/rewards': ['rewards', 'REWARD'],
  '/v1/segments': ['segments', 'SEGMENT'],
  '/v1/badges': ['badges', 'BADGE'],
  '/v1/achievements': ['achievements', 'ACHIEVEMENT'],
  '/v1/leaderboards': ['leaderboards', 'LEADERBOARD'],
  '/v1/currencies': ['currencies', null],
  '/v1/tiers': ['tiers', null],
  '/v1/internal-mappings': ['internal-mappings', null],
};

/** JWT di prova (firma finta): lo script ne legge solo il payload per sapere chi è l'operatore. */
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const jwt = (payload) => `${b64({ alg: 'none', typ: 'JWT' })}.${b64(payload)}.c2lnbmF0dXJl`;

const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** Hub finto: GET elenco, POST creazione (409 se esiste), PUT tema e attributi, GET audit; ricorda ogni chiamata. */
function makeHub({ token = TOKEN, actor = 'operatore.prova', audit = 'ok', failOn = null, echoToken = false, preload = {}, pageCap = 100, hidden = {} } = {}) {
  const state = {
    calls: [], audit: [], attrs: [], theme: { programName: 'Club Aurora', tagline: 'Il programma fedeltà che premia ogni gesto', logoUrl: null,
      colors: { primary: '#1FB98F', secondary: '#7A5CFA', coin: '#FFB547', night: '#0E1B2C', bg: '#F3F7F9' }, heroTitle: 'Ogni gesto conta',
      heroSubtitle: 'Accumula punti, sali di livello, scegli il tuo premio.', fontDisplay: null, currencyNames: { PTS: 'punti', STS: 'punti status' }, version: 0, updatedAt: null },
    data: Object.fromEntries(Object.values(COLLECTIONS).map(([name]) => [name, []])),
  };
  for (const [name, rows] of Object.entries(preload)) state.data[name] = rows;
  const push = (entityType, entityId, action) => {
    state.audit.push({ id: `A${state.audit.length}`, at: new Date(NOW).toISOString(), entityType, entityId, action,
      actorName: audit === 'demo' ? 'demo' : actor, actorRole: audit === 'demo' ? 'SYSTEM' : 'ADMIN' });
  };
  /** Con audit:'none' il bus non consegna nulla; `push` permette al test di simulare il recupero. */
  const record = (...args) => { if (audit !== 'none') push(...args); };
  async function fetch(input, init = {}) {
    const url = new URL(input);
    const method = init.method ?? 'GET';
    const body = init.body ? JSON.parse(init.body) : undefined;
    state.calls.push({ method, url: String(input), path: url.pathname, auth: init.headers?.Authorization, body, redirect: init.redirect });
    if (init.headers?.Authorization !== `Bearer ${token}`) return json(401, { code: 'UNAUTHORIZED' });
    const p = url.pathname;
    if (failOn && method === failOn.method && p === failOn.path && (!failOn.code || body?.code === failOn.code)) {
      return json(422, { code: 'VALIDATION', detail: echoToken ? `rifiutato, header Authorization: Bearer ${token}` : 'non valido' });
    }
    if (p === '/v1/audit') {
      // come AuditController: filtri per uguaglianza esatta su entityType/entityId/actor, `from` su at, più recente per prima
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Math.min(Number(url.searchParams.get('size') ?? 100), pageCap);
      const q = (k) => url.searchParams.get(k);
      const all = state.audit.filter((r) => (!q('entityType') || r.entityType === q('entityType'))
        && (!q('entityId') || r.entityId === q('entityId')) && (!q('actor') || r.actorName === q('actor'))
        && (!q('from') || Date.parse(r.at) >= Date.parse(q('from')))).reverse();
      const items = all.slice(page * size, page * size + size);
      return json(200, { items, page: { number: page, size, totalItems: all.length, totalPages: Math.ceil(all.length / size) } });
    }
    if (p === '/v1/theme') {
      if (method === 'GET') return json(200, state.theme);
      state.theme = { ...body, updatedAt: new Date(NOW).toISOString(), version: 1 };
      record('THEME', 'default', 'UPDATE');
      return json(200, state.theme);
    }
    if (p === '/v1/attribute-definitions') {
      if (method === 'GET') return json(200, state.attrs);
      state.attrs = body;
      record('attribute_definition', 'all', 'UPDATE');
      return json(200, state.attrs);
    }
    const col = COLLECTIONS[p];
    if (!col) return json(404, { code: 'NOT_FOUND' });
    const [name, type] = col;
    if (method === 'GET') {
      const visible = state.data[name].filter((x) => !(hidden[name] ?? []).includes(x.code));
      if (p === '/v1/segments') {
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Math.min(Number(url.searchParams.get('size') ?? 100), pageCap);
        return json(200, { items: visible.slice(page * size, page * size + size), page: { number: page, size, totalItems: visible.length, totalPages: Math.ceil(visible.length / size) } });
      }
      return json(200, visible);
    }
    if (method !== 'POST' || !type) return json(405, { code: 'METHOD_NOT_ALLOWED' });
    if (state.data[name].some((x) => x.code === body.code)) return json(409, { code: 'CODE_TAKEN' });
    if (p === '/v1/rewards') {
      const has = (n, c) => state.data[n].some((x) => x.code === c);
      if (!has('reward-categories', body.category) || !has('reward-bands', body.band)) return json(422, { code: 'REWARD_INVALID' });
      if (body.couponPoolId && !state.data['coupon-pools'].some((x) => x.id === body.couponPoolId)) return json(422, { code: 'REWARD_INVALID' });
    }
    if (p === '/v1/sources' && (body.allowedTypes ?? []).some((t) => !state.data['event-types'].some((x) => x.code === t))) {
      return json(422, { code: 'SOURCE_INVALID' });
    }
    const row = { ...body, id: `ID-${body.code}` };
    state.data[name].push(row);
    record(type, body.code, 'CREATE');
    return json(201, row);
  }
  return { fetch, state, push, writes: () => state.calls.filter((c) => c.method !== 'GET') };
}

function tmpTokenFile(mode = 0o600, content = `${TOKEN}\n`) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lh-vetrina-'));
  const file = path.join(dir, 'token');
  fs.writeFileSync(file, content, { mode });
  fs.chmodSync(file, mode);
  return file;
}

async function run(args, { hub = makeHub(), env = {}, fetch, file = tmpTokenFile() } = {}) {
  let out = '';
  let err = '';
  const code = await main(args, {
    env,
    fetch: fetch ?? hub.fetch,
    stdout: { write: (s) => { out += s; } },
    stderr: { write: (s) => { err += s; } },
    sleep: async () => {},
    now: () => NOW,
  });
  return { code, out, err, hub, file };
}

const common = (file, extra = []) => ['--base-url', BASE, '--token-file', file, '--audit-timeout', '2', '--audit-interval', '1', ...extra];

test('dry-run predefinito: piano dal seed reale, zero scritture, uscita 0', async () => {
  const file = tmpTokenFile();
  const { code, out, hub } = await run(common(file), { file });
  assert.equal(code, 0);
  assert.deepEqual(hub.writes(), [], 'nessuna richiesta di scrittura in dry-run');
  assert.ok(hub.state.calls.length > 0 && hub.state.calls.every((c) => c.method === 'GET'));
  assert.match(out, /dry-run: nessuna scrittura/i);
  assert.match(out, /\[reward\] reward-categories: 5 da creare/);
  assert.match(out, /\[reward\] rewards: 14 da creare/);
  assert.match(out, /Q-617 decisa il 2026-09-30/);
});

test('--offline: nessuna richiesta di rete e nessun token', async () => {
  let called = 0;
  const { code, out } = await run(['--offline'], { fetch: async () => { called++; return json(500, {}); } });
  assert.equal(code, 0);
  assert.equal(called, 0);
  assert.match(out, /--offline/);
  assert.match(out, /74 da creare/);
});

test('--apply crea la configurazione; la seconda esecuzione non scrive nulla (idempotenza)', async () => {
  const hub = makeHub();
  const file = tmpTokenFile();
  const first = await run(common(file, ['--apply']), { hub, file });
  assert.equal(first.code, 0, first.err + first.out);
  const created = hub.writes().length;
  assert.ok(created >= 70, `scritture attese ≥ 70, trovate ${created}`);
  assert.match(first.out, /Audit \(regola 21\): \d+\/\d+ scritture con voce e attore reale/);

  const before = hub.writes().length;
  const second = await run(common(file, ['--apply']), { hub, file });
  assert.equal(second.code, 0, second.err + second.out);
  assert.equal(hub.writes().length, before, 'la seconda esecuzione non scrive');
  assert.match(second.out, /0 da creare/);
  assert.match(second.out, /nessuna scrittura da verificare/i);
});

test('esclude membri, movimenti, demo, transizioni e le entità con approvazione', async () => {
  const hub = makeHub();
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 0, r.err);
  const forbidden = /\/(members|wallets|ledger|lots|demo|transitions|campaigns|contests|contents|webhooks|editions|redemptions|coupons)(\/|$)/;
  for (const c of hub.state.calls) assert.doesNotMatch(c.path, forbidden, `${c.method} ${c.path}`);
  for (const c of hub.writes()) assert.doesNotMatch(JSON.stringify(c.body), /MBR-\d/, `${c.path} porta un membro`);
  // SEG-VIP-EVENT è un elenco di membri: non si crea; i 4 segmenti dinamici sì.
  const segs = hub.state.data.segments.map((s) => s.code).sort();
  assert.deepEqual(segs, ['SEG-AT-RISK', 'SEG-DIGITAL', 'SEG-NOT-EBILL', 'SEG-TORINO']);
  assert.ok(hub.state.data.segments.every((s) => s.type === 'DYNAMIC' && s.memberIds === undefined && s.expectedMembers === undefined));
  // i premi non portano stato (nascono DRAFT) e il pool non porta size/consumed
  for (const reward of hub.state.data.rewards) {
    assert.equal(reward.status, undefined);
    assert.equal(reward.stockRemaining, undefined);
  }
  for (const pool of hub.state.data['coupon-pools']) {
    assert.equal(pool.size, undefined);
    assert.equal(pool.consumed, undefined);
  }
  // il premio con pool punta all'id reale del pool creato
  const coffee = hub.state.data.rewards.find((x) => x.code === 'RWD-COFFEE-5');
  assert.equal(coffee.couponPoolId, 'ID-POOL-CAF');
  assert.equal(coffee.validFrom, '2026-04-03T00:00:00.000Z');
  // entità senza API di creazione: saltate e riportate
  assert.match(r.out, /\[wallet\] currencies: 0 da creare · 0 presenti · 2 saltati/);
  assert.match(r.out, /\[wallet\] tiers: 0 da creare/);
  assert.match(r.out, /nessuna API di creazione/);
  assert.match(r.out, /editions\.json/);
  assert.match(r.out, /webhooks\.json/);
});

test('fonti: create solo se i tipi azione ammessi esistono (mai allargare a tutti i tipi)', async () => {
  const types = JSON.parse(fs.readFileSync(path.join(SEED_DIR, 'event-types.json'), 'utf8')).map((t) => ({ code: t.code }));
  const hub = makeHub({ preload: { 'event-types': types } });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 0, r.err);
  const sources = hub.state.data.sources.map((s) => s.code).sort();
  assert.deepEqual(sources, ['app', 'billing', 'crm', 'ecommerce', 'partner']);
  assert.ok(hub.state.data.sources.every((s) => s.kind === 'HTTP' && s.allowedTypes.length > 0));
  // senza tipi (installazione vuota) nessuna fonte parte
  const empty = makeHub();
  await run(common(file, ['--apply']), { hub: empty, file });
  assert.equal(empty.state.data.sources.length, 0);
});

test('il tema non salvato uguale al seed non si scrive; uno già personalizzato non si tocca', async () => {
  const hub = makeHub();
  const file = tmpTokenFile();
  await run(common(file, ['--apply']), { hub, file });
  assert.equal(hub.writes().filter((c) => c.path === '/v1/theme').length, 0, 'il tema di ripiego coincide col seed');

  const custom = makeHub();
  custom.state.theme = { ...custom.state.theme, programName: 'Altro', updatedAt: '2026-09-01T00:00:00Z', version: 3 };
  const r = await run(common(file, ['--apply']), { hub: custom, file });
  assert.equal(custom.writes().filter((c) => c.path === '/v1/theme').length, 0);
  assert.match(r.out, /già personalizzato/);

  const unsaved = makeHub();
  unsaved.state.theme = { ...unsaved.state.theme, programName: 'Da seed?' };
  await run(common(file, ['--apply']), { hub: unsaved, file });
  assert.equal(unsaved.writes().filter((c) => c.path === '/v1/theme').length, 1);
});

test('attributi: si rimanda l\'esistente più i mancanti, una sola PUT', async () => {
  const hub = makeHub();
  hub.state.attrs = [{ key: 'city', label: 'Città di fornitura', type: 'STRING', options: [] }, { key: 'extra', label: 'Extra', type: 'STRING', options: [] }];
  const file = tmpTokenFile();
  await run(common(file, ['--apply']), { hub, file });
  const puts = hub.writes().filter((c) => c.path === '/v1/attribute-definitions');
  assert.equal(puts.length, 1);
  assert.deepEqual(hub.state.attrs.map((a) => a.key).sort(), ['city', 'extra', 'hasGasContract', 'householdSize', 'preferredChannel']);
});

test('il token non compare mai su stdout o stderr, nemmeno se il servizio lo ripete in un errore', async () => {
  const hub = makeHub({ echoToken: true, failOn: { method: 'POST', path: '/v1/reward-categories', code: 'CASA' } });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 1);
  assert.match(r.err, /reward-categories CASA/);
  assert.equal((r.out + r.err).includes(TOKEN), false, 'token in uscita');
  // e mai in un URL
  assert.ok(hub.state.calls.every((c) => !c.url.includes(TOKEN)));
  // tutte le richieste portano il Bearer e nessun redirect è seguito
  assert.ok(hub.state.calls.every((c) => c.auth === `Bearer ${TOKEN}` && c.redirect === 'manual'));
});

test('una scrittura fallita dà uscita 1 ma le altre proseguono; i dipendenti sono saltati', async () => {
  const hub = makeHub({ failOn: { method: 'POST', path: '/v1/reward-categories', code: 'TEMPO' } });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 1);
  assert.ok(hub.state.data.badges.length === 6, 'le altre entità proseguono');
  assert.ok(!hub.state.data.rewards.some((x) => x.category === 'TEMPO'), 'i premi della categoria mancante non si creano');
  assert.ok(hub.state.data.rewards.some((x) => x.category === 'CASA'));
  assert.match(r.out, /saltate per dipendenza/);
});

test('audit mancante dopo --apply: uscita diversa da zero', async () => {
  const hub = makeHub({ audit: 'none' });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 1);
  assert.match(r.out, /Audit \(regola 21\): 0\/\d+/);
  assert.match(r.err, /voce di audit mancante/);
});

test('audit con attore demo o system non vale come attore reale', async () => {
  const hub = makeHub({ audit: 'demo' });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 1);
  assert.match(r.err, /attore non reale/);
  assert.equal(isRealActor({ actorName: 'demo', actorRole: 'ADMIN' }), false);
  assert.equal(isRealActor({ actorName: 'system', actorRole: 'ADMIN' }), false);
  assert.equal(isRealActor({ actorName: 'anonymous', actorRole: 'ADMIN' }), false);
  assert.equal(isRealActor({ actorName: 'giuseppe.operatore', actorRole: 'ADMIN' }), true);
});

test('http non locale rifiutato (anche con --allow-http); http locale solo con --allow-http', async () => {
  const file = tmpTokenFile();
  let called = 0;
  const fetch = async () => { called++; return json(200, []); };
  const remote = await run(['--base-url', 'http://hub.example.org', '--token-file', file, '--allow-http'], { fetch });
  assert.equal(remote.code, 2);
  assert.match(remote.err, /solo verso localhost/);
  const noFlag = await run(['--base-url', 'http://127.0.0.1:8080', '--token-file', file], { fetch });
  assert.equal(noFlag.code, 2);
  assert.match(noFlag.err, /serve https/);
  assert.equal(called, 0, 'nessuna richiesta è partita');
  const local = await run(['--base-url', 'http://127.0.0.1:8080', '--token-file', file, '--allow-http'], { hub: makeHub() });
  assert.equal(local.code, 0, local.err);
  assert.throws(() => validateBaseUrl('https://user:pw@hub.example.org'), UsageError);
  assert.throws(() => validateBaseUrl('https://hub.example.org/?t=1'), UsageError);
  assert.throws(() => validateBaseUrl('ftp://hub.example.org'), UsageError);
  assert.equal(validateBaseUrl('https://hub.example.org/'), 'https://hub.example.org');
});

test('file del token: permessi larghi, collegamento e contenuto non valido sono rifiutati senza richieste', async () => {
  let called = 0;
  const fetch = async () => { called++; return json(200, []); };
  const wide = tmpTokenFile(0o644);
  const r1 = await run(['--base-url', BASE, '--token-file', wide], { fetch });
  assert.equal(r1.code, 2);
  assert.match(r1.err, /permessi troppo larghi/);
  assert.equal((r1.out + r1.err).includes(TOKEN), false);
  const good = tmpTokenFile();
  const link = `${good}.link`;
  fs.symlinkSync(good, link);
  assert.throws(() => readTokenFile(link), /file regolare/);
  assert.throws(() => readTokenFile(tmpTokenFile(0o600, 'due token\n')), /contenuto non valido/);
  assert.throws(() => readTokenFile(path.join(os.tmpdir(), 'lh-inesistente-xyz')), /non leggibile/);
  assert.equal(called, 0);
});

test('token solo da file o device flow: mai da argomento o ambiente', async () => {
  assert.throws(() => parseArgs(['--token', 'abc']), /cronologia/);
  assert.throws(() => parseArgs(['--token=abc']), /cronologia/);
  const r = await run(['--base-url', BASE], { env: { LH_OPERATOR_TOKEN: TOKEN } });
  assert.equal(r.code, 2);
  assert.match(r.err, /serve un token/);
  // la variabile con il percorso del file invece vale
  const file = tmpTokenFile();
  const ok = await run(['--base-url', BASE], { env: { LH_OPERATOR_TOKEN_FILE: file } });
  assert.equal(ok.code, 0, ok.err);
});

test('URL per servizio: --svc-url e LH_SVC_*_URL prevalgono sul base-url', async () => {
  const file = tmpTokenFile();
  const hub = makeHub();
  const r = await run(['--base-url', BASE, '--svc-url', 'insight=https://insight.example.org', '--token-file', file],
    { hub, env: { LH_SVC_WALLET_URL: 'https://wallet.example.org' }, file });
  assert.equal(r.code, 0, r.err);
  assert.ok(hub.state.calls.some((c) => c.url.startsWith('https://wallet.example.org/v1/currencies')));
  assert.ok(hub.state.calls.some((c) => c.url.startsWith('https://hub.example.org/v1/reward-categories')));
  const missing = await run(['--token-file', file]);
  assert.equal(missing.code, 2);
  assert.match(missing.err, /URL mancante/);
});

test('device flow: il token resta in memoria, il codice utente si mostra, il token no', async () => {
  const DEVICE_TOKEN = 'DEVICE-SENTINEL-SENTINEL-0000';
  const DEVICE_CODE = 'DEVCODE-SENTINEL-SENTINEL-0000';
  const hub = makeHub({ token: DEVICE_TOKEN });
  const idpCalls = [];
  let polls = 0;
  const idp = 'https://idp.example.org/realms/loyaltyhub';
  const fetch = async (url, init = {}) => {
    if (!String(url).startsWith('https://idp.example.org')) return hub.fetch(url, init);
    idpCalls.push({ url: String(url), method: init.method ?? 'GET', body: init.body });
    if (String(url).endsWith('/.well-known/openid-configuration')) {
      return json(200, { device_authorization_endpoint: `${idp}/protocol/openid-connect/auth/device`, token_endpoint: `${idp}/protocol/openid-connect/token` });
    }
    if (String(url).endsWith('/auth/device')) {
      return json(200, { device_code: DEVICE_CODE, user_code: 'ABCD-EFGH', verification_uri: `${idp}/device`, interval: 1, expires_in: 120 });
    }
    polls++;
    if (polls === 1) return json(400, { error: 'authorization_pending' });
    if (polls === 2) return json(400, { error: 'slow_down' });
    return json(200, { access_token: DEVICE_TOKEN, token_type: 'Bearer' });
  };
  const r = await run(['--base-url', BASE, '--issuer', idp, '--client-id', 'lh-cli'], { fetch });
  assert.equal(r.code, 0, r.err);
  assert.match(r.err, /ABCD-EFGH/);
  assert.match(idpCalls.find((c) => c.url.endsWith('/auth/device')).body, /client_id=lh-cli/);
  assert.equal(polls, 3);
  const all = r.out + r.err;
  assert.equal(all.includes(DEVICE_TOKEN), false);
  assert.equal(all.includes(DEVICE_CODE), false);
  assert.ok(hub.state.calls.length > 0 && hub.state.calls.every((c) => c.auth === `Bearer ${DEVICE_TOKEN}`));
});

test('device flow: endpoint di un\'altra origine o http remoto sono rifiutati', async () => {
  const idp = 'https://idp.example.org/realms/loyaltyhub';
  const seen = [];
  const fetch = async (url) => {
    seen.push(String(url));
    return json(200, { device_authorization_endpoint: 'https://evil.example.net/device', token_endpoint: `${idp}/token` });
  };
  const r = await run(['--base-url', BASE, '--issuer', idp], { fetch });
  assert.equal(r.code, 2);
  assert.match(r.err, /origine diversa/);
  assert.ok(seen.every((u) => u.startsWith('https://idp.example.org')));
  const http = await run(['--base-url', BASE, '--issuer', 'http://idp.example.org/realms/x', '--allow-http'], { fetch });
  assert.equal(http.code, 2);
});

test('richieste non ammesse: solo le risorse di configurazione, mai membri, demo o transizioni', () => {
  assert.doesNotThrow(() => assertAllowedRequest('POST', '/v1/rewards', { code: 'RWD-X' }));
  assert.doesNotThrow(() => assertAllowedRequest('PUT', '/v1/theme', {}));
  for (const [m, p] of [['POST', '/v1/members'], ['GET', '/v1/wallets/MBR-000001'], ['POST', '/v1/demo/reset'], ['POST', '/v1/rewards/x/transitions'],
    ['POST', '/v1/campaigns'], ['DELETE', '/v1/reward-bands/F1'], ['PUT', '/v1/tiers/GOLD'], ['POST', '/v1/events']]) {
    assert.throws(() => assertAllowedRequest(m, p), /non ammessa/, `${m} ${p}`);
  }
  assert.throws(() => assertAllowedRequest('POST', '/v1/segments', { memberIds: ['MBR-000004'] }), /membro/);
});

test('date del seed e opzioni', () => {
  assert.equal(resolveSeedDate('@today-10d', NOW), '2026-09-20T00:00:00.000Z');
  assert.equal(resolveSeedDate('@today', NOW), '2026-09-30T00:00:00.000Z');
  assert.equal(resolveSeedDate('@today+2d', NOW), '2026-10-02T00:00:00.000Z');
  assert.equal(resolveSeedDate('@now', NOW), '2026-09-30T12:00:00.000Z');
  assert.equal(resolveSeedDate(null, NOW), null);
  assert.throws(() => resolveSeedDate('@today+2M', NOW), UsageError);
  assert.throws(() => parseArgs(['--apply', '--dry-run']), /si escludono/);
  assert.throws(() => parseArgs(['--apply', '--offline']), /non si combina/);
  assert.throws(() => parseArgs(['--boh']), /sconosciuta/);
  assert.throws(() => parseArgs(['--svc-url', 'nope=https://x']), /formato/);
  assert.equal(parseArgs(['--base-url=https://h.example.org']).baseUrl, 'https://h.example.org');
});

test('il logger toglie i segreti noti e qualunque JWT', () => {
  let out = '';
  const log = makeLogger({ write: (s) => { out += s; } }, { write: () => {} });
  log.secret('SENTINEL-secret-0000');
  log.out('x SENTINEL-secret-0000 y Authorization: Bearer abc.def.ghi e ' + ['eyJhbGciOiJIUzI1NiJ9', 'eyJzdWIiOiIxIn0', 'c2ln'].join('.'));
  assert.equal(out.includes('SENTINEL-secret-0000'), false);
  assert.equal(out.includes('eyJ'), false);
  assert.equal(/Bearer abc/.test(out), false);
});

test('regola 21: l\'attore deve essere l\'operatore del token; quello di un altro operatore non vale', async () => {
  const token = jwt({ preferred_username: 'giuseppe.operatore', sub: 'uuid-1' });
  assert.equal(tokenIdentity(token), 'giuseppe.operatore');
  assert.equal(tokenIdentity(jwt({ azp: 'lh-cli', sub: 'x' })), 'lh-cli');
  assert.equal(tokenIdentity(jwt({ sub: 'solo-sub' })), 'solo-sub');
  assert.equal(tokenIdentity(TOKEN), null, 'token opaco: nessuna identità');
  assert.equal(tokenIdentity('a.!!!.c'), null);
  const file = tmpTokenFile(0o600, `${token}\n`);

  const same = makeHub({ token, actor: 'giuseppe.operatore' });
  const ok = await run(common(file, ['--apply']), { hub: same, file });
  assert.equal(ok.code, 0, ok.err);
  assert.match(ok.out, /attore = operatore del token/);
  assert.equal((ok.out + ok.err).includes(token), false, 'il JWT non si stampa');

  const other = makeHub({ token, actor: 'altro.operatore' });
  const ko = await run(common(file, ['--apply']), { hub: other, file });
  assert.equal(ko.code, 1);
  assert.match(ko.err, /voce di audit di un altro attore/);
  assert.match(ko.out, /Audit \(regola 21\): 0\//);

  // token opaco: si accetta ogni attore reale e lo si dichiara
  const opaque = await run(common(tmpTokenFile()), { hub: makeHub() });
  assert.equal(opaque.code, 0);
  const opaqueApply = await run(common(tmpTokenFile(), ['--apply']), { hub: makeHub() });
  assert.match(opaqueApply.out, /opaco/);
});

test('l\'audit si legge per tipo di entità (filtro entityType) e attraverso più pagine', async () => {
  const hub = makeHub({ pageCap: 5 });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 0, r.err + r.out);
  const auditCalls = hub.state.calls.filter((c) => c.path === '/v1/audit');
  assert.ok(auditCalls.length > 0 && auditCalls.every((c) => new URL(c.url).searchParams.has('entityType')), 'ogni lettura filtra per entityType');
  const rewardPages = auditCalls.filter((c) => new URL(c.url).searchParams.get('entityType') === 'REWARD').map((c) => c.url);
  assert.ok(rewardPages.length >= 3, `14 premi con pagine da 5 ⇒ almeno 3 pagine, trovate ${rewardPages.length}`);
  assert.match(r.out, /Audit \(regola 21\): (\d+)\/\1 scritture/);
});

test('segmenti su più pagine: la seconda esecuzione non duplica né scrive', async () => {
  const hub = makeHub({ pageCap: 2 });
  const file = tmpTokenFile();
  assert.equal((await run(common(file, ['--apply']), { hub, file })).code, 0);
  const before = hub.writes().length;
  const second = await run(common(file, ['--apply']), { hub, file });
  assert.equal(second.code, 0, second.err);
  assert.equal(hub.writes().length, before);
  assert.equal(hub.state.data.segments.length, 4);
  assert.ok(hub.state.calls.filter((c) => c.path === '/v1/segments' && c.method === 'GET').length >= 3, 'letto a pagine da 2');
});

test('409 durante --apply: già presente, nessuna voce di audit attesa, uscita 0', async () => {
  // CASA esiste ma l'elenco non la mostra: la POST risponde 409 (creata nel frattempo da un\'altra esecuzione)
  const hub = makeHub({ preload: { 'reward-categories': [{ code: 'CASA', name: 'Casa' }] }, hidden: { 'reward-categories': ['CASA'] } });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 0, r.err + r.out);
  assert.match(r.out, /1 già presenti \(409\)/);
  assert.equal(hub.state.audit.some((a) => a.entityType === 'REWARD_CATEGORY' && a.entityId === 'CASA'), false, 'nessuna scrittura nostra, nessuna voce');
  assert.match(r.out, /Audit \(regola 21\): (\d+)\/\1 scritture/);
});

test('device flow: access_denied, expired_token e scadenza ⇒ uscita 2, senza token né richieste ai servizi', async () => {
  const idp = 'https://idp.example.org/realms/loyaltyhub';
  for (const error of ['access_denied', 'expired_token']) {
    const serviceCalls = [];
    const fetch = async (url) => {
      if (!String(url).startsWith('https://idp.example.org')) { serviceCalls.push(String(url)); return json(200, []); }
      if (String(url).endsWith('/.well-known/openid-configuration')) {
        return json(200, { device_authorization_endpoint: `${idp}/auth/device`, token_endpoint: `${idp}/token` });
      }
      if (String(url).endsWith('/auth/device')) return json(200, { device_code: 'DEVCODE-SENTINEL-0000', user_code: 'WXYZ-1234', verification_uri: `${idp}/device`, interval: 1, expires_in: 60 });
      return json(400, { error });
    };
    const r = await run(['--base-url', BASE, '--issuer', idp], { fetch });
    assert.equal(r.code, 2, `${error}: ${r.err}`);
    assert.match(r.err, new RegExp(`device flow non riuscito \\(${error}\\)`));
    assert.deepEqual(serviceCalls, []);
    assert.equal((r.out + r.err).includes('DEVCODE-SENTINEL-0000'), false);
  }
  // l'utente non conferma mai: si esce alla scadenza
  let polls = 0;
  const pending = async (url) => {
    if (String(url).endsWith('/.well-known/openid-configuration')) return json(200, { device_authorization_endpoint: `${idp}/auth/device`, token_endpoint: `${idp}/token` });
    if (String(url).endsWith('/auth/device')) return json(200, { device_code: 'DEVCODE-SENTINEL-0000', user_code: 'WXYZ-1234', verification_uri: `${idp}/device`, interval: 5, expires_in: 40 });
    polls++;
    return json(400, { error: 'authorization_pending' });
  };
  const timeout = await run(['--base-url', BASE, '--issuer', idp], { fetch: pending });
  assert.equal(timeout.code, 2);
  assert.match(timeout.err, /scaduto/);
  assert.ok(polls > 0 && polls < 20);
});

test('--verify-audit: dopo un --apply con audit mancante la seconda esecuzione lo dice e la verifica successiva lo prova', async () => {
  const hub = makeHub({ audit: 'none' });
  const file = tmpTokenFile();
  const first = await run(common(file, ['--apply']), { hub, file });
  assert.equal(first.code, 1);
  const started = /Inizio esecuzione: (\S+) \(per ripetere la sola verifica dell'audit: --verify-audit --since \1\)/.exec(first.out);
  assert.ok(started, 'l\'esecuzione stampa l\'istante da usare con --since');

  // la seconda esecuzione trova tutto presente, non scrive, esce 0 ma avverte che NON vale come verifica
  const second = await run(common(file, ['--apply']), { hub, file });
  assert.equal(second.code, 0);
  assert.match(second.out, /NON sostituisce la verifica/);
  assert.match(second.out, /--verify-audit --since/);

  // la verifica dedicata fallisce finché l'audit manca...
  const since = ['--verify-audit', '--since', started[1]];
  const stillMissing = await run(common(file, since), { hub, file });
  assert.equal(stillMissing.code, 1);
  assert.match(stillMissing.err, /voce di audit mancante/);
  const writesBefore = hub.writes().length;
  assert.equal(writesBefore, first.out.match(/Scritture: (\d+) create/)[1] * 1, 'nessuna scrittura oltre a quelle del primo --apply');

  // ...e riesce quando il bus recupera
  for (const [, [name, type]] of Object.entries(COLLECTIONS)) {
    if (type) for (const row of hub.state.data[name]) hub.push(type, row.code, 'CREATE');
  }
  hub.push('attribute_definition', 'all', 'UPDATE');
  const recovered = await run(common(file, since), { hub, file });
  assert.equal(recovered.code, 0, recovered.err + recovered.out);
  assert.match(recovered.out, /Audit \(regola 21\): (\d+)\/\1 scritture/);
  assert.equal(hub.writes().length, writesBefore, '--verify-audit non scrive');
});

test('--verify-audit: opzioni incompatibili e voci ancora da creare', async () => {
  assert.throws(() => parseArgs(['--verify-audit']), /--since/);
  assert.throws(() => parseArgs(['--since', '2026-09-30T00:00:00Z']), /solo con --verify-audit/);
  assert.throws(() => parseArgs(['--verify-audit', '--since', 'ieri']), /ISO 8601/);
  assert.throws(() => parseArgs(['--verify-audit', '--since', '2026-09-30T00:00:00Z', '--apply']), /non si combina/);
  assert.throws(() => parseArgs(['--verify-audit', '--since', '2026-09-30T00:00:00Z', '--offline']), /non si combina/);
  const file = tmpTokenFile();
  const hub = makeHub();
  const r = await run(common(file, ['--verify-audit', '--since', '2026-09-30T11:00:00Z']), { hub, file });
  assert.equal(r.code, 1);
  assert.match(r.err, /ancora da creare/);
  assert.deepEqual(hub.writes(), []);
});

test('segmenti con criteri su un attributo personalizzato: se la PUT degli attributi fallisce sono saltati per dipendenza', async () => {
  assert.deepEqual(criteriaAttributeKeys({ op: 'all', rules: [{ field: 'member.city', cmp: 'eq', value: 'x' }, { field: 'member.status', cmp: 'eq', value: 'ACTIVE' },
    { op: 'any', rules: [{ field: 'member.householdSize', cmp: 'gt', value: 1 }] }] }, [{ key: 'city' }, { key: 'householdSize' }]), ['city', 'householdSize']);
  assert.deepEqual(criteriaAttributeKeys(null, [{ key: 'city' }]), []);
  const hub = makeHub({ failOn: { method: 'PUT', path: '/v1/attribute-definitions' } });
  const file = tmpTokenFile();
  const r = await run(common(file, ['--apply']), { hub, file });
  assert.equal(r.code, 1);
  assert.ok(!hub.state.calls.some((c) => c.method === 'POST' && c.path === '/v1/segments' && c.body.code === 'SEG-TORINO'), 'SEG-TORINO non si tenta nemmeno');
  assert.deepEqual(hub.state.data.segments.map((x) => x.code).sort(), ['SEG-AT-RISK', 'SEG-DIGITAL', 'SEG-NOT-EBILL']);
  assert.match(r.out, /1 saltate per dipendenza/);
  // nel piano iniziale senza lettura dello stato degli attributi (errore di GET) il segmento è saltato con il motivo
  const noAttrs = makeHub();
  const base = noAttrs.fetch;
  const fetch = async (url, init) => (new URL(url).pathname === '/v1/attribute-definitions' && (init?.method ?? 'GET') === 'GET' ? json(500, { code: 'BOOM' }) : base(url, init));
  const dry = await run(common(file), { fetch });
  assert.equal(dry.code, 1);
  assert.match(dry.out, /dipendenza non disponibile: attribute-definitions:city/);
});

test('file del token: una cartella non vale e LH_BASE_URL è un\'alternativa a --base-url', async () => {
  assert.throws(() => readTokenFile(os.tmpdir()), /file regolare/);
  const file = tmpTokenFile();
  const r = await run(['--token-file', file], { env: { LH_BASE_URL: BASE }, file });
  assert.equal(r.code, 0, r.err);
  const cli = await run(['--token-file', file, '--base-url', 'https://altro.example.org'], { env: { LH_BASE_URL: BASE }, file });
  assert.equal(cli.code, 0);
  assert.ok(cli.hub.state.calls.every((c) => c.url.startsWith('https://altro.example.org')), '--base-url prevale su LH_BASE_URL');
});
