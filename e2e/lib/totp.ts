import crypto from 'node:crypto';

/**
 * TOTP (RFC 6238) come lo calcola Keycloak: chiave = byte UTF-8 del segreto che la pagina di configurazione porta nel
 * campo nascosto `totpSecret`, HMAC-SHA1, 6 cifre, periodo 30 s (politica OTP predefinita; il realm non la cambia).
 * Stesso calcolo di scripts/smoke-enterprise.mjs. Il codice resta in memoria e non si stampa mai.
 */
export function totp(secret: string, timeMs: number, digits = 6, period = 30): string {
  const counter = Math.floor(timeMs / 1000 / period);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const mac = crypto.createHmac('sha1', Buffer.from(secret, 'utf8')).update(msg).digest();
  const off = (mac[mac.length - 1] ?? 0) & 0x0f;
  const bin = (((mac[off] ?? 0) & 0x7f) << 24) | ((mac[off + 1] ?? 0) << 16) | ((mac[off + 2] ?? 0) << 8) | (mac[off + 3] ?? 0);
  return String(bin % 10 ** digits).padStart(digits, '0');
}

/**
 * Prossimo codice utilizzabile: Keycloak rifiuta un codice già usato e un codice a ridosso della scadenza può
 * scadere in volo, quindi si attende la finestra successiva (attesa calcolata dall'orologio, non una pausa fissa).
 */
export async function freshTotp(secret: string, lastCounter: number): Promise<{ code: string; counter: number }> {
  for (let i = 0; i < 4; i++) {
    const nowS = Date.now() / 1000;
    const counter = Math.floor(nowS / 30);
    const left = 30 - (nowS % 30);
    if (counter > lastCounter && left > 3) return { code: totp(secret, Date.now()), counter };
    await new Promise((r) => setTimeout(r, Math.ceil(left * 1000) + 250));
  }
  throw new Error('OTP: orologio fermo');
}
