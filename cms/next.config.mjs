import { withPayload } from '@payloadcms/next/withPayload';

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Il pannello è servito da Next: `withPayload` aggiunge le rotte dell'admin e delle API di Payload.
  reactStrictMode: true,
  // Il CMS ha un lockfile proprio (non fa parte del workspace npm della radice): senza questo
  // Next risalirebbe al lockfile del monorepo per tracciare i file dell'output.
  outputFileTracingRoot: import.meta.dirname,
};

export default withPayload(nextConfig);
