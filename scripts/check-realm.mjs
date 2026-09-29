#!/usr/bin/env node
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REALM_PATH = path.join(ROOT, 'deploy/idp/realm.json');
const OVERLAY_PATH = path.join(ROOT, 'deploy/idp/test-idp/realm-test-overlay.json');
const COMPOSE_PATH = path.join(ROOT, 'deploy/docker-compose.yml');
const PLACEHOLDER = /^\$\{[A-Z0-9_]+\}$/;
const SECRET_KEYS = new Set(['secret', 'clientSecret', 'bindCredential']);

// Raccoglie [percorso, valore] di ogni chiave segreta, a qualunque profondità (stringa o lista di stringhe).
function secretValues(node, at = '$', out = []) {
  if (Array.isArray(node)) {
    node.forEach((v, i) => secretValues(v, `${at}[${i}]`, out));
  } else if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      if (SECRET_KEYS.has(k)) {
        for (const x of Array.isArray(v) ? v : [v]) out.push([`${at}.${k}`, x]);
      } else {
        secretValues(v, `${at}.${k}`, out);
      }
    }
  }
  return out;
}

let realm;

test('Caricamento realm.json', () => {
  assert.ok(fs.existsSync(REALM_PATH), 'Il file realm.json non esiste in deploy/idp/');
  realm = JSON.parse(fs.readFileSync(REALM_PATH, 'utf8'));
  assert.ok(realm, 'Impossibile fare il parse del JSON');
});

test('Tutti i 7 ruoli (ADMIN, MARKETING, LEGAL, CARE, ANALYST, MEMBER, SOURCE) sono presenti', () => {
  const roles = realm.roles.realm.map(r => r.name);
  const required = ['ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST', 'MEMBER', 'SOURCE'];
  required.forEach(req => {
    assert.ok(roles.includes(req), `Manca il ruolo: ${req}`);
  });
});

test('Nessun segreto in chiaro o password negli utenti', () => {
  if (realm.users) {
    realm.users.forEach(user => {
      if (user.credentials) {
        user.credentials.forEach(cred => {
          assert.notEqual(cred.type, 'password', `L'utente ${user.username} contiene una password fissa o in chiaro`);
        });
      }
    });
  }

  if (realm.clients) {
    realm.clients.forEach(client => {
      if (client.secret) {
        assert.ok(client.secret.startsWith('${') && client.secret.endsWith('}'), `Il client ${client.clientId} ha un segreto hardcoded: ${client.secret}`);
      }
    });
  }

  if (realm.components && realm.components['org.keycloak.storage.UserStorageProvider']) {
    realm.components['org.keycloak.storage.UserStorageProvider'].forEach(p => {
        if (p.config && p.config.bindCredential) {
            p.config.bindCredential.forEach(cred => {
                assert.ok(cred.startsWith('${') && cred.endsWith('}'), `Literal bindCredential found in LDAP component ${p.name}`);
            });
        }
    });
  }

  if (realm.identityProviders) {
      realm.identityProviders.forEach(idp => {
          if (idp.config && idp.config.clientSecret) {
              assert.ok(idp.config.clientSecret.startsWith('${') && idp.config.clientSecret.endsWith('}'), `Literal clientSecret found in IdP ${idp.alias}`);
          }
      });
  }
});

test('I client di tipo service-account usano private_key_jwt (client-jwt)', () => {
    if (realm.clients) {
        realm.clients.forEach(client => {
            if (client.serviceAccountsEnabled) {
                assert.equal(client.clientAuthenticatorType, 'client-jwt', `Il client service-account ${client.clientId} deve usare client-jwt invece di ${client.clientAuthenticatorType}`);
            }
        });
    }
});

// Fonti di ingestion (Q-492, F2-SEC-07, F2-IAM-02, docs/18 §3.2 e §3.10): un client credentials con private_key_jwt per
// fonte, `client_id` = `src-<codice>` del registro fonti (seed/sources.json), utenza di servizio con il solo ruolo SOURCE.
// Solo le fonti che entrano da HTTP: le INTERNAL (ponte dei fatti, simulatore) non hanno client (minimo privilegio, review M8.2f S2).
const ALL_SEED_SOURCES = () => JSON.parse(fs.readFileSync(path.join(ROOT, 'seed/sources.json'), 'utf8'));
const SEED_SOURCES = () => ALL_SEED_SOURCES().filter(s => s.kind === 'HTTP').map(s => s.code);
const INTERNAL_SOURCES = () => ALL_SEED_SOURCES().filter(s => s.kind === 'INTERNAL').map(s => s.code);
const SOURCE_PREFIX = 'src-';

test('Il prefisso dei client di fonte del realm è lo stesso del codice (ActorContext.SOURCE_CLIENT_PREFIX)', () => {
  const java = fs.readFileSync(path.join(ROOT, 'libs/lh-common/src/main/java/io/loyaltyhub/common/web/ActorContext.java'), 'utf8');
  const prefix = (java.match(/SOURCE_CLIENT_PREFIX = "([^"]+)"/) || [])[1];
  assert.equal(prefix, SOURCE_PREFIX, 'ActorContext.SOURCE_CLIENT_PREFIX diverso dal prefisso dei client del realm');
});

test('Ogni fonte HTTP del seed ha un client src-<codice>: confidential, private_key_jwt, solo service account, senza segreto', () => {
  const codes = SEED_SOURCES();
  assert.ok(codes.length > 0, 'seed/sources.json vuoto');
  for (const code of codes) {
    const id = `${SOURCE_PREFIX}${code}`;
    const client = realm.clients.find(c => c.clientId === id);
    assert.ok(client, `Manca il client ${id} per la fonte ${code}`);
    assert.equal(client.enabled, true, `${id} disabilitato`);
    assert.equal(client.publicClient, false, `${id} deve essere confidential`);
    assert.equal(client.bearerOnly, false, `${id}`);
    assert.equal(client.serviceAccountsEnabled, true, `${id}: serviceAccountsEnabled`);
    assert.equal(client.standardFlowEnabled, false, `${id}: standardFlowEnabled`);
    assert.equal(client.implicitFlowEnabled, false, `${id}: implicitFlowEnabled`);
    assert.equal(client.directAccessGrantsEnabled, false, `${id}: directAccessGrantsEnabled`);
    assert.equal(client.clientAuthenticatorType, 'client-jwt', `${id}: private_key_jwt`);
    assert.ok(!('secret' in client), `${id}: nessun segreto (né chiavi né certificati) nel repository`);
    assert.deepEqual(client.redirectUris ?? [], [], `${id}: nessuna redirect URI`);
    // La chiave pubblica della fonte si registra all'installazione: JWKS da segnaposto, mai un certificato nel file.
    assert.equal(client.attributes?.['use.jwks.url'], 'true', `${id}: use.jwks.url`);
    assert.match(client.attributes?.['jwks.url'] ?? '', PLACEHOLDER, `${id}: jwks.url deve essere un segnaposto \${'{LH_…}'}`);
    assert.ok(!('jwt.credential.certificate' in (client.attributes ?? {})), `${id}: nessun certificato letterale`);
    // Audience `hub` e claim `lh_roles` arrivano dai client scope predefiniti del realm: il client non li sostituisce.
    for (const scope of ['hub-audience', 'lh-roles-scope']) {
      assert.ok((realm.defaultDefaultClientScopes ?? []).includes(scope), `${scope} non è tra gli scope predefiniti del realm`);
      if (client.defaultClientScopes) assert.ok(client.defaultClientScopes.includes(scope), `${id}: manca lo scope ${scope}`);
    }
  }
});

test('Le fonti INTERNAL del seed (ponte dei fatti, simulatore) non hanno né client né utenza di servizio', () => {
  const internal = INTERNAL_SOURCES();
  assert.ok(internal.length > 0, 'nessuna fonte INTERNAL nel seed: il controllo non verifica nulla');
  assert.ok(SEED_SOURCES().length > 0, 'nessuna fonte HTTP nel seed');
  for (const code of internal) {
    const id = `${SOURCE_PREFIX}${code}`;
    assert.ok(!realm.clients.some(c => c.clientId === id), `Il client ${id} non deve esistere: la fonte ${code} è INTERNAL e non entra da HTTP`);
    assert.ok(!(realm.users ?? []).some(u => u.serviceAccountClientId === id || u.username === `service-account-${id}`),
      `L'utenza di servizio di ${id} non deve esistere`);
  }
});

test('Nessun client di fonte senza prefisso o fuori dal registro fonti: src-<codice> ⇔ fonte del seed', () => {
  const codes = new Set(ALL_SEED_SOURCES().map(x => x.code));
  for (const c of realm.clients) {
    if (c.clientId.startsWith(SOURCE_PREFIX)) {
      assert.ok(codes.has(c.clientId.slice(SOURCE_PREFIX.length)), `Il client ${c.clientId} non corrisponde a nessuna fonte del seed`);
    } else {
      assert.ok(!codes.has(c.clientId), `Il client ${c.clientId} usa il codice della fonte senza il prefisso ${SOURCE_PREFIX}`);
    }
  }
});

test('Il ruolo SOURCE è solo delle utenze di servizio dei client src-<codice>, e queste hanno solo SOURCE', () => {
  const users = realm.users ?? [];
  for (const code of SEED_SOURCES()) {
    const id = `${SOURCE_PREFIX}${code}`;
    const account = users.find(u => u.serviceAccountClientId === id);
    assert.ok(account, `Manca l'utenza di servizio del client ${id}`);
    assert.equal(account.username, `service-account-${id}`);
    assert.equal(account.enabled, true);
    assert.deepEqual(account.realmRoles, ['SOURCE'], `${id}: l'utenza di servizio ha solo il ruolo SOURCE`);
    assert.ok(!account.credentials?.length, `${id}: nessuna credenziale sull'utenza di servizio`);
    assert.ok(!account.clientRoles || Object.keys(account.clientRoles).length === 0, `${id}: nessun ruolo di client`);
    assert.ok(!account.groups?.length, `${id}: nessun gruppo`);
  }
  for (const u of users) {
    const isSourceAccount = String(u.serviceAccountClientId ?? '').startsWith(SOURCE_PREFIX);
    if (!isSourceAccount) {
      assert.ok(!(u.realmRoles ?? []).includes('SOURCE'), `L'utente ${u.username} non è una fonte e non deve avere il ruolo SOURCE (mai una persona del backoffice)`);
    }
  }
  // SOURCE non entra in ruoli compositi né in gruppi.
  for (const r of realm.roles.realm) {
    assert.ok(!(r.composites?.realm ?? []).includes('SOURCE'), `Il ruolo ${r.name} non deve contenere SOURCE`);
  }
  // Né tra i ruoli predefiniti: li avrebbe ogni utente del realm.
  assert.ok(!(realm.defaultRole?.composites?.realm ?? []).includes('SOURCE'), 'SOURCE non deve essere nel ruolo predefinito del realm');
  assert.ok(!(realm.groups ?? []).length || !JSON.stringify(realm.groups).includes('SOURCE'), 'SOURCE non va assegnato a gruppi');
});

test('Ogni client con client-jwt e jwks.url ha use.jwks.url=true (senza, Keycloak cerca un certificato che non c\'è e il client non autentica)', () => {
  for (const c of realm.clients ?? []) {
    if (c.clientAuthenticatorType === 'client-jwt') {
      assert.equal(c.attributes?.['use.jwks.url'], 'true', `${c.clientId}: use.jwks.url`);
      assert.match(c.attributes?.['jwks.url'] ?? '', PLACEHOLDER, `${c.clientId}: jwks.url deve essere un segnaposto`);
    }
  }
});

test('Le redirect URI non contengono wildcard assolute come http://* o *', () => {
  if (realm.clients) {
    realm.clients.forEach(client => {
      if (client.redirectUris) {
        client.redirectUris.forEach(uri => {
          assert.ok(uri !== '*' && !uri.startsWith('http://*') && !uri.startsWith('https://*'), `Redirect URI non sicura trovata nel client ${client.clientId}: ${uri}`);
        });
      }
    });
  }
});

test('Client web: URI esatte del BFF per callback, ritorno dal logout e back-channel logout (Q-412)', () => {
  const web = realm.clients.find(c => c.clientId === 'web');
  assert.ok(web, 'Manca il client web');
  // Percorsi del BFF (web/lib/auth/oidc.ts e route di web/app/api/auth): corrispondenza esatta, niente /* sull'origine.
  const callback = (fs.readFileSync(path.join(ROOT, 'web/lib/auth/oidc.ts'), 'utf8')
    .match(/export const CALLBACK_PATH = "([^"]+)"/) || [])[1];
  assert.ok(callback, 'CALLBACK_PATH non trovato in web/lib/auth/oidc.ts');
  for (const route of [callback, '/api/auth/backchannel-logout']) {
    assert.ok(fs.existsSync(path.join(ROOT, 'web/app', route, 'route.ts')), `Route del BFF assente: ${route}`);
  }
  assert.deepEqual(web.redirectUris, [`\${LH_WEB_URL}${callback}`], 'redirectUris del client web');
  // Ritorno dopo il logout: handleLogout (web/lib/auth/handlers.ts) usa la radice dell'origine pubblica.
  assert.equal(web.attributes?.['post.logout.redirect.uris'], '${LH_WEB_URL}/', 'post.logout.redirect.uris del client web');
  assert.equal(web.attributes?.['backchannel.logout.url'], '${LH_WEB_URL}/api/auth/backchannel-logout');
  assert.equal(web.publicClient, false, 'il client web è confidential');
  assert.equal(web.attributes?.['pkce.code.challenge.method'], 'S256');
});

test('Lifespan access token <= 300 secondi (5 minuti)', () => {
  // Keycloak export file uses accessTokenLifespan
  const lifespan = realm.accessTokenLifespan;
  assert.ok(lifespan !== undefined, 'accessTokenLifespan mancante nel realm');
  assert.ok(lifespan <= 300, `accessTokenLifespan troppo alto: ${lifespan}`);
});

test('Ogni client scope referenziato è definito in clientScopes', () => {
  const defined = new Set((realm.clientScopes ?? []).map(s => s.name));
  const refs = [
    ...(realm.defaultDefaultClientScopes ?? []).map(n => ['defaultDefaultClientScopes', n]),
    ...(realm.defaultOptionalClientScopes ?? []).map(n => ['defaultOptionalClientScopes', n]),
  ];
  for (const c of realm.clients ?? []) {
    for (const n of c.defaultClientScopes ?? []) refs.push([`client ${c.clientId}.defaultClientScopes`, n]);
    for (const n of c.optionalClientScopes ?? []) refs.push([`client ${c.clientId}.optionalClientScopes`, n]);
  }
  // Con un array clientScopes esplicito Keycloak non crea i propri scope predefiniti (profile, email, roles…):
  // un riferimento a uno scope non definito lo perde in silenzio (niente preferred_username nel token).
  const missing = refs.filter(([, n]) => !defined.has(n)).map(([where, n]) => `${where}: ${n}`);
  assert.deepEqual(missing, [], `Client scope referenziati ma non definiti:\n${missing.join('\n')}`);
  for (const std of ['profile', 'email', 'roles', 'web-origins', 'acr', 'basic', 'role_list', 'offline_access']) {
    assert.ok(defined.has(std), `Manca lo scope standard ${std}`);
  }
});

test('Nessun segreto letterale (secret, clientSecret, bindCredential) in realm.json e nell\'overlay di prova', () => {
  for (const file of [REALM_PATH, OVERLAY_PATH]) {
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const [where, value] of secretValues(doc)) {
      assert.ok(typeof value === 'string' && PLACEHOLDER.test(value),
        `${path.relative(ROOT, file)} ${where}: valore letterale, usare un segnaposto \${VAR}`);
    }
  }
});

test('Ogni segnaposto ${LH_*} di realm.json è passato al servizio idp nel compose', () => {
  const vars = new Set([...fs.readFileSync(REALM_PATH, 'utf8').matchAll(/\$\{(LH_[A-Z0-9_]+)\}/g)].map(m => m[1]));
  const compose = fs.readFileSync(COMPOSE_PATH, 'utf8');
  const block = compose.match(/\n  idp:\n([\s\S]*?)(?=\n  [a-z][\w-]*:\n)/);
  assert.ok(block, 'Servizio idp non trovato in deploy/docker-compose.yml');
  const missing = [...vars].filter(v => !new RegExp(`^\\s+${v}:`, 'm').test(block[1]));
  assert.deepEqual(missing, [], `Variabili non passate a idp (Keycloak lascerebbe il segnaposto e l'avvio fallisce): ${missing.join(', ')}`);
});
