# Il BFF usa prom-client (metriche RF-117): senza installazione l'immagine parte e muore subito.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src
USER node
EXPOSE 3001
HEALTHCHECK CMD wget -qO- http://localhost:3001/healthz || exit 1
CMD ["node", "src/server.js"]
