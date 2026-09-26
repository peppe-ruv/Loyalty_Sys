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
