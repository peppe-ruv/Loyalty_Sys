// ULID minimale per il proxy (X-Correlation-Id, docs/07 §3) e per gli id degli eventi
// del pannello demo del portale (deduplica su source+id, docs/05 §2).
// 80 bit casuali da crypto.getRandomValues, come io.loyaltyhub.common.ids.Ulid (SecureRandom);
// alfabeto Crockford Base32 allineato al backend.

const ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

export function ulid(now: number = Date.now()): string {
  let time = now;
  const timeChars: string[] = [];
  for (let i = 9; i >= 0; i--) {
    timeChars[i] = ENCODING[time % 32];
    time = Math.floor(time / 32);
  }
  let rand = "";
  const randomValues = new Uint8Array(16);
  crypto.getRandomValues(randomValues);
  for (let i = 0; i < 16; i++) {
    rand += ENCODING[randomValues[i] % 32];
  }
  return timeChars.join("") + rand;
}
