#!/usr/bin/env node
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REALM_PATH = path.join(ROOT, 'deploy/idp/realm.json');
const OVERLAY_PATH = path.join(ROOT, 'deploy/idp/test-idp/realm-test-overlay.json');
const APPLY_OVERLAY_PATH = path.join(ROOT, 'deploy/idp/test-idp/apply-overlay.sh');
const VETRINA_OVERLAY_PATH = path.join(ROOT, 'deploy/idp/vetrina/realm-vetrina-overlay.json');
const VETRINA_APPLY_PATH = path.join(ROOT, 'deploy/idp/vetrina/apply-overlay.sh');
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

test('Nessun segreto letterale (secret, clientSecret, bindCredential) in realm.json e negli overlay (prova e vetrina)', () => {
  for (const file of [REALM_PATH, OVERLAY_PATH, VETRINA_OVERLAY_PATH]) {
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

test('Ogni segnaposto ${LH_*} di realm.json è passato al servizio idp nel compose', () => {
  const vars = new Set([...fs.readFileSync(REALM_PATH, 'utf8').matchAll(/\$\{(LH_[A-Z0-9_]+)\}/g)].map(m => m[1]));
  const compose = fs.readFileSync(COMPOSE_PATH, 'utf8');
  const block = compose.match(/\n  idp:\n([\s\S]*?)(?=\n  [a-z][\w-]*:\n)/);
  assert.ok(block, 'Servizio idp non trovato in deploy/docker-compose.yml');
  const missing = [...vars].filter(v => !new RegExp(`^\\s+${v}:`, 'm').test(block[1]));
  assert.deepEqual(missing, [], `Variabili non passate a idp (Keycloak lascerebbe il segnaposto e l'avvio fallisce): ${missing.join(', ')}`);
});

// Auto-registrazione dei membri (Q-557, D10, ADR-048, F2-IAM-03, docs/09 PT-16): registrazione aperta, senza verifica
// dell'e-mail. La verifica richiederebbe un `smtpServer`, cioè una nuova destinazione di rete in uscita (CLAUDE.md
// §7 «Fermati e chiedi»): si abilita solo con un'ADR, dopo il modulo `delivery` (M8.4). Senza verifica `email_verified`
// resta falso e nessun collegamento tra un account e un membro può fondarsi sull'e-mail (Q-558).
test('Registrazione aperta ai membri, senza verifica dell\'e-mail e senza SMTP (Q-557)', () => {
  assert.equal(realm.registrationAllowed, true, 'registrationAllowed deve essere true: sblocca PT-16 (auto-registrazione dei membri)');
  assert.ok('verifyEmail' in realm, 'verifyEmail deve essere dichiarato in modo esplicito (default di Keycloak implicito = decisione nascosta)');
  assert.equal(realm.verifyEmail, false, 'verifyEmail: true richiede un smtpServer (nuova destinazione di rete): serve un\'ADR (Q-557)');
  const smtp = realm.smtpServer;
  assert.ok(smtp === undefined || (typeof smtp === 'object' && smtp !== null && Object.keys(smtp).length === 0),
    'smtpServer non deve essere configurato nel realm: nuova destinazione di rete in uscita, serve un\'ADR (CLAUDE.md §7, Q-557)');
  // Un'azione richiesta di default «Verify Email» farebbe la stessa cosa a ogni nuovo utente, senza SMTP li bloccherebbe.
  for (const a of realm.requiredActions ?? []) {
    if (a.alias === 'VERIFY_EMAIL' || a.providerId === 'VERIFY_EMAIL') {
      assert.ok(!(a.enabled && a.defaultAction), 'VERIFY_EMAIL non deve essere un\'azione richiesta di default (serve SMTP)');
    }
  }
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
// Overlay di vetrina (F2-IAM-01, F2-IAM-03, ADR-048, ADR-049; Q-618, Q-619, Q-626: decise il 2026-09-30).
// La vetrina enterprise ospitata (docs/18 M8.14, V2) applica questo overlay al realm già avviato con
// deploy/idp/vetrina/apply-overlay.sh. Il realm base e l'overlay di prova restano invariati: qui si verifica che
// l'overlay chiuda la registrazione, non porti credenziali né utenti, non tocchi MFA e UPDATE_PASSWORD degli
// operatori e che l'unico client aggiunto (lh-cli, CLI dell'operatore) sia pubblico, solo Device Authorization Grant.
// ---------------------------------------------------------------------------------------------------------------------
const vetrina = () => JSON.parse(fs.readFileSync(VETRINA_OVERLAY_PATH, 'utf8'));
const OVERLAY_META_KEYS = ['_comment', 'realm', 'clients', 'scopeMappings'];
const OPERATOR_ROLES = ['ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST'];

test('Overlay di vetrina: solo impostazioni del realm ammesse e registrazione chiusa (Q-619)', () => {
  const o = vetrina();
  assert.equal(o.realm, 'loyaltyhub');
  assert.equal(o.registrationAllowed, false, 'registrationAllowed deve essere false nella vetrina: portale membri chiuso nel primo passo (Q-619)');
  assert.equal(realm.registrationAllowed, true, 'il realm base resta invariato (registrazione aperta, Q-557): la chiude solo l\'overlay di vetrina');
  // Nessun utente, ruolo, flusso, azione richiesta, broker, componente o SMTP: l'overlay non crea credenziali né allarga l'accesso.
  const settings = Object.keys(o).filter(k => !OVERLAY_META_KEYS.includes(k));
  assert.deepEqual(settings, ['registrationAllowed'], `l'overlay di vetrina ha chiavi non ammesse: ${settings.join(', ')}`);
  for (const forbidden of ['users', 'roles', 'groups', 'authenticationFlows', 'browserFlow', 'requiredActions', 'identityProviders', 'components', 'smtpServer', 'verifyEmail', 'defaultRole']) {
    assert.ok(!(forbidden in o), `l'overlay di vetrina non deve contenere ${forbidden}`);
  }
});

test('Overlay di vetrina: nessuna credenziale, nessun segreto, nessun segnaposto, nessun utente di prova', () => {
  const raw = fs.readFileSync(VETRINA_OVERLAY_PATH, 'utf8');
  const o = JSON.parse(raw);
  assert.ok(!raw.includes('${'), 'nessun segnaposto ${...}: l\'overlay non ha segreti da sostituire');
  assert.ok(!('users' in o) && !JSON.stringify(o).includes('"credentials"'), 'nessun utente né credenziali');
  assert.ok(!JSON.stringify(o).includes('testmember'), 'nessun utente di prova (testmember è dell\'overlay di prova)');
  // Nessuna chiave segreta, nemmeno come segnaposto: il client pubblico non ha nulla da custodire.
  assert.deepEqual(secretValues(o), [], 'nessuna chiave secret/clientSecret/bindCredential nell\'overlay di vetrina');
  const walk = (n, at = '$') => {
    if (Array.isArray(n)) n.forEach((v, i) => walk(v, `${at}[${i}]`));
    else if (n && typeof n === 'object') {
      for (const [k, v] of Object.entries(n)) {
        assert.ok(!/^(password|credentials|clientSecret|secret|bindCredential|privateKey|jwks\.string|jwt\.credential\.certificate)$/i.test(k), `chiave sensibile ${at}.${k}`);
        walk(v, `${at}.${k}`);
      }
    }
  };
  walk(o);
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
  const { _comment, realm: name, clients, scopeMappings, ...settings } = o;
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
  assert.deepEqual(copy.users, realm.users, 'nessun utente aggiunto, tolto o cambiato');
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

// Keycloak simulato: prova apply-overlay.sh (applicazione, rilettura, verifica degli operatori) senza un server reale.
// Regola 20: la password di amministrazione di prova e' un valore inventato del test, non un segreto.
function mockKeycloak({ operators }) {
  const state = {
    realm: { realm: 'loyaltyhub', registrationAllowed: true, browserFlow: 'browser-mfa', bruteForceProtected: true, otpPolicyType: 'totp',
      sslRequired: 'external', verifyEmail: false, passwordPolicy: 'length(12)' },
    clients: [], scope: [], puts: 0, loginBody: '',
  };
  const roleUsers = (role) => operators.filter(u => (u.realmRoles ?? []).includes(role)).map(u => ({ id: `id-${u.username}`, username: u.username }))
    .concat(role === 'ADMIN' ? [{ id: 'sa', username: 'service-account-x', serviceAccountClientId: 'x' }] : []);
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', d => { body += d; });
    req.on('end', () => {
      const url = new URL(req.url, 'http://x');
      const json = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
      const p = url.pathname;
      if (p === '/realms/master/protocol/openid-connect/token') { state.loginBody = body; return json(200, { access_token: 'mock-token' }); }
      if (req.headers.authorization !== 'Bearer mock-token') return json(401, {});
      const base = '/admin/realms/loyaltyhub';
      if (p === base && req.method === 'GET') return json(200, state.realm);
      if (p === base && req.method === 'PUT') { state.puts++; state.realm = JSON.parse(body); return json(204); }
      if (p === `${base}/partialImport`) {
        const b = JSON.parse(body);
        state.clients = state.clients.filter(c => !b.clients.some(n => n.clientId === c.clientId));
        for (const c of b.clients) state.clients.push({ id: `uuid-${c.clientId}`, ...c });
        state.scope = [];
        return json(200, { added: b.clients.length, overwritten: 0, skipped: 0, results: b.clients.map(c => ({ resourceType: 'CLIENT', resourceName: c.clientId, action: 'ADDED' })) });
      }
      if (p === `${base}/clients`) return json(200, state.clients.filter(c => c.clientId === url.searchParams.get('clientId')));
      let m;
      if ((m = p.match(/^\/admin\/realms\/loyaltyhub\/clients\/([^/]+)\/(optional-client-scopes|default-client-scopes|scope-mappings\/realm)$/))) {
        const c = state.clients.find(x => x.id === m[1]);
        if (!c) return json(404, {});
        if (m[2] === 'optional-client-scopes') return json(200, (c.optionalClientScopes ?? []).map(name => ({ name })));
        if (m[2] === 'default-client-scopes') return json(200, (c.defaultClientScopes ?? []).map(name => ({ name })));
        if (req.method === 'POST') { state.scope = JSON.parse(body); return json(204); }
        return json(200, state.scope);
      }
      if ((m = p.match(/^\/admin\/realms\/loyaltyhub\/roles\/([A-Z_]+)$/))) return json(200, { id: `role-${m[1]}`, name: m[1] });
      if ((m = p.match(/^\/admin\/realms\/loyaltyhub\/roles\/([A-Z_]+)\/users$/))) {
        const all = roleUsers(m[1]), first = Number(url.searchParams.get('first')), max = Number(url.searchParams.get('max'));
        return json(200, all.slice(first, first + max));
      }
      return json(404, { path: p });
    });
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve({ server, state, url: `http://127.0.0.1:${server.address().port}` })));
}

function runApply(url, args, userName = 'admin') {
  return new Promise(resolve => {
    const env = { ...process.env, KEYCLOAK_URL: url, KC_BOOTSTRAP_ADMIN_USERNAME: userName, KC_BOOTSTRAP_ADMIN_PASSWORD: 'Test&Pass=1 x' };
    const child = spawn('bash', [VETRINA_APPLY_PATH, ...args], { env });
    let out = '', err = '';
    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { err += d; });
    child.on('close', code => resolve({ code, out, err }));
  });
}

const haveTools = ['bash', 'curl', 'python3'].every(c => spawnSync('sh', ['-c', `command -v ${c}`]).status === 0);

test('apply-overlay.sh di vetrina contro un Keycloak simulato: applica, rilegge, non cambia altro e rifiuta un operatore senza MFA_REQUIRED_ROLE (Q-618, Q-626)', { skip: !haveTools && 'servono bash, curl e python3' }, async () => {
  const good = [
    { username: 'mario.rossi', realmRoles: ['ADMIN', 'MFA_REQUIRED_ROLE'] },
    { username: 'anna.verdi', realmRoles: ['ANALYST', 'MFA_REQUIRED_ROLE'] },
  ];
  // Molti operatori: prova la paginazione (200 per pagina) dell'elenco degli utenti di un ruolo.
  const many = Array.from({ length: 450 }, (_, i) => ({ username: `op${i}`, realmRoles: ['CARE', 'MFA_REQUIRED_ROLE'] }));
  const kc = await mockKeycloak({ operators: [...good, ...many] });
  try {
    const before = structuredClone(kc.state.realm);
    const r = await runApply(kc.url, [], 'adm&in=x');
    assert.equal(r.code, 0, `apply-overlay.sh è fallito: ${r.err}${r.out}`);
    assert.equal(kc.state.realm.registrationAllowed, false);
    assert.deepEqual({ ...kc.state.realm, registrationAllowed: true }, before, 'il PUT non cambia altre impostazioni del realm');
    const c = kc.state.clients.find(x => x.clientId === 'lh-cli');
    assert.ok(c && c.publicClient && c.consentRequired && c.fullScopeAllowed === false);
    assert.deepEqual(kc.state.scope.map(x => x.name).sort(), [...OPERATOR_ROLES].sort());
    assert.match(kc.state.loginBody, /username=adm%26in%3Dx&password=Test%26Pass%3D1%20x/, 'nome utente e password codificati');
    assert.match(r.out, /453 account|452 account|operatori: \d+ account/);
    assert.ok(!r.out.includes('Test&Pass') && !r.err.includes('Test&Pass') && !r.out.includes('mock-token'), 'nessuna credenziale in uscita');
    // Idempotente.
    assert.equal((await runApply(kc.url, [])).code, 0);
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
