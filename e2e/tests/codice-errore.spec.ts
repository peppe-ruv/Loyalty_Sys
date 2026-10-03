import { expect, test, type Response } from '@playwright/test';

// Specifiche: F2-QA-06, ADR-052 decisione 3, Q-684 opzione A, Q-716 (scelte A1, B1, C1), docs/07 §6 Error.
//
// Il guasto si forza senza endpoint nuovi: un premio che non esiste (`GET /v1/rewards/<id>`) dà un 404 NOT_FOUND del
// servizio reward, un errore 4xx inatteso per la schermata (B1: il codice si mostra, non è un errore di campo). Il codice
// che il riquadro mostra deve essere quello della risposta del servizio: la proprietà `correlationId` del problema
// RFC 9457, uguale all'intestazione `X-Correlation-Id` che il BFF restituisce (e che il servizio ha messo nel suo log).
// Il caricamento si riprova una volta (docs/07 §6): due richieste, due codici; il riquadro mostra quello dell'ultima.

test('il riquadro d\'errore mostra il codice dell\'errore, uguale a X-Correlation-Id della risposta', async ({ page }) => {
  const missing = 'RWD-E2E-INESISTENTE';
  const seen: Response[] = [];
  page.on('response', (r) => {
    if (new URL(r.url()).pathname === `/api/lh/reward/v1/rewards/${missing}`) seen.push(r);
  });

  await page.goto(`/backoffice/rewards/${missing}`);

  const box = page.getByRole('alert').filter({ hasText: "Codice dell'errore" });
  await expect(box).toBeVisible({ timeout: 30_000 });
  await expect(box.getByRole('button', { name: 'Riprova' })).toBeVisible();
  await expect(box.getByRole('button', { name: 'Copia il codice' })).toBeVisible();

  const shown = (await box.locator('code').innerText()).trim();
  expect(shown).toMatch(/^[A-Za-z0-9-]{1,64}$/);

  await test.step('ogni risposta d\'errore porta lo stesso codice nell\'intestazione e nel problema', async () => {
    expect(seen.length).toBeGreaterThan(0);
    for (const r of seen) {
      expect(r.status()).toBe(404);
      const header = r.headers()['x-correlation-id'];
      expect(header, 'intestazione X-Correlation-Id della risposta').toBeTruthy();
      const problem = (await r.json()) as { correlationId?: string };
      expect(problem.correlationId).toBe(header);
    }
  });

  await test.step('il codice mostrato è quello dell\'ultima risposta', async () => {
    const last = seen[seen.length - 1]!;
    expect(shown).toBe(last.headers()['x-correlation-id']);
  });

  await test.step('«Copia il codice» annuncia «Codice copiato»', async () => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await box.getByRole('button', { name: 'Copia il codice' }).click();
    await expect(box.getByRole('status')).toHaveText('Codice copiato');
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(shown);
  });
});
