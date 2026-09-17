/** @type {import('next').NextConfig} */
export default {
  // Immagine di runtime minima: il Dockerfile copia .next/standalone.
  output: "standalone",
  reactStrictMode: true,
  // Il sito ha un lockfile proprio (non fa parte del workspace npm della radice): senza questo
  // Next userebbe la radice del monorepo come base e `standalone` finirebbe annidato in web/site/,
  // mentre il Dockerfile si aspetta server.js in cima.
  outputFileTracingRoot: import.meta.dirname,
};
