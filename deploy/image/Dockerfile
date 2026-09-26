# Multi-stage Dockerfile for M8.1

# ---- hub-build ----
FROM eclipse-temurin:25-jdk AS hub-build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY seed/ seed/
COPY contracts/ contracts/
COPY services/ services/
COPY deploy/ deploy/
# We build all modules included in deploy/hub as today
RUN ./mvnw -q -pl deploy/hub -am -DskipTests package

# ---- web-build ----
FROM node:22-alpine AS web-build
WORKDIR /src
RUN npm install -g pnpm@10.33.0
COPY web/package.json web/pnpm-lock.yaml* ./web/
WORKDIR /src/web
RUN pnpm install --frozen-lockfile
COPY web/ ./
# Will build standalone if configured in next.config.mjs
RUN pnpm build
# Ensure public directory exists
RUN mkdir -p /src/web/public

# ---- final ----
FROM cgr.dev/chainguard/wolfi-base@sha256:08df5982c3d27e70a4ce1607e3bb9af09d746f8722cf135a7694afef879fc5a2

USER root
# We install required packages from wolfi package repository
RUN apk add --no-cache openjdk-25-jre nodejs-22 s6 tzdata \
    && addgroup -S -g 1000 lh \
    && adduser -S -u 1000 -G lh lh

# Configure directories
RUN mkdir -p /opt/lh/hub /opt/lh/web /var/lib/lh /tmp /opt/lh/s6/hub /opt/lh/s6/web && \
    chown -R lh:lh /opt/lh /var/lib/lh /tmp

# Copy hub artifact
COPY --from=hub-build --chown=lh:lh /src/deploy/hub/target/hub-*-boot.jar /opt/lh/hub/hub.jar

# Copy web standalone artifact
COPY --from=web-build --chown=lh:lh /src/web/.next/standalone /opt/lh/web
COPY --from=web-build --chown=lh:lh /src/web/public /opt/lh/web/public
COPY --from=web-build --chown=lh:lh /src/web/.next/static /opt/lh/web/.next/static

# Copy entrypoint and healthcheck
COPY --chown=lh:lh deploy/image/entrypoint.sh /opt/lh/entrypoint.sh
COPY --chown=lh:lh deploy/image/healthcheck.sh /opt/lh/healthcheck.sh
RUN chmod +x /opt/lh/entrypoint.sh /opt/lh/healthcheck.sh

# Copy s6 services (to be copied to runtime location by entrypoint)
COPY --chown=lh:lh deploy/image/s6/hub/run /opt/lh/s6/hub/run
COPY --chown=lh:lh deploy/image/s6/web/run /opt/lh/s6/web/run

USER lh
WORKDIR /opt/lh

ENV PORT=3000
EXPOSE 8080 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD /opt/lh/healthcheck.sh

ENTRYPOINT ["/opt/lh/entrypoint.sh"]
