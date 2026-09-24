# Multi-stage; contesto di build = radice repo (docs/04 §6, docs/11 §5).
#   docker build -f services/engagement-service/Dockerfile -t lh-engagement .

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY seed/ seed/
COPY services/ services/
COPY deploy/ deploy/
RUN ./mvnw -q -pl services/engagement-service -am -DskipTests package

# ---- run ----
FROM eclipse-temurin:25-jre AS run
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"
COPY --from=build /src/services/engagement-service/target/engagement-service-*-boot.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
