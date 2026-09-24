# web — Next.js (hub, backoffice, portale, proxy) — docs/07

Una sola app, tre aree. App Router + React 19 + TypeScript strict + Tailwind v4 + TanStack Query.

```sh
cd web && pnpm i
pnpm dev            # http://localhost:3000
pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

**M0.6 (fondamenta):** token e font (`app/globals.css`, `next/font`), shell delle tre aree
(`app/(hub)/page.tsx` HUB-01, `app/backoffice`, `app/portal`), proxy `app/api/lh/[service]/[...path]`
(aggiunge `X-LH-Actor` dal cookie persona e `X-Correlation-Id`), cookie persona (`lib/persona`),
`app/api/demo/status` e `app/api/demo/wake`, keep-alive gentile. Le schermate `BO-xx`/`PT-xx`
arrivano da M1 (docs/08, docs/09): una voce di menu compare solo quando la sua milestone è chiusa.

Env: `LH_SVC_<SERVICE>_URL` per ogni servizio (default `http://localhost:<porta>`).

> `web/playground/` è un placeholder statico separato per sbloccare il vecchio progetto Vercel
> `loyalty-hub-playground`; il deploy del web vero si configura a M1.8 (docs/11).
