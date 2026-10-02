import fc from "fast-check";

// Proprietà generative del web (ADR-053 decisione 1, Q-690, docs/06 §9): fast-check, solo scope di test.
// 1000 esecuzioni per proprietà in PR. Il seme è stampato nel messaggio di un fallimento («Property failed after N tests
// { seed: …, path: … }»): per riprodurre, `FC_SEED=<seme> pnpm test`. Un controesempio trovato diventa un test a esempi
// permanente nello stesso file, accanto alle proprietà.

export const NUM_RUNS = 1000;

const envSeed = process.env.FC_SEED;

/** Esegue una proprietà con 1000 tentativi e, se serve, un seme fissato da `FC_SEED`. */
export function assertProperty<T extends unknown[]>(property: fc.IPropertyWithHooks<T>): void {
  fc.assert(property, { numRuns: NUM_RUNS, ...(envSeed ? { seed: Number(envSeed) } : {}) });
}

export { fc };
