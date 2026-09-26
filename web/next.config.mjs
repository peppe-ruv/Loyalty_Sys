import { fileURLToPath } from "node:url";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  // La root del progetto web è questa cartella (evita l'ambiguità con lockfile esterni).
  outputFileTracingRoot: fileURLToPath(new URL(".", import.meta.url)),
};
export default nextConfig;
