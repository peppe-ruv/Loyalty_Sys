import { expect, test } from '@playwright/test';
import { SESSION_COOKIE, bffRequest } from '../lib/bff.js';
import { loginThroughKeycloak } from '../lib/keycloak-login.js';
import { MEMBER, MEMBER_CREDENTIALS, readCredentials } from '../lib/env.js';

// Specifiche: ADR-042 (zero trust, ruolo per ogni endpoint), ADR-051 (realm dei membri separato), docs/07 §4-bis
// (account membro nel backoffice), @RequiresRole dei servizi, ADR-052, F2-QA-01.
// Traccia, video e screenshot spenti: il test compila la password del membro (regola 20).

test.use({ storageState: { cookies: [], origins: [] }, trace: 'off', video: 'off', screenshot: 'off' });

test('un membro di prova non apre il backoffice né le sue API', async ({ page, context }) => {
  test.setTimeout(180_000);
  const password = readCredentials(MEMBER_CREDENTIALS, MEMBER);

  const { landedOn } = await loginThroughKeycloak(page, { username: MEMBER, password, returnTo: '/backoffice' });
  const hasSession = (await context.cookies()).some((c) => c.name === SESSION_COOKIE);

  // Due esiti ammessi, come in scripts/smoke-enterprise.mjs: il BFF rifiuta al callback un account di soli membri
  // sul login del backoffice (nessuna sessione), oppure apre la sessione e il backoffice/le API dicono «vietato».
  if (!hasSession) {
    test.info().annotations.push({ type: 'esito', description: 'rifiutato dal BFF, nessuna sessione' });
    expect(landedOn).toBe('/auth/error');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    const res = await bffRequest(page, '/api/lh/reward/v1/reward-categories');
    expect(res.status).toBe(401);
    expect(res.code).toBe('UNAUTHENTICATED');
    return;
  }

  test.info().annotations.push({ type: 'esito', description: 'sessione aperta, API vietate' });
  await page.goto('/backoffice');
  await expect(page.getByRole('heading', { level: 1, name: 'Accesso al backoffice non consentito' })).toBeVisible();
  const res = await bffRequest(page, '/api/lh/reward/v1/reward-categories');
  expect(res.status).toBe(403);
  expect(res.code).toBe('FORBIDDEN_ROLE');
});
