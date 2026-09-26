#!/usr/bin/env node
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const REALM_PATH = path.join(process.cwd(), 'deploy/idp/realm.json');

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
