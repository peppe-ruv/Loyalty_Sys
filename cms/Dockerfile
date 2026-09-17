# Backoffice Payload 3: è un'app Next, quindi build multi-stage con output di produzione.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# La mappa degli import dei componenti custom (viste Andamenti) è generata, non versionata a mano.
RUN npm run generate:importmap && npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/next.config.mjs ./next.config.mjs
COPY --from=build /app/src ./src
USER node
EXPOSE 3000
CMD ["npm", "start"]
