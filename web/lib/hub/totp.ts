import { createHmac } from "node:crypto";

// HUB-02 — codice OTP del momento per gli operatori di test (RFC 6238, HMAC-SHA-1, 6 cifre, 30 s; ADR-051, Q-676).
// SOLO LATO SERVER (node:crypto). Il seme è quello pubblico dell'ambiente di test (`lib/hub/testUsers.ts`).

export const TOTP_PERIOD_SECONDS = 30;
export const TOTP_DIGITS = 6;

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** Base32 RFC 4648 (senza padding obbligatorio, maiuscole o minuscole) in byte. Carattere fuori alfabeto ⇒ errore. */
export function base32Decode(input: string): Buffer {
  const clean = input.replace(/=+$/, "").toUpperCase();
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = BASE32.indexOf(ch);
    if (idx < 0) throw new Error("seme base32 non valido");
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** HOTP (RFC 4226) con troncamento dinamico. `counter` intero non negativo. */
export function hotp(secret: Buffer, counter: number, digits = TOTP_DIGITS, algorithm: "sha1" | "sha256" | "sha512" = "sha1"): string {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const mac = createHmac(algorithm, secret).update(msg).digest();
  const offset = mac[mac.length - 1] & 0x0f;
  const bin = ((mac[offset] & 0x7f) << 24) | (mac[offset + 1] << 16) | (mac[offset + 2] << 8) | mac[offset + 3];
  return String(bin % 10 ** digits).padStart(digits, "0");
}

/** Codice del momento e secondi che restano prima del prossimo (da 1 a 30). */
export function totp(
  secretBase32: string,
  nowMs: number = Date.now(),
  opts: { period?: number; digits?: number; algorithm?: "sha1" | "sha256" | "sha512" } = {},
): { code: string; remainingSeconds: number } {
  const period = opts.period ?? TOTP_PERIOD_SECONDS;
  const seconds = Math.floor(nowMs / 1000);
  const code = hotp(base32Decode(secretBase32), Math.floor(seconds / period), opts.digits ?? TOTP_DIGITS, opts.algorithm ?? "sha1");
  return { code, remainingSeconds: period - (seconds % period) };
}
