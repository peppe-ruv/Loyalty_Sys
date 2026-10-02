import fs from 'node:fs';

// Cancella la cartella della sessione dell'operatore (cookie di sessione e CSRF), se l'ha creata questa esecuzione.
export default function globalTeardown(): void {
  const dir = process.env.LH_E2E_STATE_DIR;
  if (dir && process.env.LH_E2E_STATE_DIR_OWNED === '1') fs.rmSync(dir, { recursive: true, force: true });
}
