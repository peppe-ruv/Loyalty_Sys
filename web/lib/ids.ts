// ULID minimale per il proxy (X-Correlation-Id quando assente, docs/07 §3).
// Allineato all'alfabeto Crockford Base32 del backend (io.loyaltyhub.common.ids.Ulid).

const ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

export function ulid(now: number = Date.now()): string {
  let time = now;
  const timeChars: string[] = [];
  for (let i = 9; i >= 0; i--) {
    timeChars[i] = ENCODING[time % 32];
    time = Math.floor(time / 32);
  }
  let rand = "";
  for (let i = 0; i < 16; i++) {
    rand += ENCODING[Math.floor(Math.random() * 32)];
  }
  return timeChars.join("") + rand;
}
