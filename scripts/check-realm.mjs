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

test('Tutti i 6 ruoli (ADMIN, MARKETING, LEGAL, CARE, ANALYST, MEMBER) sono presenti', () => {
  const roles = realm.roles.realm.map(r => r.name);
  const required = ['ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST', 'MEMBER'];
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
