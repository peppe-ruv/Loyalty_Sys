# Multi-stage; contesto di build = radice repo (docs/04 §6, docs/11 §5).
#   docker build -f services/insight-service/Dockerfile -t lh-insight .

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY seed/ seed/
COPY services/ services/
COPY deploy/ deploy/
RUN ./mvnw -q -pl services/insight-service -am -DskipTests package

# ---- run ----
FROM eclipse-temurin:25-jre AS run
WORKDIR /app
# Contenitore da 512 MB (docs/11 §5): heap in percentuale, GC seriale.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"
COPY --from=build /src/services/insight-service/target/insight-service-*-boot.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
