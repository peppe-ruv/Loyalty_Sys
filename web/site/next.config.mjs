/** @type {import('next').NextConfig} */

/**
 * `standalone` serve al Dockerfile, che copia `.next/standalone` per farne un'immagine minima.
 * Su Vercel non serve e non va messo: là il builder Next produce da sé la funzione serverless, e
 * un output riscritto per l'autogestione — per giunta con la radice di tracciamento spostata su
 * questa cartella, che qui serve per via del lockfile proprio — gli mette fra i piedi un albero di
 * file diverso da quello che si aspetta. Perciò la scelta dipende da dove si costruisce.
 */
const autogestito = !process.env.VERCEL;

export default {
  ...(autogestito ? { output: 'standalone', outputFileTracingRoot: import.meta.dirname } : {}),
  reactStrictMode: true,
};
