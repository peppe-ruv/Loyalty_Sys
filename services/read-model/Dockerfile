# syntax=docker/dockerfile:1.7
# Build multi-stage: il jar è costruito dalla CI (services/**), qui si impacchetta a layer.
FROM eclipse-temurin:21-jre-alpine AS base
FROM base AS extract
WORKDIR /app
ARG JAR=target/read-model-*.jar
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM base
RUN addgroup -S loyalty && adduser -S loyalty -G loyalty
WORKDIR /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER loyalty
EXPOSE 8088
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:8088/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
