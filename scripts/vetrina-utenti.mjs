// Utenti di test della vetrina enterprise (ADR-051 decisione 1; Q-671, Q-672, Q-673). Fonte unica: gli overlay dei
// realm in deploy/idp/vetrina/. Le password e il seme TOTP sono PUBBLICI e documentati (runbook e pagina Mintlify); lo
// smoke e lo script dei membri li leggono da qui invece di duplicarli. Mai stampare un valore: servono solo ai login.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** Codifica base32 (RFC 4648, senza riempimento): come mostra il seme un'app OTP. */
export function base32Encode(buf) {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const b of buf) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += BASE32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32[(value << (5 - bits)) & 31];
  return out;
}

/**
 * Legge gli overlay di vetrina e restituisce gli utenti di test per realm: `{ operators, members, totpSecret }`.
 * `operators` e `members` sono mappe `nome utente → { username, password, firstName, lastName, email, id, realmRoles }`;
 * `totpSecret` è il seme condiviso degli operatori come Keycloak lo tiene (byte UTF-8, la chiave dell'HMAC del TOTP).
 */
export function loadTestUsers({ root = ROOT } = {}) {
  const read = (rel) => JSON.parse(fs.readFileSync(path.join(root, 'deploy/idp/vetrina', rel), 'utf8'));
  const byName = (doc) => {
    const out = {};
    for (const u of doc.users ?? []) {
      const password = (u.credentials ?? []).find((c) => c.type === 'password')?.value;
      out[u.username] = { username: u.username, password, firstName: u.firstName, lastName: u.lastName, email: u.email, id: u.id, realmRoles: u.realmRoles ?? [] };
    }
    return out;
  };
  const operatorsDoc = read('realm-vetrina-overlay.json');
  const secrets = new Set(operatorsDoc.users.flatMap((u) => (u.credentials ?? []).filter((c) => c.type === 'otp').map((c) => JSON.parse(c.secretData).value)));
  if (secrets.size !== 1) throw new Error('gli operatori di test devono condividere un solo seme TOTP');
  const [totpSecret] = secrets;
  return { operators: byName(operatorsDoc), members: byName(read('realm-members-vetrina-overlay.json')), totpSecret };
}
