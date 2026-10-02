import { expect, test } from '@playwright/test';
import { bffRequest } from '../lib/bff.js';
import { OPERATOR } from '../lib/env.js';

// Specifiche: regola 21 e ADR-043 (ogni scrittura di configurazione lascia una voce in audit_entry con l'attore reale
// dal token), BO-22 (schermata Audit), F-INS (GET /v1/audit), docs/07 §4-bis (CSRF del BFF), ADR-052, F2-QA-01.
//
// La scrittura è «crea categoria premi» (POST /v1/reward-categories), la più semplice scrittura di configurazione
// vera: il backoffice non ha una schermata per crearla, quindi parte dal contesto della pagina autenticata, dal
// proxy del BFF e con il token CSRF, come le chiamate del frontend (stessa scelta di scripts/smoke-enterprise.mjs).
// La voce si verifica dall'API di audit e poi nella schermata Audit del backoffice.

test('una scrittura di configurazione lascia una voce di audit con l\'attore reale', async ({ page }) => {
  test.setTimeout(240_000);
  const code = `E2E${Date.now().toString(36).toUpperCase()}`;
  const since = new Date(Date.now() - 60_000).toISOString();

  await page.goto('/backoffice/governance/audit');
  await expect(page.getByRole('heading', { level: 1, name: 'Audit' })).toBeVisible();

  await test.step('senza il token CSRF la scrittura è rifiutata (403 CSRF_REJECTED)', async () => {
    // Stessa richiesta senza l'intestazione: il helper la aggiunge sempre, quindi si manda con fetch diretto.
    const res = await page.evaluate(async (body) => {
      const r = await fetch('/api/lh/reward/v1/reward-categories', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        credentials: 'same-origin',
      });
      const j = (await r.json().catch(() => null)) as { code?: unknown; error?: unknown } | null;
      return { status: r.status, code: typeof (j?.code ?? j?.error) === 'string' ? String(j?.code ?? j?.error) : null };
    }, { code, name: 'E2E senza CSRF', icon: 'sparkles', sortOrder: 98 });
    expect(res).toEqual({ status: 403, code: 'CSRF_REJECTED' });
  });

  await test.step(`crea la categoria premi ${code} dal BFF (201)`, async () => {
    const res = await bffRequest(page, '/api/lh/reward/v1/reward-categories', {
      method: 'POST',
      body: { code, name: 'E2E harness', icon: 'sparkles', sortOrder: 99 },
    });
    expect(res.status, `POST categoria premi: ${res.code ?? 'nessun codice'}`).toBe(201);
  });

  await test.step('GET /v1/audit: voce CREATE con l\'attore reale dell\'operatore', async () => {
    const query = new URLSearchParams({ entityType: 'REWARD_CATEGORY', entityId: code, from: since });
    await expect
      .poll(
        async () => {
          const res = await bffRequest(page, `/api/lh/insight/v1/audit?${query}`);
          if (res.status !== 200) return `HTTP ${res.status}`;
          const items = ((res.json as { items?: Array<Record<string, unknown>> } | null)?.items ?? []).filter(
            (r) => r.entityId === code && String(r.action ?? '').toUpperCase() === 'CREATE',
          );
          return items.length === 0 ? 'nessuna voce' : [...new Set(items.map((r) => String(r.actorName ?? '?')))].join(',');
        },
        { message: 'voce di audit dal bus all\'insight', timeout: 120_000, intervals: [1000, 2000, 5000] },
      )
      .toBe(OPERATOR);
  });

  await test.step('la schermata Audit mostra la voce con l\'attore e l\'azione', async () => {
    await page.getByLabel('Attore').fill(OPERATOR);
    await page.getByLabel('Servizio').selectOption('reward');
    const row = page.getByRole('row').filter({ hasText: code });
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row).toContainText(OPERATOR);
    await expect(row).toContainText('CREATE');
  });
});
