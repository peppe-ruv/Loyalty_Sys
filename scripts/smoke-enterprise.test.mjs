// Test di scripts/smoke-enterprise.mjs (F2-QA-04, M8.2, ADR-049, M8.14 V6): funzioni pure e i due modi contro un
// BFF, un Keycloak e un hub finti in Node (loopback, http con --allow-http). Uso: node --test scripts/smoke-enterprise.test.mjs
// Le password di prova si generano a ogni esecuzione: nessun valore che somigli a un segreto nel sorgente (gitleaks).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {
  CookieJar, decodeEntities, hubTiles, isRealActor, main, pageFeedback, parseArgs, parseForms, readCredentials, redact,
  totp, UsageError, validateOrigin,
} from './smoke-enterprise.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'lh-smoke-ent-'));
const randomPw = () => crypto.randomBytes(12).toString('base64url');
// Attesa vera ma breve: il calcolo dell'OTP aspetta la finestra successiva negli ultimi 3 secondi di una finestra.
const sleep = (ms) => new Promise((r) => setTimeout(r, Math.min(ms, 4000)));

// ---------------------------------------------------------------------------------------------------------------
// Funzioni pure

test('totp: vettori di RFC 6238 (SHA-1, 6 cifre) e chiave come byte UTF-8 del segreto mostrato da Keycloak', () => {
  const seed = Buffer.from('1234567890'.repeat(2));
  assert.equal(totp(seed, 59_000), '287082');
  assert.equal(totp(seed, 1_111_111_109_000), '081804');
  assert.equal(totp(seed, 1_234_567_890_000), '005924');
  assert.equal(totp('1234567890'.repeat(2), 59_000), '287082', 'stringa = byte UTF-8');
});

test('parseForms: action decodificata, campi nascosti, checkbox solo se spuntate, pulsanti esclusi', () => {
  const html = `<form id="kc-form-login" action="https://idp.example.org/realms/r/login-actions/authenticate?session_code=a&amp;tab_id=b" method="post">
    <input id="username" name="username" type="text" value="">
    <input name="password" type="password">
    <input type="hidden" name="credentialId" value="">
    <input type="checkbox" name="rememberMe">
    <input type="checkbox" name="logout-sessions" value="on" checked>
    <input type="submit" name="login" value="Accedi">
  </form><form action="/x"><input name="q"></form>`;
  const forms = parseForms(html);
  assert.equal(forms.length, 2);
  assert.equal(forms[0].id, 'kc-form-login');
  assert.equal(forms[0].method, 'post');
  assert.equal(forms[0].action, 'https://idp.example.org/realms/r/login-actions/authenticate?session_code=a&tab_id=b');
  assert.deepEqual(forms[0].inputs.map((i) => i.name), ['username', 'password', 'credentialId', 'logout-sessions']);
  assert.equal(forms[1].method, 'get');
  assert.equal(decodeEntities('&lt;a&gt; &#x2F; &#39; &quot; &amp;amp;'), '<a> / \' " &amp;');
});

test('pageFeedback: testo dell\'errore di Keycloak senza tag', () => {
  assert.equal(pageFeedback('<span id="input-error" class="x" aria-live="polite">Nome utente o password <b>non validi</b>.</span>'),
    'Nome utente o password non validi .');
  assert.equal(pageFeedback('<div class="pf-v5-c-alert__title kc-feedback-text">Codice non valido</div>'), 'Codice non valido');
  assert.equal(pageFeedback('<p>nulla</p>'), '');
});

test('CookieJar: per host e porta, cancellazione con Max-Age=0 o valore vuoto', () => {
  const jar = new CookieJar();
  jar.store('https://web.example.org/a', ['__Host-lh_session=abc; Path=/; Secure; HttpOnly', 'x=1']);
  jar.store('https://idp.example.org:8443/', ['KC=1; Path=/realms/r/']);
  assert.equal(jar.header('https://web.example.org/b'), '__Host-lh_session=abc; x=1');
  assert.equal(jar.header('https://idp.example.org/'), null, 'porta diversa, host diverso');
  assert.equal(jar.get('https://idp.example.org:8443/x', 'KC'), '1');
  jar.store('https://web.example.org/', ['x=; Max-Age=0', '__Host-lh_session=; Expires=Thu, 01 Jan 1970 00:00:00 GMT']);
  assert.equal(jar.header('https://web.example.org/'), null);
});

test('hubTiles: stato delle tessere anche dalla parte in streaming', () => {
  const html = '<div hidden id="S:0"><ul><li class="a" data-testid="tile-hub" data-state="UP"><span>Hub</span></li>'
    + '<li data-testid="tile-cms" data-state="NOT_INSTALLED"></li><li class="b"></li></ul></div>';
  assert.deepEqual(hubTiles(html), { hub: 'UP', cms: 'NOT_INSTALLED' });
});

test('isRealActor e redact', () => {
  assert.equal(isRealActor({ actorName: 'marta.admin', actorRole: 'ADMIN' }), true);
  for (const r of [{ actorName: 'demo' }, { actorName: 'system' }, { actorName: 'x', actorRole: 'SYSTEM' }, { actorName: 'src-crm' }, { actorName: 'member:1' }]) {
    assert.equal(isRealActor(r), false, JSON.stringify(r));
  }
  assert.equal(redact('https://web.example.org/api/auth/callback?code=abc&state=def'), 'https://web.example.org/api/auth/callback');
});

test('validateOrigin e parseArgs: https, http solo loopback con --allow-http, nessuna credenziale nel modo vetrina', () => {
  assert.equal(validateOrigin('https://web.example.org/'), 'https://web.example.org');
  assert.throws(() => validateOrigin('http://web.example.org'), UsageError);
  assert.throws(() => validateOrigin('http://127.0.0.1:3000'), UsageError);
  assert.equal(validateOrigin('http://127.0.0.1:3000', { allowHttp: true }), 'http://127.0.0.1:3000');
  assert.throws(() => validateOrigin('http://web.example.org', { allowHttp: true }), UsageError);
  assert.throws(() => validateOrigin('https://web.example.org/backoffice'), UsageError);
  assert.throws(() => validateOrigin('https://u:p@web.example.org'), UsageError);
  const v = parseArgs(['vetrina', '--web', 'https://web.example.org']);
  assert.equal(v.registration, 'closed');
  assert.throws(() => parseArgs(['vetrina', '--web', 'https://w.example.org', '--operator', 'a']), /senza credenziali/);
  assert.throws(() => parseArgs(['compose', '--web', 'https://w.example.org', '--hub', 'http://hub.example.org:8080', '--operator', 'a', '--operator-credentials', 'f']), /--hub/);
  assert.throws(() => parseArgs(['compose', '--web', 'https://w.example.org', '--hub', 'http://127.0.0.1:8080']), /--operator/);
  assert.throws(() => parseArgs(['vetrina', '--web']), /valore/);
});

test('readCredentials: formato di bootstrap.sh, permessi 0600, collegamenti simbolici rifiutati', () => {
  const dir = tmp();
  const pw = randomPw();
  const file = path.join(dir, 'cred.txt');
  fs.writeFileSync(file, `# intestazione\nluca.marketing: (fornita da TEMP_PASS_*, non registrata qui)\nmarta.admin: ${pw}\n`, { mode: 0o600 });
  assert.equal(readCredentials(file, 'marta.admin'), pw);
  assert.throws(() => readCredentials(file, 'luca.marketing'), /nessuna password/);
  assert.throws(() => readCredentials(file, 'nessuno'), /nessuna password/);
  fs.chmodSync(file, 0o644);
  assert.throws(() => readCredentials(file, 'marta.admin'), /0600/);
  fs.chmodSync(file, 0o600);
  const link = path.join(dir, 'link.txt');
  fs.symlinkSync(file, link);
  assert.throws(() => readCredentials(link, 'marta.admin'), /collegamento simbolico/);
});

// ---------------------------------------------------------------------------------------------------------------
// BFF, Keycloak e hub finti

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
const cookies = (req) => Object.fromEntries((req.headers.cookie ?? '').split(';').filter(Boolean).map((c) => {
  const i = c.indexOf('=');
  return [c.slice(0, i).trim(), c.slice(i + 1).trim()];
}));
const send = (res, status, body, headers = {}) => {
  const json = typeof body !== 'string';
  res.writeHead(status, { 'content-type': json ? 'application/json' : 'text/html', ...headers });
  res.end(json ? JSON.stringify(body) : body);
};
const redirectTo = (res, location, headers = {}) => send(res, 303, '', { location, ...headers });

/** Pagine di Keycloak ridotte all'essenziale (stessi nomi dei campi del tema keycloak.v2). */
const page = {
  login: (action, { registration = false, error = '' } = {}) => `<html><body>${error ? `<span id="input-error">${error}</span>` : ''}
    <form id="kc-form-login" action="${action}" method="post"><input name="username" value=""><input type="password" name="password">
    <input type="hidden" name="credentialId" value=""></form>${registration ? '<a href="/realms/loyaltyhub/login-actions/registration?client_id=web">Registrati</a>' : ''}</body></html>`,
  updatePassword: (action) => `<form id="kc-passwd-update-form" action="${action}" method="post">
    <input type="text" name="username" value="marta.admin" style="display:none"><input type="password" name="password" style="display:none">
    <input type="password" name="password-new"><input type="password" name="password-confirm">
    <input type="checkbox" name="logout-sessions" value="on" checked></form>`,
  configTotp: (action, seed) => `<form id="kc-totp-settings-form" action="${action}" method="post">
    <input name="totp" autocomplete="off"><input type="hidden" id="totpSecret" name="totpSecret" value="${seed}">
    <input name="userLabel"></form>`,
};

/**
 * Istanza finta: BFF (web), Keycloak (idp) e hub. `users` = { nome: { password, roles, mfa, updatePassword } }.
 * `audit` raccoglie le voci; `actorOverride` sostituisce l'attore delle voci (per provare il rifiuto).
 */
async function fakeInstance({ users, registration = false, actorOverride = null, tiles = {}, memberLogin = 'ok', testUsersHub = false, masterCard = '' } = {}) {
  const state = { sessions: new Map(), flows: new Map(), codes: new Map(), kcSessions: new Map(), audit: [], categories: [], seen: [] };
  const servers = {};
  servers.hub = await listen((req, res) => {
    if (req.url === '/actuator/health') return send(res, 200, { status: 'UP' });
    return send(res, 401, { status: 401 });
  });
  servers.idp = await listen((req, res, body) => {
    const u = new URL(req.url, origin(servers.idp));
    const iss = `${origin(servers.idp)}/realms/loyaltyhub`;
    state.seen.push(`idp ${req.method} ${u.pathname}`);
    // Realm dei membri (ADR-051): discovery, JWKS e pagina di login con la registrazione aperta.
    const miss = `${origin(servers.idp)}/realms/loyaltyhub-members`;
    if (u.pathname === '/realms/loyaltyhub-members/.well-known/openid-configuration') {
      return send(res, 200, {
        issuer: miss, authorization_endpoint: `${miss}/protocol/openid-connect/auth`, token_endpoint: `${miss}/protocol/openid-connect/token`,
        jwks_uri: `${miss}/protocol/openid-connect/certs`, end_session_endpoint: `${miss}/protocol/openid-connect/logout`,
        code_challenge_methods_supported: ['S256'],
      });
    }
    if (u.pathname === '/realms/loyaltyhub-members/protocol/openid-connect/certs') return send(res, 200, { keys: [{ kid: '2', kty: 'EC', use: 'sig', crv: 'P-256', x: 'AQAB', y: 'AQAB' }] });
    if (u.pathname === '/realms/loyaltyhub-members/protocol/openid-connect/auth') {
      assert.equal(u.searchParams.get('client_id'), 'portal');
      return send(res, 200, page.login(`${miss}/login-actions/authenticate?session_code=1`).replace('</body>', '<a href="/realms/loyaltyhub-members/login-actions/registration?client_id=portal">Registrati</a></body>'));
    }
    if (u.pathname === '/realms/loyaltyhub/.well-known/openid-configuration') {
      return send(res, 200, {
        issuer: iss, authorization_endpoint: `${iss}/protocol/openid-connect/auth`, token_endpoint: `${iss}/protocol/openid-connect/token`,
        jwks_uri: `${iss}/protocol/openid-connect/certs`, end_session_endpoint: `${iss}/protocol/openid-connect/logout`,
        code_challenge_methods_supported: ['plain', 'S256'],
      });
    }
    if (u.pathname === '/realms/loyaltyhub/protocol/openid-connect/certs') return send(res, 200, { keys: [{ kid: '1', kty: 'RSA', use: 'sig', n: 'AQAB', e: 'AQAB' }] });
    if (u.pathname === '/realms/loyaltyhub/protocol/openid-connect/auth') {
      assert.equal(u.searchParams.get('client_id'), 'web');
      const kc = crypto.randomUUID();
      state.kcSessions.set(kc, { redirect: u.searchParams.get('redirect_uri'), state: u.searchParams.get('state'), step: 'login' });
      return send(res, 200, page.login(`${iss}/login-actions/authenticate?session_code=1&amp;kc=${kc}`, { registration }), { 'set-cookie': `AUTH_SESSION_ID=${kc}; Path=/realms/loyaltyhub/; Secure; HttpOnly` });
    }
    if (u.pathname === '/realms/loyaltyhub/login-actions/authenticate' && req.method === 'POST') {
      const kc = u.searchParams.get('kc');
      const s = state.kcSessions.get(kc);
      assert.equal(cookies(req).AUTH_SESSION_ID, kc, 'cookie di Keycloak rimandato');
      const f = new URLSearchParams(body);
      const action = `${iss}/login-actions/authenticate?kc=${kc}`;
      if (s.step === 'login') {
        const user = users[f.get('username')];
        if (!user || user.password !== f.get('password')) return send(res, 200, page.login(action, { error: 'Nome utente o password non validi.' }));
        s.user = f.get('username');
        s.step = user.updatePassword ? 'password' : user.mfa ? 'totp' : 'done';
      } else if (s.step === 'password') {
        assert.ok(f.get('password-new') && f.get('password-new') === f.get('password-confirm'));
        assert.equal(f.get('logout-sessions'), 'on');
        users[s.user].password = f.get('password-new');
        s.step = users[s.user].mfa ? 'totp' : 'done';
      } else if (s.step === 'totp') {
        assert.equal(f.get('totpSecret'), s.seed);
        // Verifica indipendente: HMAC-SHA1 della finestra corrente o precedente.
        const now = Date.now();
        const ok = [now, now - 30_000].some((t) => totp(s.seed, t) === f.get('totp'));
        if (!ok) return send(res, 200, `<div class="kc-feedback-text">Codice non valido</div>${page.configTotp(action, s.seed)}`);
        s.step = 'done';
      }
      if (s.step === 'password') return send(res, 200, page.updatePassword(action));
      if (s.step === 'totp') {
        s.seed = crypto.randomBytes(15).toString('base64url');
        return send(res, 200, page.configTotp(action, s.seed));
      }
      const code = crypto.randomUUID();
      state.codes.set(code, s.user);
      return redirectTo(res, `${s.redirect}?state=${encodeURIComponent(s.state)}&code=${code}&iss=${encodeURIComponent(iss)}`);
    }
    return send(res, 404, 'not found');
  });
  servers.web = await listen((req, res, body) => {
    const web = origin(servers.web);
    const u = new URL(req.url, web);
    const c = cookies(req);
    const session = state.sessions.get(c['__Host-lh_session']);
    state.seen.push(`web ${req.method} ${u.pathname}`);
    if (u.pathname === '/api/demo/status') return send(res, 200, { checkedAt: new Date().toISOString(), services: [] });
    if (u.pathname === '/') {
      const t = { hub: 'UP', web: 'UP', idp: 'UP', cms: 'NOT_INSTALLED', db: 'UP', kafka: 'UP', ...tiles };
      return send(res, 200, `<h1>Loyalty Hub</h1><p role="note" data-testid="showcase-banner">Vetrina</p>${testUsersHub ? `<section data-testid="test-users">${masterCard}</section>` : '<div data-testid="portal-closed"></div>'}
        <ul>${Object.entries(t).map(([k, v]) => `<li data-testid="tile-${k}" data-state="${v}"></li>`).join('')}</ul>`);
    }
    if (u.pathname === '/api/auth/login' && u.searchParams.get('realm') === 'members') {
      if (memberLogin === 'operators') u.searchParams.delete('realm');
      else {
        const q = new URLSearchParams({
          client_id: 'portal', response_type: 'code', scope: 'openid', redirect_uri: `${web}/api/auth/callback/members`,
          state: 's', nonce: 'n', code_challenge: 'c', code_challenge_method: 'S256',
        });
        return redirectTo(res, `${origin(servers.idp)}/realms/loyaltyhub-members/protocol/openid-connect/auth?${q}`, { 'set-cookie': '__Host-lh_auth_members=f; Path=/; Secure; HttpOnly; SameSite=Lax' });
      }
    }
    if (u.pathname === '/api/auth/login') {
      const flow = crypto.randomUUID();
      state.flows.set(flow, crypto.randomUUID());
      const q = new URLSearchParams({
        client_id: 'web', response_type: 'code', scope: 'openid', redirect_uri: `${web}/api/auth/callback`,
        state: state.flows.get(flow), nonce: 'n', code_challenge: 'c', code_challenge_method: 'S256',
      });
      return redirectTo(res, `${origin(servers.idp)}/realms/loyaltyhub/protocol/openid-connect/auth?${q}`, { 'set-cookie': `__Host-lh_auth=${flow}; Path=/; Secure; HttpOnly; SameSite=Lax` });
    }
    if (u.pathname === '/api/auth/callback') {
      const user = state.codes.get(u.searchParams.get('code'));
      if (!user || state.flows.get(c['__Host-lh_auth']) !== u.searchParams.get('state')) return redirectTo(res, `${web}/auth/error?reason=rejected`);
      const id = crypto.randomUUID();
      state.sessions.set(id, { user, roles: users[user].roles, csrf: crypto.randomUUID() });
      return redirectTo(res, `${web}/backoffice`, { 'set-cookie': [`__Host-lh_session=${id}; Path=/; Secure; HttpOnly`, `__Host-lh_csrf=${state.sessions.get(id).csrf}; Path=/; Secure`, '__Host-lh_auth=; Path=/; Max-Age=0'] });
    }
    if (u.pathname === '/api/persona') return send(res, 404, { error: 'PERSONA_DISABLED' });
    if (u.pathname.startsWith('/api/lh/')) {
      if (!session) return send(res, 401, { code: 'UNAUTHENTICATED' });
      if (req.method !== 'GET' && (req.headers.origin !== web || req.headers['x-lh-csrf'] !== session.csrf)) return send(res, 403, { code: 'CSRF_REJECTED' });
      if (session.roles.includes('MEMBER')) return send(res, 403, { code: 'FORBIDDEN_ROLE' });
      if (u.pathname === '/api/lh/reward/v1/reward-categories') {
        if (req.method === 'GET') return send(res, 200, state.categories);
        const cat = JSON.parse(body);
        state.categories.push(cat);
        state.audit.push({ id: crypto.randomUUID(), entityType: 'REWARD_CATEGORY', entityId: cat.code, action: 'CREATE', actorName: actorOverride ?? session.user, actorRole: 'ADMIN' });
        return send(res, 201, cat);
      }
      if (u.pathname === '/api/lh/insight/v1/audit') {
        const items = state.audit.filter((r) => r.entityType === u.searchParams.get('entityType') && r.entityId === u.searchParams.get('entityId'));
        return send(res, 200, { items, page: { totalPages: 1 } });
      }
    }
    return send(res, 404, 'not found');
  });
  return {
    state,
    web: origin(servers.web),
    hub: origin(servers.hub),
    close: () => Promise.all(Object.values(servers).map((s) => new Promise((r) => s.close(r)))),
  };
}

function capture() {
  const chunks = [];
  return { write: (s) => chunks.push(String(s)), text: () => chunks.join('') };
}

async function runCompose(t, inst, { operatorPw, memberPw, extra = [] } = {}) {
  const dir = tmp();
  const opFile = path.join(dir, 'operatori.txt');
  const memFile = path.join(dir, 'membro.txt');
  fs.writeFileSync(opFile, `# prova\nmarta.admin: ${operatorPw}\n`, { mode: 0o600 });
  fs.writeFileSync(memFile, `testmember: ${memberPw}\n`, { mode: 0o600 });
  const out = capture();
  const err = capture();
  const code = await main(['compose', '--allow-http', '--web', inst.web, '--hub', inst.hub, '--operator', 'marta.admin',
    '--operator-credentials', opFile, '--member', 'testmember', '--member-credentials', memFile, '--settle', '0', '--audit-timeout', '1', ...extra],
  { stdout: out, stderr: err, retries: 0, sleep });
  return { code, out: out.text(), err: err.text() };
}

const operator = (pw) => ({ password: pw, roles: ['ADMIN', 'MFA_REQUIRED_ROLE'], mfa: true, updatePassword: true });
const member = (pw) => ({ password: pw, roles: ['MEMBER'], mfa: false, updatePassword: false });

test('compose: login reale con cambio password e OTP, scrittura con CSRF, voce di audit con l\'attore; nessun segreto nell\'output', async (t) => {
  const operatorPw = randomPw();
  const memberPw = randomPw();
  const users = { 'marta.admin': operator(operatorPw), testmember: member(memberPw) };
  const inst = await fakeInstance({ users, registration: true });
  t.after(inst.close);
  const r = await runCompose(t, inst, { operatorPw, memberPw });
  assert.equal(r.code, 0, r.err);
  assert.match(r.out, /cambio della password temporanea/);
  assert.match(r.out, /configurazione dell'OTP/);
  assert.match(r.out, /voce CREATE di REWARD_CATEGORY SMOKE[0-9A-Z]+ con attore reale marta\.admin/);
  assert.match(r.out, /403 FORBIDDEN_ROLE/);
  assert.match(r.out, /login dei membri: il BFF risponde 303 verso il realm loyaltyhub-members, client portal/);
  assert.match(r.out, /discovery OIDC del realm loyaltyhub-members/);
  assert.match(r.out, /pagina di login del realm loyaltyhub-members raggiungibile, con la registrazione dei membri/);
  assert.match(r.out, /superato: 1\d controlli/);
  // Il cambio password è avvenuto e la nuova password non compare da nessuna parte.
  assert.notEqual(users['marta.admin'].password, operatorPw);
  const all = r.out + r.err;
  for (const s of [operatorPw, memberPw, users['marta.admin'].password, ...inst.state.sessions.keys(), ...inst.state.codes.keys()]) {
    assert.ok(!all.includes(s), 'un segreto è finito nell\'output');
  }
  for (const s of inst.state.kcSessions.values()) if (s.seed) assert.ok(!all.includes(s.seed), 'segreto TOTP nell\'output');
  assert.equal(inst.state.categories.length, 1, 'una sola scrittura: quella senza CSRF è rifiutata');
});

test('compose: password sbagliata → uscita 1 con il messaggio dell\'IdP, senza la password', async (t) => {
  const operatorPw = randomPw();
  const wrong = randomPw();
  const inst = await fakeInstance({ users: { 'marta.admin': operator(operatorPw), testmember: member(randomPw()) } });
  t.after(inst.close);
  const r = await runCompose(t, inst, { operatorPw: wrong, memberPw: randomPw() });
  assert.equal(r.code, 1);
  assert.match(r.err, /ripropone il passo «nome utente e password»: Nome utente o password non validi/);
  assert.ok(!(r.out + r.err).includes(wrong));
});

test('compose: voce di audit con attore non reale → uscita 1', async (t) => {
  const operatorPw = randomPw();
  const memberPw = randomPw();
  const inst = await fakeInstance({ users: { 'marta.admin': operator(operatorPw), testmember: member(memberPw) }, actorOverride: 'demo' });
  t.after(inst.close);
  const r = await runCompose(t, inst, { operatorPw, memberPw });
  assert.equal(r.code, 1);
  assert.match(r.err, /nessuna voce CREATE .* con attore marta\.admin \(attori: demo\)/);
});

test('compose: operatore senza MFA → uscita 1 (la MFA degli operatori deve scattare)', async (t) => {
  const operatorPw = randomPw();
  const memberPw = randomPw();
  const inst = await fakeInstance({ users: { 'marta.admin': { ...operator(operatorPw), mfa: false }, testmember: member(memberPw) } });
  t.after(inst.close);
  const r = await runCompose(t, inst, { operatorPw, memberPw });
  assert.equal(r.code, 1);
  assert.match(r.err, /senza OTP/);
});

test('vetrina: solo GET, nessuna credenziale; registrazione chiusa richiesta', async (t) => {
  const inst = await fakeInstance({ users: {} });
  t.after(inst.close);
  const out = capture();
  const err = capture();
  const code = await main(['vetrina', '--allow-http', '--web', inst.web, '--settle', '0'], { stdout: out, stderr: err, retries: 0, sleep });
  assert.equal(code, 0, err.text());
  assert.match(out.text(), /registrazione chiusa \(Q-619\)/);
  assert.ok(inst.state.seen.every((s) => / GET /.test(s)), `richieste non GET: ${inst.state.seen.filter((s) => !/ GET /.test(s))}`);
  assert.ok(!inst.state.seen.some((s) => s.includes('login-actions/authenticate')), 'nessun tentativo di login');
});

test('vetrina: registrazione aperta o tessera giù → uscita 1 con la causa', async (t) => {
  const open = await fakeInstance({ users: {}, registration: true });
  t.after(open.close);
  let err = capture();
  assert.equal(await main(['vetrina', '--allow-http', '--web', open.web, '--settle', '0'], { stdout: capture(), stderr: err, retries: 0, sleep }), 1);
  assert.match(err.text(), /registrazione è aperta/);

  const down = await fakeInstance({ users: {}, tiles: { kafka: 'DOWN' } });
  t.after(down.close);
  err = capture();
  assert.equal(await main(['vetrina', '--allow-http', '--web', down.web, '--settle', '0'], { stdout: capture(), stderr: err, retries: 0, sleep }), 1);
  assert.match(err.text(), /tessere non attive: kafka=DOWN/);
});

test('realm dei membri: login dei membri che finisce nel realm degli operatori → uscita 1 con la causa (ADR-051)', async (t) => {
  const inst = await fakeInstance({ users: {}, memberLogin: 'operators' });
  t.after(inst.close);
  const err = capture();
  assert.equal(await main(['vetrina', '--allow-http', '--web', inst.web, '--settle', '0'], { stdout: capture(), stderr: err, retries: 0, sleep }), 1);
  assert.match(err.text(), /login dei membri: redirect verso \/realms\/loyaltyhub\/protocol\/openid-connect\/auth, atteso l'endpoint di autorizzazione del realm loyaltyhub-members/);
});

test('vetrina con --test-users: HUB-02 mostra le schede degli utenti di test al posto del portale chiuso (ADR-051 decisione 1)', async (t) => {
  const inst = await fakeInstance({ users: {}, testUsersHub: true });
  t.after(inst.close);
  const out = capture();
  // Il seguito (login degli utenti di test) non e' simulato qui: conta solo il controllo di HUB-02.
  await main(['vetrina', '--allow-http', '--web', inst.web, '--settle', '0', '--test-users'], { stdout: out, stderr: capture(), retries: 0, sleep });
  assert.match(out.text(), /HUB-02: banner, schede degli utenti di test e tessere/);

  // Senza --test-users le schede al posto del portale chiuso sono un errore (profilo di test non atteso).
  const err = capture();
  assert.equal(await main(['vetrina', '--allow-http', '--web', inst.web, '--settle', '0'], { stdout: capture(), stderr: err, retries: 0, sleep }), 1);
  assert.match(err.text(), /manca il riquadro del portale chiuso/);
});

test('vetrina con --test-users: la scheda del realm master e\' facoltativa ma non mostra mai credenziali (ADR-055, Q-727)', async (t) => {
  const run = async (masterCard) => {
    const inst = await fakeInstance({ users: {}, testUsersHub: true, masterCard });
    t.after(inst.close);
    const out = capture();
    const err = capture();
    await main(['vetrina', '--allow-http', '--web', inst.web, '--settle', '0', '--test-users'], { stdout: out, stderr: err, retries: 0, sleep });
    return { out: out.text(), err: err.text() };
  };
  // Assente: va bene. Aperta o chiusa senza credenziali: va bene.
  assert.match((await run('')).out, /HUB-02: banner, schede degli utenti di test e tessere/);
  const aperta = '<div data-testid="console-master" data-state="open"><p>Credenziali del proprietario dell\'ambiente, non mostrate qui.</p><a href="https://idp.example.org/admin/master/console/">Apri la console</a></div>';
  assert.match((await run(aperta)).out, /HUB-02: banner, schede degli utenti di test e tessere/);
  const chiusa = '<div data-testid="console-master" data-state="closed"><p>manca il segreto LH_VETRINA_MASTER_ADMIN_PASSWORD o non e valido</p><button type="button" disabled>Apri la console</button></div>';
  assert.match((await run(chiusa)).out, /HUB-02: banner, schede degli utenti di test e tessere/);
  // Credenziali nella scheda: errore. Il valore sospetto si compone a runtime (nessun segreto letterale nel sorgente).
  const nome = ['LH_VETRINA', 'MASTER_ADMIN', 'PASSWORD'].join('_');
  for (const leak of [`<code>${nome}=abc</code>`, '<button type="button">Copia</button>', '<span data-value="proprietario">x</span>']) {
    const r = await run(`<div data-testid="console-master" data-state="open">${leak}</div>`);
    assert.match(r.err, /la scheda del realm master mostra una credenziale/, leak);
  }
});

test('uso: errori di argomenti → uscita 2 senza richieste', async () => {
  const err = capture();
  assert.equal(await main(['vetrina', '--web', 'http://web.example.org'], { stdout: capture(), stderr: err, fetch: () => assert.fail('nessuna richiesta') }), 2);
  assert.match(err.text(), /serve https/);
});
