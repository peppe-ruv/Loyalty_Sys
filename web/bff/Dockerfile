FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
USER node
EXPOSE 3001
HEALTHCHECK CMD wget -qO- http://localhost:3001/healthz || exit 1
CMD ["node", "src/server.js"]
