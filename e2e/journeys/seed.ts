import crypto from 'node:crypto';
import { MAX_SEED } from './generator.js';

/** Seme casuale in 1…2^32-1 da una sorgente crittografica (solo per scegliere un seme, non per segreti). */
export function randomSeed(): number {
  return crypto.randomInt(1, MAX_SEED + 1);
}
