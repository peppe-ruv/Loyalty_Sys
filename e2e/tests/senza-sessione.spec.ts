import { expect, test } from '@playwright/test';
import { bffRequest } from '../lib/bff.js';

// Specifiche: F2-IAM-01, docs/07 §4-bis (BFF senza sessione), ADR-042 (zero trust: nessun endpoint senza ruolo),
// ADR-052 decisione 1, F2-QA-01. Browser nuovo, senza cookie.

test.use({ storageState: { cookies: [], origins: [] } });

test('senza sessione le API del BFF rispondono 401 e le pagine protette portano al login', async ({ page }) => {
  await test.step('API del backoffice senza sessione: 401 UNAUTHENTICATED', async () => {
    await page.goto('/api/demo/status');
    const res = await bffRequest(page, '/api/lh/reward/v1/reward-categories');
    expect(res.status).toBe(401);
    expect(res.code).toBe('UNAUTHENTICATED');
  });

  await test.step('il backoffice senza sessione porta al login di Keycloak (client web, PKCE S256)', async () => {
    await page.goto('/backoffice');
    await expect(page).toHaveURL(/\/realms\/loyaltyhub\/protocol\/openid-connect\/auth\?/);
    const params = new URL(page.url()).searchParams;
    expect(params.get('client_id')).toBe('web');
    expect(params.get('code_challenge_method')).toBe('S256');
    expect(params.get('response_type')).toBe('code');
    // Modulo di accesso vero dell'IdP (pagina di terzi: si riconosce dal campo, non dal testo).
    await expect(page.locator('input[name="username"]')).toBeVisible();
  });
});
