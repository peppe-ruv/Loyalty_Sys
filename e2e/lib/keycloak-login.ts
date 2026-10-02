import crypto from 'node:crypto';
import { expect, type Page } from '@playwright/test';
import { freshTotp } from './totp.js';
import { WEB_URL } from './env.js';

// Login OIDC reale fatto dal browser (Authorization Code + PKCE dal client `web`, ADR-027, ADR-049): il BFF
// (/api/auth/login) manda alla pagina di Keycloak, il browser compila i moduli veri e torna al callback del BFF.
// Porta del flusso di scripts/smoke-enterprise.mjs (oidcLogin) su Playwright: stessi passi, stessa logica di TOTP.
//
// Regola 20: password, seme TOTP, codici, cookie e token restano in memoria e non compaiono in nessun messaggio. Gli
// errori di Playwright possono citare il valore di un `fill()` nel registro delle chiamate, quindi ogni errore del
// flusso è sostituito da un messaggio nostro con il solo passo, la pagina (senza query) e l'avviso di Keycloak.
// Chi chiama deve anche spegnere trace, video e screenshot del test (project `setup`, spec del membro): una traccia
// registra il corpo delle richieste, password incluse.

export type LoginStep = 'login' | 'password' | 'totp-setup' | 'otp';

const STEP_LABEL: Record<LoginStep, string> = {
  login: 'nome utente e password',
  password: 'cambio della password temporanea (UPDATE_PASSWORD)',
  'totp-setup': "configurazione dell'OTP (CONFIGURE_TOTP)",
  otp: 'codice OTP',
};

const MAX_STEPS = 15;
const STEP_TIMEOUT_MS = 60_000;

/** Campi che distinguono le pagine di Keycloak (pagine di terzi: si riconoscono dal nome del campo, non dal testo). */
const field = (page: Page, name: string) => page.locator(`input[name="${name}"]`);

type Classified = LoginStep | 'done' | 'wait';

async function classify(page: Page): Promise<Classified> {
  try {
    const url = new URL(page.url());
    if (url.origin === new URL(WEB_URL).origin) return url.pathname === '/api/auth/callback' || url.pathname.startsWith('/api/auth/callback/') ? 'wait' : 'done';
    if (url.protocol === 'about:') return 'wait';
    // Prima le azioni richieste: la pagina del cambio password porta anche `username` e `password` nascosti.
    if ((await field(page, 'password-new').count()) > 0) return 'password';
    if ((await field(page, 'totpSecret').count()) > 0 && (await field(page, 'totp').count()) > 0) return 'totp-setup';
    if ((await field(page, 'otp').count()) > 0) return 'otp';
    if ((await field(page, 'username').count()) > 0 && (await field(page, 'password').count()) > 0) return 'login';
  } catch {
    // Navigazione in corso: contesto distrutto o pagina non ancora pronta.
  }
  return 'wait';
}

async function nextStep(page: Page): Promise<Exclude<Classified, 'wait'>> {
  let found: Classified = 'wait';
  await expect
    .poll(async () => (found = await classify(page)), { timeout: STEP_TIMEOUT_MS, intervals: [100, 250, 500, 1000] })
    .not.toBe('wait');
  return found as Exclude<Classified, 'wait'>;
}

/** Avviso d'errore mostrato da Keycloak (testo della pagina, al più 200 caratteri) o ''. */
async function feedback(page: Page): Promise<string> {
  try {
    const el = page.locator('#input-error, [id^="input-error"], .kc-feedback-text, .alert-error, .pf-m-danger').first();
    if ((await el.count()) === 0) return '';
    return ((await el.innerText({ timeout: 2000 })) ?? '').replace(/\s+/g, ' ').trim().slice(0, 200);
  } catch {
    return '';
  }
}

async function submit(page: Page, marker: ReturnType<typeof field>): Promise<void> {
  // Il pulsante di invio del modulo che contiene il campo (non «Annulla» di una richiesta d'azione).
  const button = marker.locator('xpath=ancestor::form[1]').locator('input[type="submit"]:not([name="cancel-aia"]), button[type="submit"]:not([name="cancel-aia"])').first();
  const navigated = page.waitForEvent('framenavigated', { predicate: (f) => f === page.mainFrame(), timeout: STEP_TIMEOUT_MS });
  await button.click({ timeout: STEP_TIMEOUT_MS });
  await navigated;
}

export interface LoginResult {
  /** Passi attraversati sulle pagine dell'IdP, in ordine. */
  steps: LoginStep[];
  /** Percorso finale sul web (senza query). */
  landedOn: string;
}

/**
 * Login di `username` dal browser, a partire da /api/auth/login del BFF, fino al ritorno sul web. Gestisce:
 * password → (cambio password temporanea con una nuova casuale, solo in memoria) → configurazione dell'OTP (il seme si
 * legge dal campo `totpSecret` della pagina e il codice si calcola qui) → eventuale richiesta di OTP.
 * Si ferma sul web: l'esito (sessione aperta o rifiuto del BFF) lo verifica chi chiama.
 */
export async function loginThroughKeycloak(
  page: Page,
  opts: { username: string; password: string; returnTo?: string },
): Promise<LoginResult> {
  const { username } = opts;
  let password = opts.password;
  const steps: LoginStep[] = [];
  const seen = new Map<LoginStep, number>();
  let seed: string | null = null;
  let lastCounter = -1;
  let current: string = 'avvio';
  try {
    await page.goto(`/api/auth/login?returnTo=${encodeURIComponent(opts.returnTo ?? '/backoffice')}`, { waitUntil: 'domcontentloaded' });
    for (let i = 0; i < MAX_STEPS; i++) {
      const step = await nextStep(page);
      if (step === 'done') return { steps, landedOn: new URL(page.url()).pathname };
      current = STEP_LABEL[step];
      const count = (seen.get(step) ?? 0) + 1;
      seen.set(step, count);
      if (count > 1) {
        throw new Error(`l'IdP ripropone il passo «${current}»${(await feedback(page)) ? `: ${await feedback(page)}` : ''}`);
      }
      steps.push(step);
      if (step === 'password') {
        // Nuova password casuale, solo in memoria: l'utenza di prova non serve dopo il job.
        password = `${crypto.randomBytes(18).toString('base64url')}-Aa1`;
        await field(page, 'password-new').fill(password);
        await field(page, 'password-confirm').fill(password);
        await submit(page, field(page, 'password-new'));
      } else if (step === 'totp-setup') {
        const secret = await field(page, 'totpSecret').inputValue();
        if (!secret) throw new Error('la pagina di configurazione dell\'OTP non porta il segreto');
        seed = secret;
        const t = await freshTotp(seed, lastCounter);
        lastCounter = t.counter;
        await field(page, 'totp').fill(t.code);
        const label = field(page, 'userLabel');
        if ((await label.count()) > 0 && !(await label.inputValue())) await label.fill('e2e-ci');
        await submit(page, field(page, 'totp'));
      } else if (step === 'otp') {
        if (!seed) throw new Error("l'IdP chiede un OTP di un dispositivo che il test non conosce");
        const t = await freshTotp(seed, lastCounter);
        lastCounter = t.counter;
        await field(page, 'otp').fill(t.code);
        await submit(page, field(page, 'otp'));
      } else {
        await field(page, 'username').fill(username);
        await field(page, 'password').fill(password);
        await submit(page, field(page, 'password'));
      }
    }
    throw new Error(`non concluso entro ${MAX_STEPS} passi`);
  } catch (e) {
    const where = (() => {
      try {
        const u = new URL(page.url());
        return `${u.origin}${u.pathname}`;
      } catch {
        return 'pagina sconosciuta';
      }
    })();
    const hint = e instanceof Error && e.message.startsWith("l'IdP ripropone") ? ` (${e.message}). Se l'operatore ha già fatto il primo accesso, ricreare lo stack: bash scripts/smoke-enterprise.sh down && up` : '';
    // Mai il messaggio originale di Playwright: il registro delle chiamate può riportare valori compilati.
    const kind = e instanceof Error ? e.name : 'errore';
    // Pagina vuota prima di uscire: l'istantanea della pagina che Playwright allega a un test fallito non deve
    // riportare il segreto OTP mostrato da Keycloak.
    await page.goto('about:blank').catch(() => undefined);
    throw new Error(`login di ${username} non riuscito al passo «${current}» su ${where} (${kind})${hint}`);
  }
}
