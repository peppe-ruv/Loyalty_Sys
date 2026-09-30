const ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

function ulid(now = Date.now()) {
  let time = now;
  const timeChars = [];
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

console.log(ulid());
