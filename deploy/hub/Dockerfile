# Multi-stage; contesto di build = radice repo (docs/04 §6, docs/11 §5).
# Deployable CONSOLIDATO della demo ospitata (docs/13 ADR-023): i 4 servizi del
# core loop (ingestion, member, campaign, wallet) in un solo JVM.
#   docker build -f deploy/hub/Dockerfile -t lh-hub .

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY seed/ seed/
COPY contracts/ contracts/
COPY services/ services/
COPY deploy/ deploy/
RUN ./mvnw -q -pl deploy/hub -am -DskipTests package

# ---- run ----
FROM eclipse-temurin:25-jre AS run
WORKDIR /app
# Contenitore da 512 MB (docs/11 §5): heap in percentuale, GC seriale.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"
COPY --from=build /src/deploy/hub/target/hub-*-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
