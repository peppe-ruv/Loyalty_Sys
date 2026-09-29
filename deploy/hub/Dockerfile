# Multi-stage; contesto di build = radice repo (docs/04 §6, docs/11 §5).
# Deployable CONSOLIDATO della demo ospitata (docs/13 ADR-023): i 4 servizi del
# core loop (ingestion, member, campaign, wallet) in un solo JVM.
#   docker build -f deploy/hub/Dockerfile -t lh-hub .

# ---- build ----
FROM eclipse-temurin:25-jdk@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 AS build
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
FROM eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5 AS run
WORKDIR /app
# Contenitore da 512 MB (docs/11 §5): heap in percentuale, GC seriale.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"
COPY --from=build /src/deploy/hub/target/hub-*-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
