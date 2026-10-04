#!/usr/bin/env node
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REALM_PATH = path.join(ROOT, 'deploy/idp/realm.json');
// Realm dei membri del portale e dei widget (ADR-051 decisioni 6 e 7), nella stessa istanza di Keycloak.
const MEMBERS_REALM_PATH = path.join(ROOT, 'deploy/idp/realm-members.json');
const REFERENCE_COMPOSE_PATH = path.join(ROOT, 'deploy/compose/reference.yml');
const OVERLAY_PATH = path.join(ROOT, 'deploy/idp/test-idp/realm-test-overlay.json');
const APPLY_OVERLAY_PATH = path.join(ROOT, 'deploy/idp/test-idp/apply-overlay.sh');
const VETRINA_OVERLAY_PATH = path.join(ROOT, 'deploy/idp/vetrina/realm-vetrina-overlay.json');
const VETRINA_APPLY_PATH = path.join(ROOT, 'deploy/idp/vetrina/apply-overlay.sh');
// Overlay di vetrina del realm dei membri e del realm master (ADR-051 decisioni 1 e 10; Q-671, Q-672, Q-677).
const VETRINA_MEMBERS_PATH = path.join(ROOT, 'deploy/idp/vetrina/realm-members-vetrina-overlay.json');
const VETRINA_MASTER_PATH = path.join(ROOT, 'deploy/idp/vetrina/master.json');
const BOOTSTRAP_PATH = path.join(ROOT, 'deploy/idp/bootstrap.sh');
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

// Ruolo predefinito del realm (composito assegnato a ogni utente creato dopo l'import): l'entry di `roles.realm` con il
// nome indicato da `defaultRole`. Solo questa porta i composti nell'import di Keycloak (vedi il test sul ruolo MEMBER).
const DEFAULT_ROLE = 'default-roles-loyaltyhub';
const defaultRoleEntry = () => realm.roles.realm.find(r => r.name === DEFAULT_ROLE);

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
  assert.ok(!(defaultRoleEntry()?.composites?.realm ?? []).includes('SOURCE'), 'SOURCE non deve essere nel composito di default-roles-loyaltyhub (roles.realm)');
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

test('Nessun segreto letterale (secret, clientSecret, bindCredential) nei due realm e negli overlay (prova e vetrina)', () => {
  for (const file of [REALM_PATH, MEMBERS_REALM_PATH, OVERLAY_PATH, VETRINA_OVERLAY_PATH]) {
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const [where, value] of secretValues(doc)) {
      assert.ok(typeof value === 'string' && PLACEHOLDER.test(value),
        `${path.relative(ROOT, file)} ${where}: valore letterale, usare un segnaposto \${VAR}`);
    }
  }
});

// L'overlay di prova (solo prova, F2-IAM-04) non è importato da Keycloak: lo applica apply-overlay.sh, che sostituisce
// i segnaposto solo per le variabili di OVERLAY_VARS e si ferma davanti a qualunque altro ${...} o a una variabile
// mancante. Una variabile nell'overlay e non nell'elenco (o il contrario) si scoprirebbe solo applicando l'overlay.
test('Ogni segnaposto ${LH_*} dell\'overlay di prova è in OVERLAY_VARS di apply-overlay.sh, e viceversa', () => {
  const overlayVars = new Set([...fs.readFileSync(OVERLAY_PATH, 'utf8').matchAll(/\$\{([A-Z0-9_]+)\}/g)].map(m => m[1]));
  const list = fs.readFileSync(APPLY_OVERLAY_PATH, 'utf8').match(/^OVERLAY_VARS=\(\n([\s\S]*?)^\)/m);
  assert.ok(list, 'OVERLAY_VARS non trovato in apply-overlay.sh');
  const scriptVars = new Set(list[1].split('\n').map(l => l.trim()).filter(Boolean));
  assert.deepEqual([...overlayVars].sort(), [...scriptVars].sort(), 'segnaposto dell\'overlay e OVERLAY_VARS di apply-overlay.sh devono coincidere');
});

// Il membro di prova (Q-557, ADR-048 decisione 11) è l'utente con cui verify.sh controlla il token di un membro
// registrato: ruolo predefinito del realm (il cui composito è MEMBER) e nient'altro, e-mail non verificata, nessuna
// azione richiesta (senza SMTP la verifica dell'e-mail bloccherebbe il grant password). Un utente creato con
// partialImport non riceve il ruolo predefinito da solo (verificato con Keycloak 26.7.4), quindi lo dichiara.
// La password segue la convenzione dell'overlay: segnaposto sostituito da apply-overlay.sh, mai un valore nel file.
test('Overlay di prova: il membro di prova è come un membro registrato e le sue credenziali sono segnaposti (Q-557)', () => {
  const overlay = JSON.parse(fs.readFileSync(OVERLAY_PATH, 'utf8'));
  const users = overlay.users ?? [];
  assert.ok(users.length > 0, 'L\'overlay non ha utenti: manca il membro di prova che verify.sh usa');
  for (const u of users) {
    for (const c of u.credentials ?? []) {
      assert.ok(typeof c.value === 'string' && PLACEHOLDER.test(c.value),
        `overlay, utente ${u.username}: password letterale, usare un segnaposto \${VAR} (nessun segreto nel repository)`);
      assert.equal(c.temporary, false, `overlay, utente ${u.username}: la password di prova non è temporanea (il grant password fallirebbe)`);
    }
  }
  const member = users.find(u => u.username === 'testmember');
  assert.ok(member, 'Manca l\'utente testmember (il membro di prova di verify.sh)');
  assert.equal(member.enabled, true);
  assert.equal(member.emailVerified, false, 'emailVerified deve essere false, dichiarato: senza SMTP l\'e-mail non è mai verificata (Q-557)');
  assert.deepEqual(member.realmRoles, [DEFAULT_ROLE], 'Il membro di prova ha solo il ruolo predefinito, come un account registrato: MEMBER gli arriva dal composito');
  assert.ok(!member.serviceAccountClientId, 'Il membro di prova non è un\'utenza di servizio');
  assert.deepEqual(member.requiredActions ?? [], [], 'Nessuna azione richiesta (VERIFY_EMAIL, UPDATE_PASSWORD…): il grant password non riuscirebbe');
  assert.equal(users.filter(u => u.username === 'testmember').length, 1);
});

test('Ogni segnaposto ${LH_*} dei due realm è passato al servizio idp nei due compose, che importano entrambi i realm', () => {
  const vars = new Set([REALM_PATH, MEMBERS_REALM_PATH]
    .flatMap(f => [...fs.readFileSync(f, 'utf8').matchAll(/\$\{(LH_[A-Z0-9_]+)\}/g)].map(m => m[1])));
  assert.ok(vars.has('LH_PORTAL_CLIENT_SECRET'), 'il realm dei membri usa LH_PORTAL_CLIENT_SECRET');
  for (const file of [COMPOSE_PATH, REFERENCE_COMPOSE_PATH]) {
    const compose = fs.readFileSync(file, 'utf8');
    const block = compose.match(/\n  idp:\n([\s\S]*?)(?=\n  [a-z][\w-]*:\n)/);
    assert.ok(block, `Servizio idp non trovato in ${path.relative(ROOT, file)}`);
    const missing = [...vars].filter(v => !new RegExp(`^\\s+${v}:`, 'm').test(block[1]));
    assert.deepEqual(missing, [], `${path.relative(ROOT, file)}: variabili non passate a idp (Keycloak lascerebbe il segnaposto e l'avvio fallisce): ${missing.join(', ')}`);
    // Il segreto del client portal è obbligatorio come gli altri segreti dei client (guardia x-lh-require-env).
    assert.match(block[1], /LH_REQUIRED_ENV: "[^"]*\bLH_PORTAL_CLIENT_SECRET\b/, `${path.relative(ROOT, file)}: LH_PORTAL_CLIENT_SECRET non obbligatoria`);
    // Keycloak rifiuta un file <nome>-realm.json che contiene un realm diverso: il nome del file segue il realm.
    assert.match(block[1], /realm-members\.json:\/opt\/keycloak\/data\/import\/loyaltyhub-members-realm\.json:ro/, `${path.relative(ROOT, file)}: realm dei membri non importato`);
  }
});

// Auto-registrazione dei membri (Q-557, D10, ADR-048, F2-IAM-03, docs/09 PT-16): registrazione aperta, senza verifica
// dell'e-mail. La verifica richiederebbe un `smtpServer`, cioè una nuova destinazione di rete in uscita (CLAUDE.md
// §7 «Fermati e chiedi»): si abilita solo con un'ADR, dopo il modulo `delivery` (M8.4). Senza verifica `email_verified`
// resta falso e nessun collegamento tra un account e un membro può fondarsi sull'e-mail (Q-558).
// Con ADR-051 i membri si registrano nel loro realm (`loyaltyhub-members`, test sotto): il realm degli operatori ha la
// registrazione chiusa. Resta senza verifica dell'e-mail e senza SMTP, come quello dei membri.
function assertNoEmailVerification(r, name) {
  assert.ok('verifyEmail' in r, `${name}: verifyEmail deve essere dichiarato in modo esplicito (default di Keycloak implicito = decisione nascosta)`);
  assert.equal(r.verifyEmail, false, `${name}: verifyEmail: true richiede un smtpServer (nuova destinazione di rete): serve un'ADR (Q-557)`);
  const smtp = r.smtpServer;
  assert.ok(smtp === undefined || (typeof smtp === 'object' && smtp !== null && Object.keys(smtp).length === 0),
    `${name}: smtpServer non deve essere configurato: nuova destinazione di rete in uscita, serve un'ADR (CLAUDE.md §7, Q-557)`);
  // Un'azione richiesta di default «Verify Email» farebbe la stessa cosa a ogni nuovo utente, senza SMTP li bloccherebbe.
  for (const a of r.requiredActions ?? []) {
    if (a.alias === 'VERIFY_EMAIL' || a.providerId === 'VERIFY_EMAIL') {
      assert.ok(!(a.enabled && a.defaultAction), `${name}: VERIFY_EMAIL non deve essere un'azione richiesta di default (serve SMTP)`);
    }
  }
}

test('Realm degli operatori: registrazione chiusa (i membri si registrano nel loro realm, ADR-051), niente SMTP (Q-557)', () => {
  assert.equal(realm.registrationAllowed, false, 'registrationAllowed deve essere false nel realm degli operatori: i membri si registrano in loyaltyhub-members (ADR-051)');
  assertNoEmailVerification(realm, 'realm.json');
  assert.ok(!realm.clients.some(c => c.clientId === 'widgets' || c.clientId === 'portal'), 'widgets e portal stanno nel realm dei membri (ADR-051)');
});

// Il ruolo predefinito. Verificato con l'import reale di Keycloak 26.7.4 (kc.sh import + export):
//  - i composti valgono solo se stanno nell'entry di `roles.realm`; quelli dentro `defaultRole` sono ignorati in silenzio;
//  - senza `defaultRole` l'entry di `roles.realm` con lo stesso nome fa creare a Keycloak un secondo ruolo
//    `default-roles-loyaltyhub-1` come ruolo predefinito, e MEMBER non arriva a nessuno;
//  - `offline_access`, `uma_authorization` e i ruoli del client `account` li aggiunge Keycloak da sé all'import;
//  - gli utenti del file NON ricevono il ruolo predefinito (solo i `realmRoles` che elencano); lo ricevono gli utenti
//    creati dopo (registrazione, console di amministrazione, federazione LDAP) e le utenze di servizio create da Keycloak.
test('MEMBER è nel composito del ruolo predefinito, e solo lui (Q-557)', () => {
  assert.equal(realm.defaultRole?.name, DEFAULT_ROLE, 'defaultRole deve nominare default-roles-loyaltyhub (senza, Keycloak crea default-roles-loyaltyhub-1)');
  assert.ok(!('composites' in realm.defaultRole), 'defaultRole non porta composites: Keycloak li ignora, valgono quelli di roles.realm');
  const entry = defaultRoleEntry();
  assert.ok(entry, `Manca l'entry ${DEFAULT_ROLE} in roles.realm: senza, MEMBER non entra nel composito`);
  assert.equal(entry.composite, true);
  // Solo MEMBER: offline_access, uma_authorization e i ruoli di account li aggiunge Keycloak (non vanno ripetuti qui).
  assert.deepEqual(entry.composites?.realm, ['MEMBER'], 'Il composito di default-roles-loyaltyhub nel file è solo MEMBER');
  assert.ok(!entry.composites?.client || Object.keys(entry.composites.client).length === 0, 'Nessun ruolo di client nel composito del file');
  // Ogni utente creato dopo l'import lo riceve: nessun ruolo operatore, MFA o SOURCE tra i predefiniti.
  const NOT_DEFAULT = ['ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST', 'SOURCE', 'MFA_REQUIRED_ROLE'];
  for (const r of NOT_DEFAULT) {
    assert.ok(!(entry.composites?.realm ?? []).includes(r), `${r} non deve essere tra i ruoli predefiniti (lo avrebbe ogni membro registrato)`);
  }
  // Il nome del ruolo predefinito non è assegnato ad altri ruoli come composito (né MEMBER lo contiene: niente cicli).
  for (const r of realm.roles.realm) {
    if (r.name !== DEFAULT_ROLE) assert.ok(!(r.composites?.realm ?? []).includes(DEFAULT_ROLE), `${r.name} non deve contenere ${DEFAULT_ROLE}`);
  }
  // Il ruolo MEMBER esiste ed è definito una sola volta.
  assert.equal(realm.roles.realm.filter(r => r.name === 'MEMBER').length, 1);
});

// Un client con service account non dichiarato tra gli `users` riceve da Keycloak un'utenza creata «come un utente
// qualunque», quindi con il ruolo predefinito: dopo Q-557 avrebbe MEMBER (un job o una fonte non è un membro).
// Dichiararla con `realmRoles` esplicito (anche vuoto) evita il ruolo predefinito: verificato con l'import reale.
test('Ogni client con service account ha la sua utenza nel file, con ruoli espliciti e mai quelli predefiniti (Q-557)', () => {
  const accountClients = (realm.clients ?? []).filter(c => c.serviceAccountsEnabled);
  assert.ok(accountClients.length > 0, 'nessun client con service account: il controllo non verifica nulla');
  for (const c of accountClients) {
    const account = (realm.users ?? []).find(u => u.serviceAccountClientId === c.clientId);
    assert.ok(account, `Manca l'utenza di servizio di ${c.clientId}: Keycloak la creerebbe con il ruolo predefinito (quindi con MEMBER)`);
    assert.equal(account.username, `service-account-${c.clientId}`);
    assert.ok(Array.isArray(account.realmRoles), `${c.clientId}: realmRoles esplicito (anche vuoto)`);
    for (const forbidden of ['MEMBER', DEFAULT_ROLE]) {
      assert.ok(!account.realmRoles.includes(forbidden), `${c.clientId}: l'utenza di servizio non deve avere ${forbidden}`);
    }
  }
});

// ---------------------------------------------------------------------------------------------------------------------
// Realm dei membri `loyaltyhub-members` (ADR-051 decisioni 6 e 7, F2-IAM-01, F2-IAM-03, docs/18 M8.14 R2). Stessa istanza
// di Keycloak, nessun legame con il realm degli operatori: solo MEMBER, client `portal` (BFF) e `widgets`, registrazione
// aperta, passkey disponibili, flusso di login standard. Nessun utente né credenziale.
// ---------------------------------------------------------------------------------------------------------------------
const members = () => JSON.parse(fs.readFileSync(MEMBERS_REALM_PATH, 'utf8'));
const MEMBERS_DEFAULT_ROLE = 'default-roles-loyaltyhub-members';

test('Realm dei membri: nome, nessun utente, nessuna credenziale, nessun broker né federazione (ADR-051 decisione 7)', () => {
  const m = members();
  assert.equal(m.realm, 'loyaltyhub-members');
  assert.equal(m.id, 'loyaltyhub-members');
  assert.equal(m.enabled, true);
  assert.deepEqual(m.users ?? [], [], 'nessun utente nel realm dei membri');
  assert.ok(!JSON.stringify(m).includes('"credentials"'), 'nessuna credenziale');
  for (const k of ['identityProviders', 'identityProviderMappers', 'components', 'groups', 'smtpServer']) {
    const v = m[k];
    assert.ok(v === undefined || (Array.isArray(v) ? v.length === 0 : Object.keys(v).length === 0), `il realm dei membri non deve avere ${k} (nessun broker, nessuna federazione, nessun SMTP)`);
  }
  assert.doesNotMatch(JSON.stringify(m), /"loyaltyhub"(?!-)/, 'nessun riferimento al realm degli operatori');
});

test('Realm dei membri: MEMBER è l\'unico ruolo applicativo ed è nel ruolo predefinito', () => {
  const m = members();
  const names = m.roles.realm.map(r => r.name).sort();
  assert.deepEqual(names, ['MEMBER', MEMBERS_DEFAULT_ROLE].sort(), 'solo MEMBER e il ruolo predefinito: nessun ruolo operatore, SOURCE o MFA_REQUIRED_ROLE');
  assert.deepEqual(m.roles.client ?? {}, {}, 'nessun ruolo di client');
  assert.equal(m.defaultRole?.name, MEMBERS_DEFAULT_ROLE);
  assert.ok(!('composites' in m.defaultRole), 'defaultRole non porta composites: Keycloak li ignora, valgono quelli di roles.realm');
  const entry = m.roles.realm.find(r => r.name === MEMBERS_DEFAULT_ROLE);
  assert.equal(entry.composite, true);
  assert.deepEqual(entry.composites, { realm: ['MEMBER'] }, 'il ruolo predefinito contiene solo MEMBER');
});

test('Realm dei membri: client portal confidential per il BFF, Authorization Code con PKCE S256 e i percorsi /members', () => {
  const m = members();
  assert.deepEqual(m.clients.map(c => c.clientId).sort(), ['portal', 'widgets'], 'solo i client portal e widgets');
  const c = m.clients.find(x => x.clientId === 'portal');
  assert.equal(c.enabled, true);
  assert.equal(c.publicClient, false, 'portal è confidential');
  assert.equal(c.bearerOnly, false);
  assert.equal(c.clientAuthenticatorType, 'client-secret');
  assert.equal(c.secret, '${LH_PORTAL_CLIENT_SECRET}', 'segreto da segnaposto, mai un valore');
  assert.equal(c.standardFlowEnabled, true);
  for (const k of ['implicitFlowEnabled', 'directAccessGrantsEnabled', 'serviceAccountsEnabled', 'frontchannelLogout']) {
    assert.equal(c[k], false, `portal: ${k} deve essere false`);
  }
  assert.ok(!(c.attributes?.['oauth2.device.authorization.grant.enabled'] === 'true'), 'portal: nessun device grant');
  // Percorsi del BFF per il realm dei membri (ADR-051): callback e back-channel logout con /members, ritorno alla radice.
  assert.deepEqual(c.redirectUris, ['${LH_WEB_URL}/api/auth/callback/members']);
  assert.equal(c.attributes?.['post.logout.redirect.uris'], '${LH_WEB_URL}/');
  assert.equal(c.attributes?.['backchannel.logout.url'], '${LH_WEB_URL}/api/auth/backchannel-logout/members');
  assert.equal(c.attributes?.['backchannel.logout.session.required'], 'true');
  assert.equal(c.attributes?.['pkce.code.challenge.method'], 'S256');
  // Stesse impostazioni del client web del realm degli operatori, salvo nome, segreto e percorsi.
  const web = realm.clients.find(x => x.clientId === 'web');
  for (const k of ['standardFlowEnabled', 'implicitFlowEnabled', 'directAccessGrantsEnabled', 'serviceAccountsEnabled', 'publicClient', 'consentRequired', 'protocol']) {
    assert.equal(c[k], web[k], `portal.${k} come web.${k}`);
  }
  assert.deepEqual(c.webOrigins, web.webOrigins);
});

test('Realm dei membri: il token di portal porta audience hub e il claim lh_roles, con gli scope definiti nel realm', () => {
  const m = members();
  const c = m.clients.find(x => x.clientId === 'portal');
  for (const scope of ['hub-audience', 'lh-roles-scope']) {
    assert.ok(c.defaultClientScopes.includes(scope), `portal: manca lo scope predefinito ${scope}`);
    assert.ok(m.defaultDefaultClientScopes.includes(scope), `${scope} non è tra gli scope predefiniti del realm dei membri`);
    // Stessa definizione del realm degli operatori: stesso aud e stesso claim, che l'hub verifica (ADR-051 decisione 6).
    assert.deepEqual(m.clientScopes.find(s => s.name === scope), realm.clientScopes.find(s => s.name === scope), `${scope} diverso dal realm degli operatori`);
  }
  const aud = m.clientScopes.find(s => s.name === 'hub-audience').protocolMappers[0];
  assert.equal(aud.config['included.client.audience'], 'hub');
  assert.equal(aud.config['access.token.claim'], 'true');
  const roles = m.clientScopes.find(s => s.name === 'lh-roles-scope').protocolMappers[0];
  assert.equal(roles.config['claim.name'], 'lh_roles');
  assert.equal(roles.config['access.token.claim'], 'true');
  const defined = new Set(m.clientScopes.map(s => s.name));
  const refs = [...m.defaultDefaultClientScopes, ...m.defaultOptionalClientScopes,
    ...m.clients.flatMap(x => [...(x.defaultClientScopes ?? []), ...(x.optionalClientScopes ?? [])])];
  assert.deepEqual(refs.filter(n => !defined.has(n)), [], 'client scope referenziati ma non definiti nel realm dei membri');
  for (const std of ['profile', 'email', 'roles', 'web-origins', 'acr', 'basic']) assert.ok(c.defaultClientScopes.includes(std), `portal: manca lo scope standard ${std}`);
});

test('Realm dei membri: widgets spostato qui dal realm degli operatori, con la stessa definizione', () => {
  const w = members().clients.find(x => x.clientId === 'widgets');
  assert.ok(w, 'manca il client widgets');
  assert.equal(w.secret, '${LH_WIDGETS_CLIENT_SECRET}');
  assert.equal(w.publicClient, false);
  for (const k of ['standardFlowEnabled', 'implicitFlowEnabled', 'directAccessGrantsEnabled', 'serviceAccountsEnabled']) assert.equal(w[k], false, `widgets: ${k}`);
  assert.deepEqual(w.redirectUris, []);
});

test('Realm dei membri: registrazione aperta senza verifica dell\'e-mail né SMTP, passkey disponibili, login standard', () => {
  const m = members();
  assert.equal(m.registrationAllowed, true, 'i membri si registrano da soli (PT-16, ADR-051)');
  assertNoEmailVerification(m, 'realm-members.json');
  assert.equal(m.browserFlow, 'browser', 'flusso di login standard: niente OTP obbligatorio per i membri');
  assert.ok(!('authenticationFlows' in m), 'nessun flusso personalizzato');
  assert.ok(!m.roles.realm.some(r => r.name === 'MFA_REQUIRED_ROLE'));
  // Passkey: politica WebAuthn passwordless con chiave residente e verifica dell'utente; azione richiesta abilitata, non predefinita.
  assert.equal(m.webAuthnPolicyPasswordlessRequireResidentKey, 'Yes');
  assert.equal(m.webAuthnPolicyPasswordlessUserVerificationRequirement, 'required');
  assert.ok(m.webAuthnPolicyPasswordlessSignatureAlgorithms.includes('ES256'));
  const passkey = (m.requiredActions ?? []).find(a => a.alias === 'webauthn-register-passwordless');
  assert.ok(passkey && passkey.enabled === true && passkey.defaultAction === false, 'webauthn-register-passwordless abilitata e non predefinita');
  for (const a of m.requiredActions) assert.equal(a.defaultAction, false, `${a.alias}: nessuna azione richiesta di default`);
});

test('Realm dei membri: tentativi, sessioni, token, rotazione dei refresh token ed eventi come nel realm degli operatori', () => {
  const m = members();
  for (const k of ['bruteForceProtected', 'sslRequired', 'ssoSessionIdleTimeout', 'ssoSessionMaxLifespan', 'accessTokenLifespan',
    'revokeRefreshToken', 'refreshTokenMaxReuse', 'eventsEnabled', 'adminEventsEnabled']) {
    assert.ok(k in m, `${k} dichiarato`);
    assert.equal(m[k], realm[k], `${k} come nel realm degli operatori`);
  }
  assert.equal(m.bruteForceProtected, true);
  assert.ok(m.accessTokenLifespan <= 300);
  assert.equal(m.revokeRefreshToken, true);
  assert.equal(m.eventsEnabled, true);
  assert.equal(m.adminEventsEnabled, true);
});

// Utenti di test (Q-676, ADR-051 decisione 1): il ruolo LH_TEST_USER e le credenziali degli utenti di test esistono solo
// negli overlay di vetrina (fetta V8), mai nei realm di base importati da ogni installazione. Hub e web rifiutano un
// utente con LH_TEST_USER salvo LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test.
test('Realm di base (operatori e membri): nessun ruolo LH_TEST_USER e nessuna credenziale di utente (Q-676)', () => {
  for (const file of [REALM_PATH, MEMBERS_REALM_PATH]) {
    const name = path.relative(ROOT, file);
    const r = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.ok(!JSON.stringify(r).includes('LH_TEST_USER'), `${name}: il ruolo LH_TEST_USER sta solo negli overlay di vetrina`);
    for (const u of r.users ?? []) {
      assert.ok(!u.credentials?.length, `${name}: l'utente ${u.username} ha credenziali (solo negli overlay di vetrina)`);
      assert.ok(!(u.realmRoles ?? []).includes('LH_TEST_USER'), `${name}: ${u.username} con LH_TEST_USER`);
    }
    assert.ok(!/"(credentials|password|hashedSaltedValue|secretData|credentialData)"\s*:/.test(fs.readFileSync(file, 'utf8')), `${name}: credenziali nel file`);
  }
});

// ---------------------------------------------------------------------------------------------------------------------
// Overlay di vetrina (F2-IAM-01, F2-IAM-03, ADR-048, ADR-049; Q-618, Q-619, Q-626: decise il 2026-09-30).
// La vetrina enterprise ospitata (docs/18 M8.14, V2) applica questo overlay al realm già avviato con
// deploy/idp/vetrina/apply-overlay.sh. Il realm base e l'overlay di prova restano invariati: qui si verifica che
// l'overlay chiuda la registrazione, non porti credenziali né utenti, non tocchi MFA e UPDATE_PASSWORD degli
// operatori e che l'unico client aggiunto (lh-cli, CLI dell'operatore) sia pubblico, solo Device Authorization Grant.
// ---------------------------------------------------------------------------------------------------------------------
const vetrina = () => JSON.parse(fs.readFileSync(VETRINA_OVERLAY_PATH, 'utf8'));
const OVERLAY_META_KEYS = ['_comment', 'realm', 'clients', 'scopeMappings', 'roles', 'users'];
const OPERATOR_ROLES = ['ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST'];

test('Overlay di vetrina: solo impostazioni del realm ammesse, registrazione chiusa (Q-619) ed eventi di amministrazione con dettagli (Q-677)', () => {
  const o = vetrina();
  assert.equal(o.realm, 'loyaltyhub');
  assert.equal(o.registrationAllowed, false, 'registrationAllowed deve essere false nella vetrina: portale membri chiuso nel primo passo (Q-619)');
  // Il realm base ha già la registrazione chiusa (ADR-051): l'overlay la ribadisce, senza effetti sul realm dei membri.
  assert.equal(realm.registrationAllowed, false, 'il realm degli operatori ha la registrazione chiusa (ADR-051)');
  assert.equal(o.adminEventsEnabled, true);
  assert.equal(o.adminEventsDetailsEnabled, true, 'eventi di amministrazione con la rappresentazione (Q-677)');
  // Nessun flusso, azione richiesta, broker, componente o SMTP: l'overlay non tocca l'autenticazione né allarga l'accesso.
  const settings = Object.keys(o).filter(k => !OVERLAY_META_KEYS.includes(k)).sort();
  assert.deepEqual(settings, ['adminEventsDetailsEnabled', 'adminEventsEnabled', 'registrationAllowed'], `l'overlay di vetrina ha chiavi non ammesse: ${settings.join(', ')}`);
  for (const forbidden of ['groups', 'authenticationFlows', 'browserFlow', 'requiredActions', 'identityProviders', 'components', 'smtpServer', 'verifyEmail', 'defaultRole']) {
    assert.ok(!(forbidden in o), `l'overlay di vetrina non deve contenere ${forbidden}`);
  }
  // Il solo ruolo nuovo è LH_TEST_USER (ruolo di realm); i ruoli applicativi sono quelli del realm base.
  assert.deepEqual((o.roles?.realm ?? []).map(r => r.name), ['LH_TEST_USER']);
  assert.deepEqual(Object.keys(o.roles), ['realm']);
});

// Credenziali di test PUBBLICHE e fisse (ADR-051 decisione 1): esistono solo negli overlay di vetrina, mai nel realm base.
function rawSecrets(o) {
  const out = [];
  const walk = (n, at = '$') => {
    if (Array.isArray(n)) n.forEach((v, i) => walk(v, `${at}[${i}]`));
    else if (n && typeof n === 'object') {
      for (const [k, v] of Object.entries(n)) {
        if (/^(clientSecret|secret|bindCredential|privateKey|jwks\.string|jwt\.credential\.certificate)$/i.test(k)) out.push(`${at}.${k}`);
        walk(v, `${at}.${k}`);
      }
    }
  };
  walk(o);
  return out;
}

test('Overlay di vetrina: nessun segreto vero né segnaposto; le sole credenziali sono password fisse e OTP degli utenti di test (ADR-051)', () => {
  for (const file of [VETRINA_OVERLAY_PATH, VETRINA_MEMBERS_PATH, VETRINA_MASTER_PATH]) {
    const raw = fs.readFileSync(file, 'utf8');
    const o = JSON.parse(raw);
    assert.ok(!raw.includes('${'), `${path.basename(file)}: nessun segnaposto \${...}: nessun segreto da sostituire`);
    assert.deepEqual(secretValues(o), [], `${path.basename(file)}: nessuna chiave secret/clientSecret/bindCredential`);
    assert.deepEqual(rawSecrets(o), [], `${path.basename(file)}: chiavi di segreto vere (client, chiavi private, certificati)`);
    assert.ok(!raw.includes('testmember'), 'nessun utente di prova (testmember è dell\'overlay di prova)');
  }
  assert.ok(!('users' in JSON.parse(fs.readFileSync(VETRINA_MASTER_PATH, 'utf8'))), 'master.json: nessun utente');
});

test('Overlay di vetrina: il solo client è lh-cli, pubblico, solo Device Authorization Grant, senza segreto né service account (Q-626)', () => {
  const o = vetrina();
  assert.deepEqual((o.clients ?? []).map(c => c.clientId), ['lh-cli'], 'l\'overlay di vetrina aggiunge solo lh-cli (non ridefinisce web né altri client)');
  const c = o.clients[0];
  assert.equal(c.enabled, true);
  assert.equal(c.publicClient, true, 'lh-cli è un client pubblico: nessun segreto da custodire sul computer dell\'operatore');
  assert.equal(c.bearerOnly, false);
  assert.equal(c.attributes?.['oauth2.device.authorization.grant.enabled'], 'true', 'Device Authorization Grant attivo');
  for (const k of ['standardFlowEnabled', 'implicitFlowEnabled', 'directAccessGrantsEnabled', 'serviceAccountsEnabled']) {
    assert.equal(c[k], false, `lh-cli: ${k} deve essere false (nessun direct access grant, nessun service account: i ruoli arrivano solo dall'utente)`);
  }
  assert.ok(!('secret' in c) && !('clientAuthenticatorType' in c), 'lh-cli: nessun segreto né autenticazione di client');
  assert.deepEqual(c.redirectUris, [], 'lh-cli: nessuna redirect URI');
  assert.deepEqual(c.webOrigins ?? [], [], 'lh-cli: nessuna web origin');
  assert.equal(c.attributes?.['pkce.code.challenge.method'], 'S256', 'PKCE S256');
  // Token di breve durata: non oltre il realm (300 s) e sessione del client limitata.
  assert.ok(Number(c.attributes?.['access.token.lifespan']) <= 300, 'access token di lh-cli oltre 300 secondi');
  assert.ok(Number(c.attributes?.['oauth2.device.code.lifespan']) <= 600, 'codice del dispositivo di lh-cli troppo longevo');
  assert.ok(Number(c.attributes?.['client.session.max.lifespan']) > 0 && Number(c.attributes?.['client.session.max.lifespan']) <= 3600, 'sessione massima di lh-cli: al più un\'ora');
  assert.ok(Number(c.attributes?.['client.session.idle.timeout']) > 0 && Number(c.attributes?.['client.session.idle.timeout']) <= 900, 'inattività di lh-cli: al più 15 minuti');
  // Nessun token offline (refresh senza scadenza): niente scope offline_access.
  assert.deepEqual(c.optionalClientScopes ?? [], [], 'lh-cli: nessuno scope opzionale (offline_access darebbe token di lunga durata)');
  assert.ok(!(c.defaultClientScopes ?? []).includes('offline_access'), 'lh-cli: offline_access non è tra gli scope predefiniti');
  // I ruoli del token vengono solo dall'utente: audience hub e claim lh_roles come gli altri client, definiti nel realm base.
  const defined = new Set(realm.clientScopes.map(s => s.name));
  for (const scope of c.defaultClientScopes ?? []) assert.ok(defined.has(scope), `lh-cli: lo scope ${scope} non è definito in realm.json`);
  for (const scope of ['hub-audience', 'lh-roles-scope']) assert.ok((c.defaultClientScopes ?? []).includes(scope), `lh-cli: manca lo scope ${scope}`);
  assert.ok(!('authenticationFlowBindingOverrides' in c), 'lh-cli usa il flusso del realm (browser-mfa): nessuna deroga alla MFA');
  // Scelta esplicita (Q-626): nel token solo i ruoli operatore dell'utente, non quelli tecnici (offline_access, uma_authorization, MEMBER).
  assert.equal(c.fullScopeAllowed, false, 'lh-cli: fullScopeAllowed deve essere false (ruoli del token limitati a quelli operatore)');
  assert.deepEqual(o.scopeMappings, [{ client: 'lh-cli', roles: OPERATOR_ROLES }], 'scopeMappings: lh-cli limitato ai soli ruoli operatore');
  for (const role of o.scopeMappings[0].roles) assert.ok(realm.roles.realm.some(r => r.name === role), `scopeMappings: il ruolo ${role} non è definito in realm.json`);
  // Rischio del Device Authorization Grant (RFC 8628 par. 5.4, phishing del codice): l'operatore deve vedere chi chiede l'accesso.
  assert.equal(c.consentRequired, true, 'lh-cli: consentRequired deve essere true (schermata di consenso che nomina il client)');
  assert.equal(realm.clients.filter(x => x.clientId === 'lh-cli').length, 0, 'lh-cli esiste solo nell\'overlay di vetrina, non nel realm base (né nell\'overlay di prova)');
  const testOverlay = JSON.parse(fs.readFileSync(OVERLAY_PATH, 'utf8'));
  assert.ok(!(testOverlay.clients ?? []).some(x => x.clientId === 'lh-cli'), 'lh-cli non va nell\'overlay di prova');
  // Un solo client con device grant nell'intero realm base: nessun client di produzione lo attiva.
  for (const x of realm.clients) {
    assert.notEqual(x.attributes?.['oauth2.device.authorization.grant.enabled'], 'true', `${x.clientId}: il device grant è solo di lh-cli, nell'overlay di vetrina`);
  }
});

test('Overlay di vetrina: il client web e le variabili LH_WEB_URL restano quelli del realm base (nessuna ridefinizione)', () => {
  assert.ok(!vetrina().clients.some(c => c.clientId === 'web'), 'l\'overlay non ridefinisce il client web');
  const web = realm.clients.find(c => c.clientId === 'web');
  assert.ok(web.redirectUris.every(u => u.startsWith('${LH_WEB_URL}/')), 'redirect URI del client web da LH_WEB_URL');
  assert.ok(String(web.attributes['post.logout.redirect.uris']).startsWith('${LH_WEB_URL}'), 'ritorno dal logout da LH_WEB_URL');
  assert.equal(web.directAccessGrantsEnabled, false);
  assert.ok(!(web.attributes?.['oauth2.device.authorization.grant.enabled'] === 'true'));
});

// Q-618 (decisa il 2026-09-30): account operatore nominativi creati a mano, nessuna password pubblicata. Gli utenti del
// realm base non hanno credenziali e restano con UPDATE_PASSWORD e con il ruolo che attiva la MFA; il flusso browser
// con MFA condizionale (conditional-user-role MFA_REQUIRED_ROLE → OTP) resta quello del realm; l'overlay non lo tocca.
test('Operatori invariati (Q-618): senza credenziali, con UPDATE_PASSWORD e MFA_REQUIRED_ROLE, flusso browser-mfa del realm', () => {
  const operators = (realm.users ?? []).filter(u => !u.serviceAccountClientId);
  assert.ok(operators.length > 0, 'nessun operatore nel realm: il controllo non verifica nulla');
  for (const u of operators) {
    assert.ok(!u.credentials?.length, `${u.username}: nessuna credenziale nel realm`);
    assert.ok((u.requiredActions ?? []).includes('UPDATE_PASSWORD'), `${u.username}: UPDATE_PASSWORD richiesta al primo accesso`);
    assert.ok((u.realmRoles ?? []).includes('MFA_REQUIRED_ROLE'), `${u.username}: senza MFA_REQUIRED_ROLE non scatta la MFA`);
  }
  assert.equal(realm.browserFlow, 'browser-mfa');
  const flow = realm.authenticationFlows.find(f => f.alias === 'mfa-conditional');
  assert.ok(flow?.authenticationExecutions.some(e => e.authenticator === 'conditional-user-role' && e.requirement === 'REQUIRED'), 'mfa-conditional: condizione sul ruolo');
  assert.ok(flow?.authenticationExecutions.some(e => e.authenticator === 'auth-otp-form' && e.requirement === 'REQUIRED'), 'mfa-conditional: OTP obbligatorio');
  const cfg = (realm.authenticatorConfig ?? []).find(a => a.alias === 'mfa-role-config');
  // La chiave è quella di ConditionalRoleAuthenticator (condUserRole): con un'altra il ruolo arriva null, la condizione
  // è sempre falsa e l'OTP non scatta per nessuno (trovato dallo smoke enterprise, M8.14 V6).
  assert.deepEqual(Object.keys(cfg?.config ?? {}), ['condUserRole'], 'mfa-role-config: solo la chiave condUserRole');
  assert.equal(cfg.config.condUserRole, 'MFA_REQUIRED_ROLE', 'mfa-role-config deve riferirsi a MFA_REQUIRED_ROLE');
});

// L'overlay si applica con un PUT del realm (frammento) e un partialImport dei client: simulazione della semantica
// di Keycloak sul realm.json senza un server, per provare che l'esito sia quello voluto e non tocchi altro.
test('Applicazione simulata dell\'overlay a una copia del realm: registrazione chiusa, lh-cli aggiunto, il resto invariato', () => {
  const o = vetrina();
  const copy = structuredClone(realm);
  const { _comment, realm: name, clients, scopeMappings, users: _users, roles: _roles, ...settings } = o;
  assert.equal(name, copy.realm);
  Object.assign(copy, settings);                                   // PUT /admin/realms/loyaltyhub (frammento)
  for (const c of clients) {                                       // partialImport, ifResourceExists=OVERWRITE
    const i = copy.clients.findIndex(x => x.clientId === c.clientId);
    if (i >= 0) copy.clients[i] = c; else copy.clients.push(c);
  }
  assert.equal(copy.registrationAllowed, false);
  assert.equal(scopeMappings.length, 1, 'gli scope mapping si applicano a parte (il partialImport non li porta)');
  assert.equal(copy.clients.length, realm.clients.length + 1);
  assert.deepEqual(copy.clients.filter(c => c.clientId !== 'lh-cli'), realm.clients, 'gli altri client restano identici');
  // Utenti di test (ADR-051): il partialImport OVERWRITE sostituisce gli operatori del realm base con quelli dell'overlay
  // (stesso nome utente, id e credenziali fissi) e aggiunge vetrina.admin; le utenze di servizio restano intatte.
  const baseOperators = realm.users.filter(u => !u.serviceAccountClientId);
  for (const u of o.users) {
    const i = copy.users.findIndex(x => x.username === u.username);
    if (i >= 0) copy.users[i] = u; else copy.users.push(u);
  }
  assert.deepEqual(copy.users.filter(u => u.serviceAccountClientId), realm.users.filter(u => u.serviceAccountClientId), 'utenze di servizio invariate');
  assert.equal(copy.users.filter(u => !u.serviceAccountClientId).length, baseOperators.length + 1, 'i cinque operatori sono sostituiti, vetrina.admin aggiunto');
  assert.deepEqual(copy.authenticationFlows, realm.authenticationFlows);
  assert.deepEqual(copy.requiredActions, realm.requiredActions);
  assert.equal(copy.verifyEmail, false);
  assert.ok(copy.smtpServer === undefined || Object.keys(copy.smtpServer).length === 0);
  // Idempotenza: riapplicarlo non cambia nulla.
  const again = structuredClone(copy);
  for (const c of clients) again.clients[again.clients.findIndex(x => x.clientId === c.clientId)] = c;
  assert.deepEqual(again, copy);
  // Il client web resta con le URI da LH_WEB_URL (il test del client web sul realm base vale anche dopo l'overlay).
  assert.deepEqual(copy.clients.find(c => c.clientId === 'web').redirectUris, [`\${LH_WEB_URL}/api/auth/callback`]);
});

test('apply-overlay.sh di vetrina: impostazioni ammesse uguali alle chiavi dell\'overlay, nessun segnaposto, nessun valore stampato', () => {
  const sh = fs.readFileSync(VETRINA_APPLY_PATH, 'utf8');
  const list = sh.match(/^REALM_SETTINGS=\(\n([\s\S]*?)^\)/m);
  assert.ok(list, 'REALM_SETTINGS non trovato in vetrina/apply-overlay.sh');
  const scriptKeys = list[1].split('\n').map(l => l.trim()).filter(Boolean).sort();
  const overlayKeys = Object.keys(vetrina()).filter(k => !OVERLAY_META_KEYS.includes(k)).sort();
  assert.deepEqual(scriptKeys, overlayKeys, 'REALM_SETTINGS di apply-overlay.sh e chiavi dell\'overlay devono coincidere');
  assert.match(sh, /set -euo pipefail/);
  // La password di amministrazione non sta mai in un argomento di processo né viene stampata.
  assert.ok(!/-d\s+["']?password=/.test(sh) && !/--data(-urlencode)?\s+["']?password=/.test(sh), 'password di amministrazione sulla riga di comando');
  for (const line of sh.split('\n')) {
    if (/\b(echo|printf)\b/.test(line) && /\$\{?(ADMIN_PASSWORD|TOKEN)\b/.test(line)) {
      assert.ok(/>>?\s*"\$WORK\/|\|\s*python3/.test(line), `riga che potrebbe stampare una credenziale: ${line.trim()}`);
    }
  }
});

function shellList(sh, name) {
  const m = sh.match(new RegExp(`^${name}=\\(\\n([\\s\\S]*?)^\\)`, 'm'));
  assert.ok(m, `${name} non trovato in vetrina/apply-overlay.sh`);
  return m[1].split('\n').map(l => l.trim()).filter(Boolean).sort();
}

test('apply-overlay.sh di vetrina: allowlist dei realm dei membri e master uguali alle chiavi dei file (Q-672, Q-677)', () => {
  const sh = fs.readFileSync(VETRINA_APPLY_PATH, 'utf8');
  const meta = ['_comment', 'realm', 'roles', 'users'];
  const keys = f => Object.keys(JSON.parse(fs.readFileSync(f, 'utf8'))).filter(k => !meta.includes(k)).sort();
  assert.deepEqual(shellList(sh, 'MEMBERS_SETTINGS'), keys(VETRINA_MEMBERS_PATH));
  assert.deepEqual(shellList(sh, 'MASTER_SETTINGS'), keys(VETRINA_MASTER_PATH));
  assert.match(sh, /^MGMT_ROLES=\(view-users query-users query-groups manage-users view-events\)$/m, 'ruoli di realm-management di Q-671');
  assert.match(sh, /^TEST_ROLE="LH_TEST_USER"$/m);
});

// ---------------------------------------------------------------------------------------------------------------------
// Utenti di test della vetrina (ADR-051 decisione 1; Q-671, Q-672, Q-673, Q-676). Password e seme TOTP sono PUBBLICI e
// documentati; esistono solo negli overlay di vetrina. Il blocco di sicurezza del realm base (nessuna credenziale,
// nessun LH_TEST_USER) è nel test «Realm di base» più sopra e continua a valere.
// ---------------------------------------------------------------------------------------------------------------------
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MGMT_ROLES = ['manage-users', 'query-groups', 'query-users', 'view-events', 'view-users'];
const FORBIDDEN_MGMT = ['manage-realm', 'manage-clients', 'manage-identity-providers', 'manage-events', 'realm-admin', 'impersonation', 'view-realm', 'view-clients', 'view-identity-providers', 'create-client', 'query-clients', 'query-realms'];
const OPERATOR_USERS = { 'marta.admin': 'ADMIN', 'luca.marketing': 'MARKETING', 'elena.legal': 'LEGAL', 'paolo.care': 'CARE', 'sara.analyst': 'ANALYST' };
const membersVetrina = () => JSON.parse(fs.readFileSync(VETRINA_MEMBERS_PATH, 'utf8'));
const masterVetrina = () => JSON.parse(fs.readFileSync(VETRINA_MASTER_PATH, 'utf8'));
const passwordOf = u => u.credentials.find(c => c.type === 'password');
const otpOf = u => u.credentials.find(c => c.type === 'otp');
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function base32(buf) {
  let bits = 0, value = 0, out = '';
  for (const b of buf) {
    value = (value << 8) | b; bits += 8;
    while (bits >= 5) { out += BASE32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += BASE32[(value << (5 - bits)) & 31];
  return out;
}
/** Seme TOTP condiviso degli operatori di test: i byte UTF-8 del segreto di Keycloak, in base32 come lo mostra un'app OTP. */
const totpSeedBase32 = () => base32(Buffer.from(JSON.parse(otpOf(vetrina().users[0]).secretData).value, 'utf8'));
const checkPolicy = (policy, password, who) => {
  const length = /length\((\d+)\)/.exec(policy ?? '');
  if (length) assert.ok(password.length >= Number(length[1]), `${who}: la password non rispetta length(${length[1]})`);
};

test('Utenti di test degli operatori: cinque operatori con MFA e credenziali fisse, id fissi, nessuna azione richiesta (ADR-051, Q-672)', () => {
  const users = vetrina().users;
  assert.deepEqual(users.map(u => u.username), [...Object.keys(OPERATOR_USERS), 'vetrina.admin']);
  const seeds = new Set();
  for (const [name, role] of Object.entries(OPERATOR_USERS)) {
    const u = users.find(x => x.username === name);
    assert.deepEqual([...u.realmRoles].sort(), [role, 'LH_TEST_USER', 'MFA_REQUIRED_ROLE'].sort(), `${name}: ruolo applicativo, MFA_REQUIRED_ROLE e LH_TEST_USER`);
    assert.ok(!('requiredActions' in u), `${name}: nessuna azione richiesta: il login non si ferma su UPDATE_PASSWORD o CONFIGURE_TOTP`);
    assert.equal(passwordOf(u).temporary, false, `${name}: password non temporanea`);
    checkPolicy(realm.passwordPolicy, passwordOf(u).value, name);
    const otp = otpOf(u);
    assert.ok(otp, `${name}: credenziale OTP fissa (senza, scatterebbe CONFIGURE_TOTP)`);
    assert.deepEqual(JSON.parse(otp.credentialData), { subType: 'totp', digits: 6, period: 30, algorithm: 'HmacSHA1', counter: 0 }, 'OTP come la politica predefinita del realm (lo smoke calcola il TOTP così)');
    seeds.add(JSON.parse(otp.secretData).value);
    assert.ok(u.email && u.firstName && u.lastName, `${name}: profilo completo, altrimenti Keycloak chiede di completarlo`);
    // Gli operatori esistono già nel realm base, senza credenziali: l'overlay li sostituisce (OVERWRITE).
    const base = realm.users.find(x => x.username === name);
    assert.ok(base, `${name}: esiste nel realm base`);
    assert.deepEqual(base.realmRoles, [role, 'MFA_REQUIRED_ROLE']);
  }
  assert.equal(seeds.size, 1, 'un solo seme TOTP condiviso e documentato');
  const [secret] = seeds;
  assert.ok(/^[\x20-\x7e]{20}$/.test(secret), 'seme ASCII stampabile di 20 caratteri: i byte UTF-8 sono quelli che usa Keycloak');
  const ids = users.map(u => u.id);
  assert.ok(ids.every(i => UUID_RE.test(i)) && new Set(ids).size === ids.length, 'id fissi UUID e distinti: il sub non cambia a ogni ripristino (Q-672)');
});

test('Amministratore di test degli operatori (Q-671): i soli cinque ruoli di realm-management, nessun ruolo applicativo, nessuna MFA', () => {
  const u = vetrina().users.find(x => x.username === 'vetrina.admin');
  assert.deepEqual(u.realmRoles, ['LH_TEST_USER'], 'né MFA_REQUIRED_ROLE né ruoli applicativi: non entra nel backoffice');
  assert.deepEqual(Object.keys(u.clientRoles), ['realm-management']);
  assert.deepEqual([...u.clientRoles['realm-management']].sort(), MGMT_ROLES);
  for (const r of FORBIDDEN_MGMT) assert.ok(!u.clientRoles['realm-management'].includes(r), `vetrina.admin non deve avere ${r}`);
  assert.equal(otpOf(u), undefined, 'nessuna MFA');
  checkPolicy(realm.passwordPolicy, passwordOf(u).value, 'vetrina.admin');
});

test('Utenti di test dei membri: Anna, Marco, Giulia dal seed e Laura da zero, più membri.admin con i soli ruoli di Q-671 (Q-673)', () => {
  const o = membersVetrina();
  const baseMembers = JSON.parse(fs.readFileSync(MEMBERS_REALM_PATH, 'utf8'));
  assert.equal(o.realm, 'loyaltyhub-members');
  assert.equal(o.registrationAllowed, false, 'registrazione libera chiusa: gli account creati a mano sparirebbero al ripristino (decisione 10)');
  assert.equal(o.adminEventsEnabled, true);
  assert.equal(o.adminEventsDetailsEnabled, true, 'Q-677');
  assert.deepEqual((o.roles?.realm ?? []).map(r => r.name), ['LH_TEST_USER']);
  const seed = JSON.parse(fs.readFileSync(path.join(ROOT, 'seed/members.json'), 'utf8'));
  assert.deepEqual(o.users.map(u => u.username), ['anna.rossi', 'marco.bianchi', 'giulia.ferri', 'laura.conti', 'membri.admin']);
  for (const [i, u] of o.users.slice(0, 3).entries()) {
    const s = seed[i];
    assert.deepEqual([u.firstName, u.lastName, u.email], [s.firstName, s.lastName, s.email], `${u.username}: nome ed e-mail dal seed (${s.id})`);
  }
  const laura = o.users.find(u => u.username === 'laura.conti');
  assert.equal(laura.firstName, 'Laura');
  assert.ok(!seed.some(m => m.firstName === 'Laura' || m.email === laura.email), 'Laura non è nel seed: scenario di registrazione da zero');
  assert.ok(laura.email.endsWith('@example.org'));
  for (const u of o.users.slice(0, 4)) {
    assert.deepEqual([...u.realmRoles].sort(), ['LH_TEST_USER', 'MEMBER', 'default-roles-loyaltyhub-members'].sort(), `${u.username}: MEMBER e LH_TEST_USER`);
    assert.ok(!('requiredActions' in u));
    assert.equal(passwordOf(u).temporary, false);
    assert.equal(otpOf(u), undefined, 'il realm dei membri non ha MFA');
    checkPolicy(baseMembers.passwordPolicy, passwordOf(u).value, u.username);
  }
  const admin = o.users.find(u => u.username === 'membri.admin');
  assert.deepEqual(admin.realmRoles, ['LH_TEST_USER']);
  assert.deepEqual([...admin.clientRoles['realm-management']].sort(), MGMT_ROLES);
  const ids = o.users.map(u => u.id);
  assert.ok(ids.every(i => UUID_RE.test(i)) && new Set(ids).size === ids.length, 'id fissi');
  assert.ok(baseMembers.roles.realm.some(r => r.name === 'MEMBER'));
  assert.ok(!baseMembers.roles.realm.some(r => r.name === 'LH_TEST_USER'));
});

test('Id degli utenti di test distinti tra i due realm (nessuna collisione di sub)', () => {
  const all = [...vetrina().users, ...membersVetrina().users].map(u => u.id);
  assert.equal(new Set(all).size, all.length);
});

test('master.json (Q-672): solo impostazioni del realm master, nessun utente né segreto né frontendUrl', () => {
  const m = masterVetrina();
  assert.equal(m.realm, 'master');
  assert.equal(m.bruteForceProtected, true);
  assert.equal(m.adminEventsEnabled, true);
  assert.equal(m.adminEventsDetailsEnabled, true);
  assert.equal(m.sslRequired, 'external');
  assert.match(m.passwordPolicy, /length\(\d+\)/);
  for (const k of ['users', 'clients', 'roles', 'groups', 'frontendUrl', 'attributes', 'smtpServer', 'browserFlow', 'identityProviders', 'components']) {
    assert.ok(!(k in m), `master.json non deve contenere ${k} (niente indirizzi né utenti: l'utente del proprietario lo crea apply-overlay.sh dal segreto, ADR-055)`);
  }
  assert.ok(!/credentials|secret/i.test(fs.readFileSync(VETRINA_MASTER_PATH, 'utf8')), 'nessuna credenziale');
});

test('Eventi di amministrazione (Q-677): attivi con i dettagli in entrambi i realm di vetrina e in master; nei realm base già attivi', () => {
  const baseMembers = JSON.parse(fs.readFileSync(MEMBERS_REALM_PATH, 'utf8'));
  for (const r of [realm, baseMembers]) assert.equal(r.adminEventsEnabled, true, `${r.realm}: eventi di amministrazione già attivi nel realm base`);
  for (const o of [vetrina(), membersVetrina(), masterVetrina()]) {
    assert.equal(o.adminEventsEnabled, true, o.realm);
    assert.equal(o.adminEventsDetailsEnabled, true, `${o.realm}: con i dettagli della rappresentazione`);
  }
});

test('Gitleaks: credenziali di test pubbliche ammesse solo nei percorsi esatti degli overlay, dei documenti che le elencano e di web/lib/hub/testUsers.ts (ADR-051 decisione 1)', () => {
  const toml = fs.readFileSync(path.join(ROOT, '.gitleaks.toml'), 'utf8');
  assert.match(toml, /ADR-051 decisione 1/);
  for (const f of ['deploy/idp/vetrina/realm-vetrina-overlay.json', 'deploy/idp/vetrina/realm-members-vetrina-overlay.json', 'deploy/vetrina/README.md', 'concetti/vetrina-enterprise.mdx', 'web/lib/hub/testUsers.ts']) {
    assert.ok(toml.includes(f.replace(/[\\^$.*+?()[\]{}|]/g, '\\$&')), `.gitleaks.toml: percorso ${f}`);
  }
});

test('Documentazione delle credenziali di test: runbook e pagina Mintlify elencano ogni utente, password, seme TOTP e URI otpauth (ADR-051)', () => {
  const docs = ['deploy/vetrina/README.md', 'concetti/vetrina-enterprise.mdx'].map(f => [f, fs.readFileSync(path.join(ROOT, f), 'utf8')]);
  const seed = totpSeedBase32();
  for (const [f, text] of docs) {
    for (const u of [...vetrina().users, ...membersVetrina().users]) {
      assert.ok(text.includes(`\`${u.username}\``), `${f}: manca l'utente ${u.username}`);
      assert.ok(text.includes(passwordOf(u).value), `${f}: manca la password di ${u.username}`);
    }
    assert.ok(text.includes(seed), `${f}: manca il seme TOTP in base32`);
    assert.ok(text.includes('otpauth://totp/') && text.includes(`secret=${seed}`), `${f}: manca l'URI otpauth`);
  }
});

// Regola 20 (ADR-049 decisione 6): bootstrap.sh non stampa le password su stdout; le scrive solo in un file 0600.
test('bootstrap.sh: password solo in un file 0600 (umask 077), mai su stdout né come argomenti di processo (regola 20)', () => {
  const sh = fs.readFileSync(BOOTSTRAP_PATH, 'utf8');
  assert.match(sh, /^umask 077$/m, 'umask 077 prima di creare il file');
  // File creato in modo atomico: mktemp (O_EXCL, 0600 con umask 077) nella stessa cartella e rename finale; nessuna scrittura
  // diretta sul percorso finale (TOCTOU con un collegamento simbolico) e nessun file creato prima del token.
  assert.match(sh, /CRED_TMP="\$\(mktemp "\$\{CRED_DIR\}\//, 'file temporaneo con mktemp nella cartella di destinazione');
  assert.match(sh, /os\.replace\(sys\.argv\[1\],sys\.argv\[2\]\)' "\$CRED_TMP" "\$CRED_FILE"/, 'rename finale del file temporaneo');
  assert.ok(!/(>>?|tee)\s*"\$CRED_FILE"/.test(sh) && !/chmod 600 "\$CRED_FILE"/.test(sh), 'mai scrivere direttamente sul percorso finale');
  assert.ok(sh.indexOf('mktemp "${CRED_DIR}') > sh.indexOf('LH_IDP_BOOTSTRAP_OUT'), 'ordine');
  assert.match(sh, /trap '[^']*rm -f "\$CRED_TMP"[^']*' EXIT/, 'il file temporaneo si cancella se lo script fallisce');
  // Il nome utente di amministrazione è codificato come la password (un & o un = non rompe il corpo della richiesta).
  assert.ok(!/username=%s/.test(sh) && /printf '%s' "\$ADMIN_USER" \| urlenc/.test(sh), 'nome utente non codificato');
  assert.match(sh, /LH_IDP_BOOTSTRAP_OUT/, 'percorso configurabile');
  assert.ok(!/--- Credenziali generate ---/.test(sh), 'la vecchia stampa delle credenziali non deve esistere');
  assert.ok(!/-d\s+"\{\\"type\\"/.test(sh), 'la password non va nel corpo passato con -d (visibile in ps)');
  assert.ok(!/-d\s+["']?password=/.test(sh), 'la password di amministrazione non va passata con -d');
  const secret = /\$\{?(PASS_\w+|USERS\[|SUPPLIED\[|ADMIN_PASSWORD|PASSWORD|TOKEN|env_val|TEMP_PASS)/;
  const lines = sh.split('\n');
  lines.forEach((line, i) => {
    if (/^\s*#/.test(line)) return;
    if (/\b(echo|printf)\b/.test(line) && secret.test(line)) {
      // Ammesso solo: scrittura nel file delle credenziali, o ingresso di una pipeline (stdin di python3).
      const continued = /\\\s*$/.test(line) && /^\s*\|\s*python3\b/.test(lines[i + 1] ?? '');
      assert.ok(/>>?\s*"\$(CRED_TMP|WORK\/[a-z]+)"/.test(line) || /\|\s*(python3|urlenc)\b/.test(line) || continued || /env_val/.test(line) && /printf '%s\\n'/.test(line),
        `bootstrap.sh riga ${i + 1} potrebbe stampare una password: ${line.trim()}`);
    }
  });
  // Il percorso di default sta in una cartella ignorata da git.
  assert.match(sh, /\.secrets\/bootstrap-passwords\.txt/);
  assert.match(fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf8'), /^deploy\/idp\/\.secrets\/$/m, '.gitignore deve ignorare deploy/idp/.secrets/');
});

// apply-overlay.sh di vetrina: elenco dei ruoli operatore e del ruolo MFA uguali a quelli del realm (Q-618).
test('apply-overlay.sh di vetrina: ruoli operatore e MFA_REQUIRED_ROLE coerenti con il realm, modalità --check-operators, nome utente codificato', () => {
  const sh = fs.readFileSync(VETRINA_APPLY_PATH, 'utf8');
  const ops = sh.match(/^OPERATOR_ROLES=\(([^)]*)\)/m);
  assert.ok(ops, 'OPERATOR_ROLES non trovato');
  assert.deepEqual(ops[1].trim().split(/\s+/), OPERATOR_ROLES);
  assert.match(sh, /^MFA_ROLE="MFA_REQUIRED_ROLE"$/m);
  assert.ok(realm.roles.realm.some(r => r.name === 'MFA_REQUIRED_ROLE'));
  assert.match(sh, /--check-operators\) MODE="operators"/);
  assert.ok(!/username=%s/.test(sh) && /printf '%s' "\$ADMIN_USER" \| urlenc/.test(sh), 'nome utente non codificato');
  // Il PUT del realm rimanda la rappresentazione intera (GET + unione), non il solo frammento.
  assert.match(sh, /--data-binary "@\$WORK\/realm-put\.json"/);
  assert.ok(!/--data-binary "@\$WORK\/realm-settings\.json"/.test(sh), 'PUT parziale del realm');
});

// Keycloak simulato: prova apply-overlay.sh (applicazione, rilettura, verifica degli operatori e degli utenti di test)
// senza un server reale. Regola 20: la password di amministrazione di prova e' un valore inventato del test.
// Simula la semantica di Keycloak che serve allo script: PUT dei realm, partialImport OVERWRITE di client, ruoli e
// utenti (con gli id dichiarati), ruoli di realm-management assegnati con l'API, elenco degli utenti per ruolo.
// Utente del proprietario nel realm master (ADR-055, Q-727): creazione, reset-password, ruolo `admin`, rilettura e login con
// la password (valido solo se la password non e' temporanea e l'utente e' abilitato e senza azioni richieste).
// `rejectOwnerPassword` risponde 400 al reset-password con il solo motivo (come Keycloak, mai il valore);
// `ownerLoginFails` rifiuta il login di prova dell'utente.
function mockKeycloak({ operators, bootstrapping = 0, wrongPassword = false, dropOtp = false, renumberIds = false, rejectOwnerPassword = false, ownerLoginFails = false }) {
  const base = (name, extra = {}) => ({ realm: name, registrationAllowed: true, browserFlow: 'browser', bruteForceProtected: true, sslRequired: 'external',
    adminEventsEnabled: true, adminEventsDetailsEnabled: false, ...extra });
  const state = {
    realms: {
      master: base('master', { bruteForceProtected: false, adminEventsEnabled: false, passwordPolicy: '' }),
      loyaltyhub: base('loyaltyhub', { browserFlow: 'browser-mfa', otpPolicyType: 'totp', verifyEmail: false, passwordPolicy: 'length(12)' }),
      'loyaltyhub-members': base('loyaltyhub-members'),
    },
    users: { loyaltyhub: new Map(), 'loyaltyhub-members': new Map(), master: new Map() },
    roles: { loyaltyhub: [], 'loyaltyhub-members': [] },
    clients: [], scope: [], puts: 0, loginBody: '', imports: [], issued: 0, minValid: 1, logins: 0, ownerLogins: 0, paths: [],
  };
  const imported = (realm) => [...state.users[realm].values()];
  const roleUsers = (role) => operators.filter(u => (u.realmRoles ?? []).includes(role)).map(u => ({ id: `id-${u.username}`, username: u.username }))
    .concat(imported('loyaltyhub').filter(u => (u.realmRoles ?? []).includes(role)).map(u => ({ id: u.id, username: u.username })))
    .concat(role === 'ADMIN' ? [{ id: 'sa', username: 'service-account-x', serviceAccountClientId: 'x' }] : []);
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', d => { body += d; });
    req.on('end', () => {
      const url = new URL(req.url, 'http://x');
      const json = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
      const p = url.pathname;
      if (p === '/realms/master/protocol/openid-connect/token') {
        const form = new URLSearchParams(body);
        if (form.get('username') === 'proprietario') {
          state.ownerLogins++;
          const o = [...state.users.master.values()].find(x => x.username === 'proprietario');
          const valid = o && o.enabled && !(o.requiredActions ?? []).length && o.password !== undefined && !o.temporary && form.get('password') === o.password;
          return valid && !ownerLoginFails ? json(200, { access_token: `owner-token-${state.ownerLogins}` }) : json(401, { error: 'invalid_grant' });
        }
        state.loginBody = body; state.logins++;
        // Keycloak 26 a fine avvio: /health/ready gia' verde, ma le richieste ricevono 503 in testo finche' il bootstrap
        // (import dei realm, amministratore temporaneo) non termina.
        if (state.logins <= bootstrapping) { res.writeHead(503, { 'Content-Type': 'text/plain' }); return res.end('Request received during bootstrapping'); }
        if (wrongPassword) return json(401, { error: 'invalid_grant' });
        return json(200, { access_token: `mock-token-${++state.issued}` });
      }
      const tokenNo = Number((req.headers.authorization ?? '').match(/^Bearer mock-token-(\d+)$/)?.[1]);
      if (!(tokenNo >= state.minValid)) return json(401, {});
      const m = p.match(/^\/admin\/realms\/([a-z-]+)(?:\/(.*))?$/);
      if (!m || !state.realms[m[1]]) return json(404, { path: p });
      const [, realm, rest] = m;
      state.paths.push(`${req.method} ${req.url}`);
      let o;
      if (realm === 'master' && rest === 'users' && req.method === 'GET') {
        const name = url.searchParams.get('username');
        return json(200, [...state.users.master.values()].filter(u => u.username === name).map(({ id, username, enabled }) => ({ id, username, enabled })));
      }
      if (realm === 'master' && rest === 'users' && req.method === 'POST') {
        const id = `owner-${state.users.master.size + 1}`;
        state.users.master.set(id, { id, requiredActions: [], realmRoles: [], creds: [], ...JSON.parse(body) });
        res.writeHead(201, { Location: `/admin/realms/master/users/${id}` });
        return res.end();
      }
      if (realm === 'master' && rest === 'roles/admin') return json(200, { id: 'role-admin', name: 'admin' });
      if (realm === 'master' && (o = rest?.match(/^users\/([^/]+)(?:\/(reset-password|role-mappings\/realm|role-mappings|credentials))?$/))) {
        const u = state.users.master.get(o[1]);
        if (!u) return json(404, {});
        if (!o[2] && req.method === 'GET') return json(200, { id: u.id, username: u.username, enabled: u.enabled, emailVerified: u.emailVerified, requiredActions: u.requiredActions });
        if (!o[2] && req.method === 'PUT') { Object.assign(u, JSON.parse(body)); return json(204); }
        if (o[2] === 'reset-password') {
          if (rejectOwnerPassword) return json(400, { error: 'invalidPasswordMinLengthMessage', error_description: 'Invalid password: minimum length 12.' });
          const b = JSON.parse(body);
          Object.assign(u, { password: b.value, temporary: b.temporary, creds: [{ type: b.type }] });
          return json(204);
        }
        if (o[2] === 'role-mappings/realm') { u.realmRoles = [...new Set([...u.realmRoles, ...JSON.parse(body).map(x => x.name)])]; return json(204); }
        if (o[2] === 'role-mappings') return json(200, { realmMappings: u.realmRoles.map(name => ({ name })) });
        if (o[2] === 'credentials') return json(200, u.creds);
      }
      if (rest === undefined) {
        if (req.method === 'GET') return json(200, state.realms[realm]);
        if (req.method === 'PUT') {
          const rep = JSON.parse(body);
          state.puts++; state.realms[realm] = rep; return json(204);
        }
      }
      if (rest === 'partialImport') {
        const b = JSON.parse(body);
        state.imports.push({ realm, keys: Object.keys(b).filter(k => k !== 'ifResourceExists') });
        const results = [];
        for (const c of b.clients ?? []) {
          state.clients = state.clients.filter(x => x.clientId !== c.clientId);
          state.clients.push({ id: `uuid-${c.clientId}`, ...c });
          state.scope = [];
          results.push({ resourceType: 'CLIENT', resourceName: c.clientId, action: 'ADDED' });
        }
        for (const r of b.roles?.realm ?? []) {
          state.roles[realm] = state.roles[realm].filter(x => x.name !== r.name).concat(r);
          results.push({ resourceType: 'REALM_ROLE', resourceName: r.name, action: 'ADDED' });
        }
        for (const u of b.users ?? []) {
          for (const [id, x] of state.users[realm]) if (x.username === u.username) state.users[realm].delete(id);
          const id = renumberIds ? `renumbered-${u.username}` : u.id;
          const creds = (u.credentials ?? []).filter(c => !(dropOtp && c.type === 'otp')).map(c => ({ type: c.type }));
          state.users[realm].set(id, { ...u, id, creds, clientMappings: [] });
          results.push({ resourceType: 'USER', resourceName: u.username, action: 'ADDED' });
        }
        return json(200, { added: results.length, overwritten: 0, skipped: 0, results });
      }
      if (rest === 'clients') {
        const id = url.searchParams.get('clientId');
        if (id === 'realm-management') return json(200, [{ id: `rm-${realm}`, clientId: id }]);
        return json(200, realm === 'loyaltyhub' ? state.clients.filter(c => c.clientId === id) : []);
      }
      let r;
      if ((r = rest?.match(/^clients\/rm-[a-z-]+\/roles$/))) {
        return json(200, ['view-users', 'query-users', 'query-groups', 'manage-users', 'view-events', 'manage-realm', 'realm-admin', 'impersonation'].map(name => ({ id: `r-${name}`, name })));
      }
      if ((r = rest?.match(/^clients\/([^/]+)\/(optional-client-scopes|default-client-scopes|scope-mappings\/realm)$/))) {
        const c = state.clients.find(x => x.id === r[1]);
        if (!c) return json(404, {});
        if (r[2] === 'optional-client-scopes') return json(200, (c.optionalClientScopes ?? []).map(name => ({ name })));
        if (r[2] === 'default-client-scopes') return json(200, (c.defaultClientScopes ?? []).map(name => ({ name })));
        if (req.method === 'POST') { state.scope = JSON.parse(body); return json(204); }
        return json(200, state.scope);
      }
      if ((r = rest?.match(/^users\/([^/]+)(?:\/(role-mappings|credentials|role-mappings\/clients\/rm-[a-z-]+))?$/))) {
        const u = state.users[realm].get(r[1]);
        if (!u) return json(404, {});
        if (!r[2]) return json(200, { id: u.id, username: u.username, enabled: u.enabled, requiredActions: u.requiredActions ?? [] });
        if (r[2] === 'credentials') return json(200, u.creds);
        if (r[2] === 'role-mappings') {
          const mappings = u.clientMappings.length ? { 'realm-management': { mappings: u.clientMappings } } : {};
          return json(200, { realmMappings: u.realmRoles.map(name => ({ name })), clientMappings: mappings });
        }
        u.clientMappings = JSON.parse(body);
        return json(204);
      }
      if (realm !== 'loyaltyhub') return json(404, { path: p });
      if ((r = rest?.match(/^roles\/([A-Z_]+)$/))) return json(200, { id: `role-${r[1]}`, name: r[1] });
      if ((r = rest?.match(/^roles\/([A-Z_]+)\/users$/))) {
        const all = roleUsers(r[1]), first = Number(url.searchParams.get('first')), max = Number(url.searchParams.get('max'));
        return json(200, all.slice(first, first + max));
      }
      return json(404, { path: p });
    });
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve({ server, state, url: `http://127.0.0.1:${server.address().port}` })));
}

function runApply(url, args, userName = 'admin', extraEnv = {}) {
  return new Promise(resolve => {
    const env = { ...process.env, ...extraEnv, KEYCLOAK_URL: url, KC_BOOTSTRAP_ADMIN_USERNAME: userName, KC_BOOTSTRAP_ADMIN_PASSWORD: 'Test&Pass=1 x' };
    const child = spawn('bash', [VETRINA_APPLY_PATH, ...args], { env });
    let out = '', err = '';
    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { err += d; });
    child.on('close', code => resolve({ code, out, err }));
  });
}

const haveTools = ['bash', 'curl', 'python3'].every(c => spawnSync('sh', ['-c', `command -v ${c}`]).status === 0);

test('apply-overlay.sh di vetrina contro un Keycloak simulato: applica i tre realm, importa gli utenti di test con id fissi, rilegge, non cambia altro e rifiuta un operatore senza MFA_REQUIRED_ROLE (Q-618, Q-626, Q-671, Q-672)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const good = [
    { username: 'mario.rossi', realmRoles: ['ADMIN', 'MFA_REQUIRED_ROLE'] },
    { username: 'anna.verdi', realmRoles: ['ANALYST', 'MFA_REQUIRED_ROLE'] },
  ];
  // Molti operatori: prova la paginazione (200 per pagina) dell'elenco degli utenti di un ruolo.
  const many = Array.from({ length: 450 }, (_, i) => ({ username: `op${i}`, realmRoles: ['CARE', 'MFA_REQUIRED_ROLE'] }));
  const kc = await mockKeycloak({ operators: [...good, ...many] });
  try {
    const before = structuredClone(kc.state.realms);
    const r = await runApply(kc.url, [], 'adm&in=x');
    assert.equal(r.code, 0, `apply-overlay.sh è fallito: ${r.err}${r.out}`);
    const { loyaltyhub: ops, 'loyaltyhub-members': mem, master } = kc.state.realms;
    assert.equal(ops.registrationAllowed, false);
    assert.equal(ops.adminEventsDetailsEnabled, true, 'eventi di amministrazione con i dettagli (Q-677)');
    assert.deepEqual({ ...ops, registrationAllowed: true, adminEventsDetailsEnabled: false }, before.loyaltyhub, 'il PUT non cambia altre impostazioni del realm');
    // Realm dei membri: registrazione libera chiusa (ADR-051, Q-673) e eventi di amministrazione con dettagli.
    assert.equal(mem.registrationAllowed, false, 'registrazione libera chiusa nel realm dei membri della vetrina');
    assert.equal(mem.adminEventsDetailsEnabled, true);
    assert.equal(mem.browserFlow, 'browser', 'il PUT non cambia altre impostazioni del realm dei membri');
    // Master (Q-672): le sole impostazioni di master.json, il resto invariato.
    const masterOverlay = masterVetrina();
    const { _comment, realm: _r, ...settings } = masterOverlay;
    assert.deepEqual(master, { ...before.master, ...settings });
    assert.equal(master.frontendUrl, undefined, 'senza MASTER_FRONTEND_URL il realm master non riceve il frontendUrl (Q-670)');
    assert.equal(master.attributes, undefined);
    const c = kc.state.clients.find(x => x.clientId === 'lh-cli');
    assert.ok(c && c.publicClient && c.consentRequired && c.fullScopeAllowed === false);
    assert.deepEqual(kc.state.scope.map(x => x.name).sort(), [...OPERATOR_ROLES].sort());
    // Utenti di test: importati con gli id dell'overlay, ruolo LH_TEST_USER creato prima degli utenti, ruoli di Q-671.
    assert.deepEqual([...kc.state.users.loyaltyhub.keys()].sort(), vetrina().users.map(u => u.id).sort());
    assert.deepEqual([...kc.state.users['loyaltyhub-members'].keys()].sort(), membersVetrina().users.map(u => u.id).sort());
    assert.deepEqual(kc.state.imports.filter(i => i.realm === 'loyaltyhub').map(i => i.keys[0]), ['clients', 'roles', 'users']);
    assert.deepEqual(kc.state.imports.filter(i => i.realm === 'loyaltyhub-members').map(i => i.keys[0]), ['roles', 'users']);
    for (const realmName of ['loyaltyhub', 'loyaltyhub-members']) {
      const admin = [...kc.state.users[realmName].values()].find(u => /\.admin$/.test(u.username) && !(u.username in OPERATOR_USERS) && u.username !== 'marta.admin');
      assert.deepEqual(admin.clientMappings.map(x => x.name).sort(), MGMT_ROLES, `${admin.username}: i soli ruoli di Q-671 (non manage-realm, realm-admin, impersonation)`);
    }
    const members = [...kc.state.users['loyaltyhub-members'].values()].filter(u => u.username !== 'membri.admin');
    assert.ok(members.every(u => u.clientMappings.length === 0));
    assert.match(kc.state.loginBody, /username=adm%26in%3Dx&password=Test%26Pass%3D1%20x/, 'nome utente e password codificati');
    assert.match(r.out, /operatori: \d+ account/);
    assert.match(r.out, /6 utenti di test in loyaltyhub con id fissi/);
    assert.match(r.out, /5 utenti di test in loyaltyhub-members con id fissi/);
    // Nessuna credenziale in uscita: né quella di amministrazione, né il token, né le password e il seme di prova.
    const secrets = [...vetrina().users, ...membersVetrina().users].flatMap(u => u.credentials.map(x => x.value ?? JSON.parse(x.secretData).value));
    for (const text of [r.out, r.err]) {
      assert.ok(!text.includes('Test&Pass') && !text.includes('mock-token'), 'nessuna credenziale di amministrazione in uscita');
      for (const sec of secrets) assert.ok(!text.includes(sec), 'nessuna password o seme di prova in uscita');
    }
    // Idempotente.
    assert.equal((await runApply(kc.url, [])).code, 0);
    assert.equal(kc.state.users.loyaltyhub.size, 6);
    // Solo lettura: --check-operators non scrive.
    const puts = kc.state.puts;
    assert.equal((await runApply(kc.url, ['--check-operators'])).code, 0);
    assert.equal(kc.state.puts, puts);
  } finally { kc.server.close(); }

  // Un ADMIN creato a mano senza MFA_REQUIRED_ROLE: lo script deve fallire e nominarlo (non le utenze di servizio).
  const bad = await mockKeycloak({ operators: [...good, { username: 'luigi.bianchi', realmRoles: ['ADMIN'] }, { username: 'sara.care', realmRoles: ['CARE'] }] });
  try {
    for (const args of [[], ['--check-operators']]) {
      const r = await runApply(bad.url, args);
      assert.notEqual(r.code, 0, `${args.join(' ') || 'apply'}: doveva fallire`);
      assert.match(r.err, /luigi\.bianchi/);
      assert.match(r.err, /sara\.care/);
      assert.ok(!/mario\.rossi|service-account-x/.test(r.err), 'elenca solo gli account senza MFA');
    }
  } finally { bad.server.close(); }
});

// Collaudo M8.14 (2026-10-04): avvio.sh si e' fermato perche' il token di amministrazione e' stato chiesto un secondo prima
// della fine del bootstrap di Keycloak (503 non JSON). Lo script deve riprovare il 503 e non il 401.
test('apply-overlay.sh di vetrina: riprova il token mentre Keycloak finisce l\'avvio (503), si ferma subito su credenziali sbagliate (401) e dopo il tetto', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const fast = { KC_TOKEN_RETRY_S: '0' };
  let kc = await mockKeycloak({ operators: [], bootstrapping: 3 });
  try {
    const r = await runApply(kc.url, [], 'admin', fast);
    assert.equal(r.code, 0, `apply-overlay.sh è fallito: ${r.err}${r.out}`);
    assert.match(r.out, /Keycloak non e' ancora pronto \(HTTP 503\)/);
    assert.ok(kc.state.issued >= 1, 'token ottenuto dopo il bootstrap');
  } finally { kc.server.close(); }
  kc = await mockKeycloak({ operators: [], wrongPassword: true });
  try {
    const r = await runApply(kc.url, [], 'admin', fast);
    assert.notEqual(r.code, 0);
    assert.match(r.err, /impossibile ottenere il token di amministrazione .*\(HTTP 401 dopo 0 s\)/);
    assert.equal(kc.state.logins, 1, 'nessun nuovo tentativo con credenziali sbagliate');
  } finally { kc.server.close(); }
  kc = await mockKeycloak({ operators: [], bootstrapping: 1000 });
  try {
    const r = await runApply(kc.url, [], 'admin', { ...fast, KC_TOKEN_WAIT_S: '4' });
    assert.notEqual(r.code, 0);
    assert.match(r.err, /HTTP 503 dopo \d+ s/);
    assert.ok(kc.state.logins <= 6, `tetto rispettato (${kc.state.logins} tentativi)`);
  } finally { kc.server.close(); }
});

test('apply-overlay.sh di vetrina: fallisce se l\'OTP degli operatori non viene importato o se Keycloak non rispetta gli id fissi (nessun login di test funzionerebbe)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const noOtp = await mockKeycloak({ operators: [], dropOtp: true });
  try {
    const r = await runApply(noOtp.url, []);
    assert.notEqual(r.code, 0);
    assert.match(r.err, /credenziale OTP assente/);
    assert.match(r.err, /marta\.admin/);
  } finally { noOtp.server.close(); }
  const renumbered = await mockKeycloak({ operators: [], renumberIds: true });
  try {
    const r = await runApply(renumbered.url, []);
    assert.notEqual(r.code, 0);
    assert.match(r.err, /id fisso non e' stato rispettato/);
  } finally { renumbered.server.close(); }
});

// Utente del proprietario nel realm master (ADR-055, Q-727): password dal segreto del codespace, solo da ambiente.
const OWNER_PW = 'Proprietario-di-prova-2026';
const ownerUsers = (kc) => [...kc.state.users.master.values()];
const noOwnerSecret = (kc, r, ...secrets) => {
  for (const sec of secrets) {
    for (const [what, text] of [['stdout', r.out], ['stderr', r.err], ['richieste a Keycloak', kc.state.paths.join('\n')]]) {
      assert.ok(!text.includes(sec) && !text.includes(encodeURIComponent(sec)), `la password del proprietario compare in: ${what}`);
    }
  }
};

test('apply-overlay.sh di vetrina con LH_VETRINA_MASTER_ADMIN_PASSWORD (ADR-055, Q-727): utente permanente `proprietario` nel realm master, ruolo admin, password non temporanea verificata con un login, nessuna credenziale in uscita, idempotente e password aggiornabile', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const kc = await mockKeycloak({ operators: [] });
  try {
    const r = await runApply(kc.url, [], 'admin', { LH_VETRINA_MASTER_ADMIN_PASSWORD: OWNER_PW });
    assert.equal(r.code, 0, `apply-overlay.sh è fallito: ${r.err}${r.out}`);
    const [u, ...others] = ownerUsers(kc);
    assert.deepEqual(others, [], 'un solo utente');
    assert.equal(u.username, 'proprietario');
    assert.equal(u.enabled, true);
    assert.deepEqual(u.requiredActions, [], 'nessuna azione richiesta (bloccherebbe il login)');
    assert.deepEqual(u.realmRoles, ['admin'], 'ruolo admin del realm master');
    assert.equal(u.password, OWNER_PW);
    assert.equal(u.temporary, false, 'password NON temporanea');
    assert.equal(kc.state.ownerLogins, 1, 'un login di prova con la password');
    assert.match(r.out, /utente proprietario nel realm master con ruolo admin e password non temporanea \(login di prova riuscito\)/);
    // Il resto dell'overlay e' applicato come sempre.
    assert.equal(kc.state.realms.loyaltyhub.registrationAllowed, false);
    assert.equal(kc.state.realms.master.bruteForceProtected, true, 'blocco dei tentativi di master.json');
    assert.equal(kc.state.realms.master.failureFactor, 5);
    noOwnerSecret(kc, r, OWNER_PW, 'Test&Pass');
    // Idempotente, e una password nuova nel segreto sostituisce la vecchia (riavvio con segreto cambiato).
    const NEW_PW = 'Un-altra-password-2027';
    const r2 = await runApply(kc.url, [], 'admin', { LH_VETRINA_MASTER_ADMIN_PASSWORD: NEW_PW });
    assert.equal(r2.code, 0, r2.err);
    assert.equal(ownerUsers(kc).length, 1);
    assert.equal(ownerUsers(kc)[0].password, NEW_PW);
    assert.deepEqual(ownerUsers(kc)[0].realmRoles, ['admin']);
    noOwnerSecret(kc, r2, NEW_PW, OWNER_PW);
    // Le sole verifiche (--check, --check-operators) non creano niente.
    const before = JSON.stringify(ownerUsers(kc));
    assert.equal((await runApply(kc.url, ['--check-operators'], 'admin', { LH_VETRINA_MASTER_ADMIN_PASSWORD: ['Altra', 'ancora', '2028x'].join('-') })).code, 0);
    assert.equal(JSON.stringify(ownerUsers(kc)), before);
  } finally { kc.server.close(); }
});

test('apply-overlay.sh di vetrina senza LH_VETRINA_MASTER_ADMIN_PASSWORD (assente o vuota): nessun utente nel realm master, il resto applicato, uscita 0 (ADR-055)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  for (const env of [{}, { LH_VETRINA_MASTER_ADMIN_PASSWORD: '' }]) {
    const kc = await mockKeycloak({ operators: [] });
    try {
      const r = await runApply(kc.url, [], 'admin', env);
      assert.equal(r.code, 0, r.err);
      assert.equal(ownerUsers(kc).length, 0, 'nessun utente del proprietario');
      assert.equal(kc.state.ownerLogins, 0);
      assert.match(r.out, /utente del proprietario saltato \(segreto LH_VETRINA_MASTER_ADMIN_PASSWORD assente\): console master chiusa/);
      assert.equal(kc.state.realms.loyaltyhub.registrationAllowed, false, 'il resto e\' applicato');
      assert.ok(!kc.state.paths.some(p => /\/admin\/realms\/master\/users/.test(p)), 'nessuna chiamata sugli utenti di master');
    } finally { kc.server.close(); }
  }
});

test('apply-overlay.sh di vetrina: password del proprietario non valida (politica del realm master, rifiuto di Keycloak, login di prova) -> uscita 3 DOPO aver applicato il resto, messaggio chiaro, mai il valore (ADR-055)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const cases = [
    ['troppo corta', 'corta', {}, /non rispetta la politica del realm master \(almeno 12 caratteri e diversa dal nome utente proprietario\)\. Console master chiusa/, 0],
    ['uguale al nome utente', 'PROPRIETARIO', {}, /non rispetta la politica del realm master/, 0],
    ['rifiutata da Keycloak', ['Rifiutata', 'da', 'Keycloak', '1'].join('-'), { rejectOwnerPassword: true }, /Keycloak ha rifiutato la password del proprietario \(HTTP 400\): Invalid password: minimum length 12\.\s+Console master chiusa/, 1],
    ['login di prova fallito', ['Login', 'di', 'prova', 'fallisce', '1'].join('-'), { ownerLoginFails: true }, /login di prova dell'utente proprietario sul realm master non e' riuscito \(HTTP 401\)\. Console master chiusa/, 1],
  ];
  for (const [what, pw, mockOpts, msg, usersCreated] of cases) {
    const kc = await mockKeycloak({ operators: [], ...mockOpts });
    try {
      const r = await runApply(kc.url, [], 'admin', { LH_VETRINA_MASTER_ADMIN_PASSWORD: pw });
      assert.equal(r.code, 3, `${what}: uscita 3, non ${r.code}: ${r.err}`);
      assert.match(r.err, msg, what);
      assert.match(r.err, /il resto e' applicato, la console master resta chiusa/, what);
      assert.match(r.out, /Overlay di vetrina applicato ai realm master/, `${what}: il resto e' applicato`);
      assert.equal(kc.state.realms.loyaltyhub.registrationAllowed, false, `${what}: il resto e' applicato`);
      assert.equal(ownerUsers(kc).length, usersCreated, `${what}: utenti creati`);
      noOwnerSecret(kc, r, pw);
    } finally { kc.server.close(); }
  }
});

test('apply-overlay.sh di vetrina: MASTER_FRONTEND_URL non esiste piu\' (Q-670 superata da ADR-055): il realm master non riceve mai frontendUrl ne\' attributi', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const kc = await mockKeycloak({ operators: [] });
  try {
    const r = await runApply(kc.url, [], 'admin', { MASTER_FRONTEND_URL: 'https://prova-vetrina-8180.app.github.dev' });
    assert.equal(r.code, 0, r.err);
    assert.equal(kc.state.realms.master.frontendUrl, undefined);
    assert.equal(kc.state.realms.master.attributes, undefined, 'nessun frontendUrl in attributes');
    assert.equal(kc.state.logins, 1, 'un solo login di amministrazione: nessuna scrittura invalida il token');
  } finally { kc.server.close(); }
});

test('apply-overlay.sh di vetrina --check: valida i tre file senza rete e rifiuta un overlay con utenti non ammessi', { skip: !haveTools && 'servono bash e python3' }, () => {
  const ok = spawnSync('bash', [VETRINA_APPLY_PATH, '--check'], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /operatori=6 utenti, membri=5 utenti/);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lh-overlay-'));
  const cases = {
    'password temporanea': o => { o.users[0].credentials[0].temporary = true; },
    'azione richiesta': o => { o.users[0].requiredActions = ['UPDATE_PASSWORD']; },
    'senza LH_TEST_USER': o => { o.users[1].realmRoles = o.users[1].realmRoles.filter(r => r !== 'LH_TEST_USER'); },
    'operatore senza MFA_REQUIRED_ROLE': o => { o.users[2].realmRoles = o.users[2].realmRoles.filter(r => r !== 'MFA_REQUIRED_ROLE'); },
    'id mancante': o => { delete o.users[0].id; },
    'amministratore con manage-realm': o => { o.users[5].clientRoles['realm-management'].push('manage-realm'); },
    'amministratore con un ruolo applicativo': o => { o.users[5].realmRoles.push('ADMIN'); },
    'due semi TOTP': o => { o.users[1].credentials[1].secretData = JSON.stringify({ value: 'AltroSeme0123456789' }); },
  };
  for (const [name, mutate] of Object.entries(cases)) {
    const o = vetrina();
    mutate(o);
    const file = path.join(tmp, 'overlay.json');
    fs.writeFileSync(file, JSON.stringify(o));
    const r = spawnSync('bash', [VETRINA_APPLY_PATH, '--check'], { encoding: 'utf8', env: { ...process.env, OVERLAY: file } });
    assert.notEqual(r.status, 0, `${name}: doveva fallire`);
    assert.match(r.stderr, /^Errore:/m, name);
  }
  fs.rmSync(tmp, { recursive: true, force: true });
});

test('apply-overlay.sh di vetrina --no-test-users: master, impostazioni e client sì, ruolo e utenti di test no (vetrina fuori dal codespace, Q-676)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const kc = await mockKeycloak({ operators: [{ username: 'mario.rossi', realmRoles: ['ADMIN', 'MFA_REQUIRED_ROLE'] }] });
  try {
    const r = await runApply(kc.url, ['--no-test-users']);
    assert.equal(r.code, 0, r.err);
    assert.match(r.out, /Utenti di test del realm loyaltyhub: saltati/);
    assert.equal(kc.state.users.loyaltyhub.size + kc.state.users['loyaltyhub-members'].size, 0, 'nessun utente di test importato');
    assert.deepEqual(kc.state.roles.loyaltyhub, [], 'nessun ruolo LH_TEST_USER');
    assert.equal(kc.state.realms['loyaltyhub-members'].registrationAllowed, false);
    assert.equal(kc.state.realms.master.bruteForceProtected, true);
    assert.ok(kc.state.clients.some(c => c.clientId === 'lh-cli'));
  } finally { kc.server.close(); }
});
