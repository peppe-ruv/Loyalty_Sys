import { createCipheriv, createDecipheriv, createHash, createHmac, hkdfSync, randomBytes, timingSafeEqual } from "node:crypto";

// Primitive crittografiche del BFF (docs/18 §3.2, CLAUDE.md regola 20). SOLO LATO SERVER.
// Dalla chiave maestra `LH_WEB_SESSION_KEY` si derivano, con HKDF-SHA256, chiavi separate per scopo: una chiave
// compromessa in un uso non vale negli altri. Cifratura AES-256-GCM con IV casuale da 12 byte e dati associati (AAD)
// che legano il testo cifrato al suo contesto (es. l'id della sessione): un blob copiato altrove non si apre.

export type KeyPurpose = "session" | "auth-flow" | "csrf";

const IV_BYTES = 12;
const TAG_BYTES = 16;

/** Chiave da 32 byte per uno scopo, derivata dalla chiave maestra. */
export function deriveKey(master: Buffer, purpose: KeyPurpose): Buffer {
  return Buffer.from(hkdfSync("sha256", master, "io.loyaltyhub.web", `lh-${purpose}-v1`, 32));
}

/** Cifra `plaintext` legandolo ad `aad`. Formato base64url: `iv(12) ‖ tag(16) ‖ testo cifrato`. */
export function seal(plaintext: string, key: Buffer, aad: string): string {
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const body = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64url");
}

/** Apre un blob di `seal`; `null` se è stato alterato, se la chiave o l'AAD non corrispondono o se è malformato. */
export function open(sealed: string, key: Buffer, aad: string): string | null {
  try {
    const raw = Buffer.from(sealed, "base64url");
    if (raw.length < IV_BYTES + TAG_BYTES) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, raw.subarray(0, IV_BYTES));
    decipher.setAAD(Buffer.from(aad, "utf8"));
    decipher.setAuthTag(raw.subarray(IV_BYTES, IV_BYTES + TAG_BYTES));
    return Buffer.concat([decipher.update(raw.subarray(IV_BYTES + TAG_BYTES)), decipher.final()]).toString("utf8");
  } catch {
    return null;
  }
}

/** Identificatore opaco da 256 bit (id di sessione). */
export function randomId(): string {
  return randomBytes(32).toString("base64url");
}

/** Impronta SHA-256 in base64url: chiave interna dello store, così la memoria non contiene cookie utilizzabili. */
export function digest(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("base64url");
}

/** HMAC-SHA256 in base64url. */
export function hmac(key: Buffer, value: string): string {
  return createHmac("sha256", key).update(value, "utf8").digest("base64url");
}

/** Confronto a tempo costante di due stringhe (lunghezze diverse ⇒ `false`). */
export function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}
