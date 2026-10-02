import fs from 'node:fs';
import { expect, test as setup } from '@playwright/test';
import { loginThroughKeycloak } from '../lib/keycloak-login.js';
import { OPERATOR, OPERATOR_CREDENTIALS, ensureStateDir, operatorStatePath, readCredentials } from '../lib/env.js';
import { SESSION_COOKIE } from '../lib/bff.js';

// Specifiche: F2-IAM-01 (login OIDC con MFA), BO-01 (dashboard), docs/07 §4-bis (login del BFF), ADR-027, ADR-049,
// ADR-052 decisione 1 (cancello e2e-pr), F2-QA-01.
// È il test (a) e anche la preparazione degli altri: salva la sessione dell'operatore (cookie, non la password) in una
// cartella 0700 fuori dagli artefatti. Il progetto `setup` ha traccia, video e screenshot spenti (regola 20).

setup('l\'operatore accede con il login reale di Keycloak e la MFA e arriva alla dashboard del backoffice', async ({ page, context }) => {
  setup.setTimeout(240_000);
  const password = readCredentials(OPERATOR_CREDENTIALS, OPERATOR);

  const { steps, landedOn } = await loginThroughKeycloak(page, { username: OPERATOR, password, returnTo: '/backoffice' });

  // La MFA del realm è scattata: configurazione del primo OTP (primo accesso) oppure richiesta del codice.
  expect(steps.some((s) => s === 'totp-setup' || s === 'otp'), 'la MFA degli operatori non è scattata').toBe(true);
  expect(landedOn).toBe('/backoffice');

  // Dashboard BO-01: titolo di primo livello e uscita dell'operatore (profilo enterprise).
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Esci' })).toBeVisible();

  const cookies = await context.cookies();
  expect(cookies.some((c) => c.name === SESSION_COOKIE && c.httpOnly && c.secure), 'cookie di sessione del BFF assente o senza HttpOnly e Secure').toBe(true);

  ensureStateDir();
  const file = operatorStatePath();
  fs.writeFileSync(file, JSON.stringify(await context.storageState()), { mode: 0o600 });
  fs.chmodSync(file, 0o600);
});
