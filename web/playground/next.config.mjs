/** @type {import('next').NextConfig} */
export default {
  // Esportazione statica: il playground non ha backend e non deve averne uno. Così si pubblica
  // su qualunque CDN — Vercel compreso — senza funzioni server, senza segreti e senza costi.
  output: 'export',
  reactStrictMode: true,
  // Il playground vive nel workspace npm della radice: senza questo Next cercherebbe il lockfile
  // dentro web/playground e sbaglierebbe la radice del tracing.
  outputFileTracingRoot: new URL('../..', import.meta.url).pathname,
  images: { unoptimized: true },
  // I file .js del design system sono ESM già compilati: Next li usa così come sono.
  transpilePackages: ['@loyalty-hub/backoffice-design-system'],
};
