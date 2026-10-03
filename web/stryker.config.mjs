// Mutation testing del web (F2-QA-07, ADR-053 decisione 2, Q-691): consultivo, solo sui file cambiati.
// Uso: `bash scripts/mutation.sh web` (calcola i file cambiati rispetto a origin/main e passa --mutate).
// Senza --mutate (esecuzione manuale) muta tutta la logica: lento, serve solo in locale.
/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
const config = {
  // Con pnpm i plugin non si scoprono da soli: vanno nominati.
  plugins: ["@stryker-mutator/vitest-runner"],
  testRunner: "vitest",
  vitest: { configFile: "vitest.config.ts" },
  mutate: [
    "lib/**/*.{ts,tsx}",
    "components/**/*.{ts,tsx}",
    "app/**/*.{ts,tsx}",
    "!**/*.test.{ts,tsx}",
    "!**/*.d.ts",
  ],
  // Modalità incrementale (Q-691): il file di stato riusa i risultati dei mutanti non toccati.
  incremental: true,
  incrementalFile: "reports/stryker-incremental.json",
  reporters: ["clear-text", "html", "json"],
  htmlReporter: { fileName: "reports/mutation/index.html" },
  jsonReporter: { fileName: "reports/mutation/mutation.json" },
  clearTextReporter: { allowColor: false, logTests: false },
  // Consultivo: nessuna soglia che faccia fallire il comando (`break` nullo).
  thresholds: { high: 80, low: 70, break: null },
  coverageAnalysis: "perTest",
  concurrency: 2,
  timeoutMS: 10000,
  tempDirName: "reports/.stryker-tmp",
  cleanTempDir: true,
  ignorePatterns: [".next", "reports", "node_modules"],
};
export default config;
