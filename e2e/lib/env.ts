import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// Configurazione dell'harness (ADR-052, M9.1). Lo stack è quello di `bash scripts/smoke-enterprise.sh up`:
// compose di riferimento in profilo enterprise con l'overlay di test del realm. Nessun valore segreto è qui: le
// credenziali si leggono dai file 0600 della cartella dello smoke (regola 20).

/** Origine del web (BFF) dietro il proxy TLS di prova. */
export const WEB_URL = (process.env.LH_E2E_WEB_URL ?? 'https://web.lh.test:8443').replace(/\/+$/, '');

/** Cartella dello smoke enterprise: la stessa di scripts/smoke-enterprise.sh (LH_CI_DIR o $RUNNER_TEMP/lh-smoke-enterprise). */
export const CI_DIR = process.env.LH_CI_DIR ?? path.join(process.env.RUNNER_TEMP ?? '/tmp', 'lh-smoke-enterprise');

export const OPERATOR = process.env.LH_E2E_OPERATOR ?? 'marta.admin';
export const MEMBER = process.env.LH_E2E_MEMBER ?? 'testmember';
export const OPERATOR_CREDENTIALS = process.env.LH_E2E_OPERATOR_CREDENTIALS ?? path.join(CI_DIR, 'secrets', 'operatori.txt');
export const MEMBER_CREDENTIALS = process.env.LH_E2E_MEMBER_CREDENTIALS ?? path.join(CI_DIR, 'secrets', 'membro.txt');

const SERVER_CERT = process.env.LH_E2E_SERVER_CERT ?? path.join(CI_DIR, 'tls', 'server.crt');

/**
 * Impronta SPKI (sha256, base64) del certificato del proxy di prova, per `--ignore-certificate-errors-spki-list` di
 * Chromium: il browser accetta SOLO la chiave di quel certificato (che vale per web.lh.test e idp.lh.test), non
 * disattiva la verifica TLS in generale. Senza certificato di prova (ad esempio contro uno stack con CA pubblica) è
 * `null` e il browser usa le CA di sistema.
 */
export function serverSpkiPin(): string | null {
  if (!fs.existsSync(SERVER_CERT)) return null;
  const cert = new crypto.X509Certificate(fs.readFileSync(SERVER_CERT));
  const spki = cert.publicKey.export({ type: 'spki', format: 'der' });
  return crypto.createHash('sha256').update(spki).digest('base64');
}

/**
 * Cartella 0700 per la sessione dell'operatore, fuori dal repository e fuori dalle cartelle caricate come
 * artefatto (test-results, playwright-report). Il nome lo sceglie la sola configurazione del processo principale (i
 * worker lo ereditano dall'ambiente); la cartella si crea quando serve (`ensureStateDir`) e la cancella
 * `global-teardown.ts`.
 */
export function stateDir(): string {
  if (!process.env.LH_E2E_STATE_DIR) {
    process.env.LH_E2E_STATE_DIR = path.join(os.tmpdir(), `lh-e2e-${crypto.randomBytes(8).toString('hex')}`);
    process.env.LH_E2E_STATE_DIR_OWNED = '1';
  }
  return process.env.LH_E2E_STATE_DIR;
}

export function ensureStateDir(): string {
  const dir = stateDir();
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  return dir;
}

export const operatorStatePath = (): string => path.join(stateDir(), 'operatore.json');

/**
 * Password di `username` da un file `utente: password` (formato di deploy/idp/bootstrap.sh, stessa logica di
 * scripts/smoke-enterprise.mjs): file regolare, non un collegamento simbolico, permessi 0600 o più stretti. La
 * password non finisce mai in un messaggio d'errore.
 */
export function readCredentials(file: string, username: string): string {
  let fd: number;
  try {
    fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0));
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    throw new Error(`file delle credenziali non leggibile: ${file} (${code === 'ELOOP' ? 'collegamento simbolico rifiutato' : code}). Eseguire prima: bash scripts/smoke-enterprise.sh up`);
  }
  try {
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw new Error(`file delle credenziali non regolare: ${file}`);
    if ((st.mode & 0o077) !== 0) throw new Error(`file delle credenziali con permessi troppo larghi (serve 0600): ${file}`);
    if (st.size > 64 * 1024) throw new Error(`file delle credenziali troppo grande: ${file}`);
    for (const line of fs.readFileSync(fd, 'utf8').split('\n')) {
      if (line.startsWith('#')) continue;
      const sep = line.indexOf(': ');
      if (sep <= 0 || line.slice(0, sep) !== username) continue;
      const pw = line.slice(sep + 2).replace(/\r$/, '');
      if (!pw || pw.startsWith('(')) break;
      return pw;
    }
    throw new Error(`nessuna password per ${username} in ${file}`);
  } finally {
    fs.closeSync(fd);
  }
}
