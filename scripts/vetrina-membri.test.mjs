// Test di scripts/vetrina-membri.mjs e del modo `vetrina --test-users` dello smoke (M8.14 V8, ADR-051, Q-673), contro un
// BFF e un Keycloak finti in Node (loopback, http con --allow-http). Le credenziali sono quelle fisse e pubbliche degli
// overlay di vetrina, lette da scripts/vetrina-utenti.mjs: nessun valore duplicato qui. Uso: node --test scripts/vetrina-membri.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import { main as membri, parseArgs, registrationBody } from './vetrina-membri.mjs';
import { checkTestUsers, createContext, main as smoke, totp } from './smoke-enterprise.mjs';
import { loadTestUsers, base32Encode } from './vetrina-utenti.mjs';

const users = loadTestUsers();

function listen(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => handler(req, res, body));
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}
const origin = (server) => `http://127.0.0.1:${server.address().port}`;
const cookiesOf = (req) => Object.fromEntries((req.headers.cookie ?? '').split(';').filter(Boolean).map((c) => {
  const i = c.indexOf('=');
  return [c.slice(0, i).trim(), c.slice(i + 1).trim()];
}));
const send = (res, status, body, headers = {}) => {
  const json = typeof body !== 'string';
  res.writeHead(status, { 'content-type': json ? 'application/json' : 'text/html', ...headers });
  res.end(json ? JSON.stringify(body) : body);
};
const redirectTo = (res, location, headers = {}) => send(res, 303, '', { location, ...headers });

function capture() {
  const chunks = [];
  return { write: (s) => chunks.push(String(s)), text: () => chunks.join('') };
}

/**
 * Istanza finta. Orologio condiviso (`clock`): `sleep` lo avanza senza attendere, il finto Keycloak controlla il TOTP con lo
 * stesso orologio e rifiuta un codice già usato dallo stesso utente (come Keycloak con otpPolicyCodeReusable=false).
 * `members` = membri dell'hub (id, email, nome, stato, sub del legame); `registrations` = esiti delle registrazioni.
 */
async function fakeInstance({ members = [], wrongPassword = null } = {}) {
  const clock = { t: Date.parse('2026-10-02T10:00:05Z') };
  const state = { members: [...members], sessions: new Map(), codes: new Map(), kc: new Map(), usedOtp: new Set(), registrations: [], audit: [], seen: [] };
  const servers = {};
  const realmUsers = { loyaltyhub: users.operators, 'loyaltyhub-members': users.members };
  servers.idp = await listen((req, res, body) => {
    const u = new URL(req.url, origin(servers.idp));
    const m = u.pathname.match(/^\/realms\/(loyaltyhub|loyaltyhub-members)\/(protocol\/openid-connect\/auth|login-actions\/authenticate)$/);
    if (!m) return send(res, 404, 'not found');
    const realm = m[1];
    const form = (kc, kind, error = '') => `${error ? `<span id="input-error">${error}</span>` : ''}<form action="${origin(servers.idp)}/realms/${realm}/login-actions/authenticate?kc=${kc}" method="post">${
      kind === 'login' ? '<input name="username"><input type="password" name="password">' : '<input name="otp">'}</form>`;
    if (m[2].startsWith('protocol')) {
      const kc = crypto.randomUUID();
      state.kc.set(kc, { realm, redirect: u.searchParams.get('redirect_uri'), state: u.searchParams.get('state'), step: 'login' });
      return send(res, 200, form(kc, 'login'));
    }
    const kc = u.searchParams.get('kc');
    const s = state.kc.get(kc);
    const f = new URLSearchParams(body);
    state.seen.push(`idp ${realm} ${s.step}`);
    if (s.step === 'login') {
      const user = realmUsers[realm][f.get('username')];
      const password = wrongPassword === f.get('username') ? 'sbagliata' : f.get('password');
      if (!user || user.password !== password) return send(res, 200, form(kc, 'login', 'Nome utente o password non validi.'));
      s.user = user.username;
      s.step = realm === 'loyaltyhub' ? 'otp' : 'done';
    } else if (s.step === 'otp') {
      const code = f.get('otp');
      const valid = [clock.t, clock.t - 30_000].some((t) => totp(users.totpSecret, t) === code) && !state.usedOtp.has(`${s.user}:${code}`);
      if (!valid) return send(res, 200, form(kc, 'otp', 'Codice OTP non valido.'));
      state.usedOtp.add(`${s.user}:${code}`);
      s.step = 'done';
    }
    if (s.step === 'otp') return send(res, 200, form(kc, 'otp'));
    const code = crypto.randomUUID();
    state.codes.set(code, { user: s.user, realm });
    return redirectTo(res, `${s.redirect}?state=${encodeURIComponent(s.state)}&code=${code}`);
  });
  servers.web = await listen((req, res, body) => {
    const web = origin(servers.web);
    const u = new URL(req.url, web);
    const c = cookiesOf(req);
    state.seen.push(`web ${req.method} ${u.pathname}`);
    const login = (realm, redirectPath) => {
      const kcRealm = realm === 'members' ? 'loyaltyhub-members' : 'loyaltyhub';
      const q = new URLSearchParams({ client_id: realm === 'members' ? 'portal' : 'web', response_type: 'code', redirect_uri: `${web}${redirectPath}`, state: crypto.randomUUID() });
      return redirectTo(res, `${origin(servers.idp)}/realms/${kcRealm}/protocol/openid-connect/auth?${q}`);
    };
    if (u.pathname === '/api/auth/login') return login(u.searchParams.get('realm') === 'members' ? 'members' : 'operators', u.searchParams.get('realm') === 'members' ? '/api/auth/callback/members' : '/api/auth/callback');
    const cb = u.pathname === '/api/auth/callback' ? 'operators' : u.pathname === '/api/auth/callback/members' ? 'members' : null;
    if (cb) {
      const grant = state.codes.get(u.searchParams.get('code'));
      if (!grant) return redirectTo(res, `${web}/auth/error?reason=rejected`);
      const id = crypto.randomUUID();
      const csrf = crypto.randomUUID();
      state.sessions.set(id, { user: grant.user, realm: cb, csrf });
      const names = cb === 'members' ? ['__Host-lh_msession', '__Host-lh_mcsrf'] : ['__Host-lh_session', '__Host-lh_csrf'];
      return redirectTo(res, `${web}${cb === 'members' ? '/portal' : '/backoffice'}`, { 'set-cookie': [`${names[0]}=${id}; Path=/; Secure; HttpOnly`, `${names[1]}=${csrf}; Path=/; Secure`] });
    }
    if (u.pathname.startsWith('/api/lh/')) {
      const portal = u.pathname.startsWith('/api/lh/member/v1/portal/');
      const session = state.sessions.get(portal ? c['__Host-lh_msession'] : c['__Host-lh_session']);
      if (!session) return send(res, 401, { code: 'UNAUTHENTICATED' });
      if (req.method !== 'GET' && (req.headers.origin !== web || req.headers['x-lh-csrf'] !== session.csrf)) return send(res, 403, { code: 'CSRF_REJECTED' });
      const sub = `sub-${session.user}`;
      if (u.pathname === '/api/lh/member/v1/portal/members' && req.method === 'POST') {
        const b = JSON.parse(body);
        const linked = state.members.find((x) => x.sub === sub);
        if (linked) return send(res, 200, linked);
        if (state.members.some((x) => x.email === b.email && x.status !== 'ANONYMIZED')) return send(res, 409, { code: 'EMAIL_TAKEN' });
        const member = { id: `MBR-${String(900001 + state.members.length)}`, sub, status: 'ACTIVE', ...b };
        state.members.push(member);
        state.registrations.push(b);
        return send(res, 201, member);
      }
      if (u.pathname === '/api/lh/member/v1/portal/me/profile') {
        const linked = state.members.find((x) => x.sub === sub);
        return linked ? send(res, 200, linked) : send(res, 404, { code: 'NOT_FOUND' });
      }
      if (u.pathname === '/api/lh/member/v1/members' && req.method === 'GET') {
        const q = (u.searchParams.get('q') ?? '').toLowerCase();
        // Ricerca larga, come una ricerca testuale: basta un termine; lo script filtra poi chi è davvero Laura.
        const items = state.members.filter((x) => q.split(/\s+/).some((tok) => `${x.email} ${x.firstName} ${x.lastName}`.toLowerCase().includes(tok)));
        return send(res, 200, { items, page: {} });
      }
      const an = u.pathname.match(/^\/api\/lh\/member\/v1\/members\/([^/]+)\/anonymize$/);
      if (an && req.method === 'POST') {
        if (!users.operators[session.user]?.realmRoles.includes('ADMIN')) return send(res, 403, { code: 'FORBIDDEN_ROLE' });
        const member = state.members.find((x) => x.id === an[1]);
        if (!member) return send(res, 404, { code: 'NOT_FOUND' });
        assert.equal(JSON.parse(body).confirm, member.id);
        Object.assign(member, { status: 'ANONYMIZED', firstName: null, lastName: null, email: null, sub: null });
        state.audit.push({ entityId: member.id, action: 'TRANSITION', actorName: session.user });
        return send(res, 200, member);
      }
      if (u.pathname === '/api/lh/reward/v1/reward-categories') return send(res, 200, []);
    }
    return send(res, 404, 'not found');
  });
  return {
    state,
    web: origin(servers.web),
    deps: { stdout: capture(), stderr: capture(), retries: 0, now: () => clock.t, sleep: async (ms) => { clock.t += ms; } },
    close: () => Promise.all(Object.values(servers).map((s) => new Promise((r) => s.close(r)))),
  };
}

const run = async (inst, extra = []) => {
  inst.deps.stdout = capture();
  inst.deps.stderr = capture();
  const code = await membri(['--allow-http', '--web', inst.web, '--settle', '0', ...extra], inst.deps);
  return { code, out: inst.deps.stdout.text(), err: inst.deps.stderr.text() };
};

const noSecrets = (text) => {
  const secrets = [...Object.values(users.operators), ...Object.values(users.members)].map((u) => u.password);
  secrets.push(users.totpSecret, base32Encode(Buffer.from(users.totpSecret, 'utf8')));
  for (const s of secrets) assert.ok(!text.includes(s), 'una credenziale è finita nell\'output');
};

test('registrationBody: profilo dal seed, senza id né memberId; parseArgs: https obbligatorio', () => {
  const body = registrationBody({ id: 'MBR-000002', externalId: 'CRM-102', firstName: 'Marco', lastName: 'Bianchi', nickname: 'marco_b', email: 'marco.bianchi@example.org', phone: '+39 333 000 0102', city: 'Milano', consents: { marketing: true, profiling: true }, tier: 'SILVER', points: 1850 });
  assert.deepEqual(Object.keys(body).sort(), ['city', 'consents', 'email', 'firstName', 'lastName', 'nickname', 'phone']);
  assert.ok(!JSON.stringify(body).includes('MBR-') && !JSON.stringify(body).includes('emberId'));
  assert.throws(() => parseArgs(['--web', 'http://web.example.org']), /serve https/);
  assert.throws(() => parseArgs([]), /manca --web/);
  assert.throws(() => parseArgs(['--web', 'https://w.example.org', '--password', 'x']), /sconosciuto/);
});

test('membri: primo avvio registra Anna, Marco e Giulia dal portale col profilo del seed; Laura resta senza profilo (Q-673)', async (t) => {
  const inst = await fakeInstance();
  t.after(inst.close);
  const r = await run(inst);
  assert.equal(r.code, 0, r.err);
  assert.deepEqual(inst.state.registrations.map((b) => `${b.firstName} ${b.lastName}`), ['Anna Rossi', 'Marco Bianchi', 'Giulia Ferri']);
  assert.equal(inst.state.registrations[1].city, 'Milano');
  assert.deepEqual(inst.state.registrations[0].consents, { marketing: true, profiling: false });
  assert.match(r.out, /Anna Rossi \(MBR-000001 del seed\): registrazione dal portale creato/);
  assert.match(r.out, /Laura Conti: nessun profilo attivo/);
  assert.equal(inst.state.audit.length, 0);
  assert.ok(!inst.state.members.some((m) => /laura/i.test(m.firstName ?? '')));
  // Solo login nel realm dei membri per i tre; l'operatore solo per cercare Laura (nessun password grant: mai un POST al token endpoint).
  assert.ok(!inst.state.seen.some((s) => /protocol\/openid-connect\/token/.test(s)));
  noSecrets(r.out + r.err);
});

test('membri: idempotente (200 dal secondo avvio, stesso membro) e anonimizza Laura se esiste, con l\'attore reale', async (t) => {
  const inst = await fakeInstance();
  t.after(inst.close);
  assert.equal((await run(inst)).code, 0);
  const ids = inst.state.members.map((m) => m.id);
  // Laura si è registrata (con un'e-mail diversa dal suo account: la si trova dal nome) e Anna, omonima parziale, non va toccata.
  inst.state.members.push({ id: 'MBR-900099', sub: 'sub-laura.conti', status: 'ACTIVE', firstName: 'Laura', lastName: 'Conti', email: 'laura.altra@example.org' });
  inst.state.members.push({ id: 'MBR-900100', sub: null, status: 'ACTIVE', firstName: 'Laura', lastName: 'Verdi', email: 'laura.verdi@example.org' });
  const r = await run(inst);
  assert.equal(r.code, 0, r.err);
  assert.match(r.out, /registrazione dal portale già registrato/);
  assert.match(r.out, /Laura Conti: 1 membro anonimizzato \(audit con l'attore marta\.admin\)/);
  assert.deepEqual(inst.state.members.slice(0, 3).map((m) => m.id), ids, 'nessun nuovo membro per Anna, Marco e Giulia');
  assert.equal(inst.state.members.filter((m) => m.status === 'ANONYMIZED').length, 1);
  assert.equal(inst.state.members.find((m) => m.id === 'MBR-900100').status, 'ACTIVE', 'un\'altra Laura non si tocca');
  assert.deepEqual(inst.state.audit, [{ entityId: 'MBR-900099', action: 'TRANSITION', actorName: 'marta.admin' }]);
  // Terzo avvio: Laura è già anonimizzata, niente da fare. Il codice OTP della stessa finestra è già stato usato: secondo tentativo.
  const again = await run(inst);
  assert.equal(again.code, 0, again.err);
  assert.match(again.out, /Laura Conti: nessun profilo attivo/);
  assert.equal(inst.state.audit.length, 1);
  noSecrets(r.out + r.err + again.out + again.err);
});

test('membri: e-mail già di un altro membro → 409 con messaggio chiaro in italiano, uscita 1', async (t) => {
  const inst = await fakeInstance({ members: [{ id: 'MBR-000777', sub: null, status: 'ACTIVE', firstName: 'Altra', lastName: 'Persona', email: 'anna.rossi@example.org' }] });
  t.after(inst.close);
  const r = await run(inst);
  assert.equal(r.code, 1);
  assert.match(r.err, /^FALLITO: registrazione di Anna Rossi: 409 EMAIL_TAKEN: l'e-mail anna\.rossi@example\.org è già di un altro membro/);
  assert.match(r.err, /vetrina\.sh reset/);
  noSecrets(r.out + r.err);
});

test('membri: login rifiutato dall\'IdP → uscita 1 con il passo e il messaggio, senza password', async (t) => {
  const inst = await fakeInstance({ wrongPassword: 'marco.bianchi' });
  t.after(inst.close);
  const r = await run(inst);
  assert.equal(r.code, 1);
  assert.match(r.err, /login di marco\.bianchi: l'IdP ripropone il passo «nome utente e password»: Nome utente o password non validi/);
  assert.equal(inst.state.registrations.length, 1, 'Anna fatta, Marco no');
  noSecrets(r.out + r.err);
});

test('membri: uso non valido → uscita 2 senza richieste', async () => {
  const err = capture();
  assert.equal(await membri(['--web', 'http://web.example.org'], { stdout: capture(), stderr: err, fetch: () => assert.fail('nessuna richiesta') }), 2);
  assert.match(err.text(), /serve https/);
  assert.equal(await membri(['--help'], { stdout: capture(), stderr: capture() }), 0);
});

test('smoke vetrina --test-users: login con password e seme fissi (nessun cambio password né configurazione OTP) e profilo di Anna → 200', async (t) => {
  const inst = await fakeInstance();
  t.after(inst.close);
  // Il profilo di Anna esiste solo dopo la registrazione di vetrina-membri.
  assert.equal((await run(inst)).code, 0);
  // Il passo del modo vetrina con --test-users (il resto del modo vetrina, senza credenziali, lo prova smoke-enterprise.test.mjs).
  const out = capture();
  const ctx = createContext({ ...inst.deps, stdout: out });
  await checkTestUsers(ctx, { web: inst.web });
  assert.match(out.text(), /login OIDC reale di paolo\.care con credenziali e seme TOTP fissi \(login → otp\), senza azioni richieste/);
  assert.match(out.text(), /anna\.rossi \(realm dei membri\) apre il suo profilo, GET \/v1\/portal\/me\/profile → 200/);
  noSecrets(out.text());
  // Un solo --test-users ammesso nel solo modo vetrina.
  const err = capture();
  assert.equal(await smoke(['compose', '--test-users', '--allow-http', '--web', inst.web], { stdout: capture(), stderr: err }), 2);
  assert.match(err.text(), /solo nel modo vetrina/);
});
