# Build from the backend directory (the reactor root):
#   docker build -f workflow-service/Dockerfile -t <registry>/<repo>:workflow-<version> .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# The reactor pom lists every module, so all of them must be present; only workflow-service and what it
# depends on are built (-pl ... -am). See backend/.dockerignore for what stays out of the context.
COPY . .
RUN chmod +x mvnw && ./mvnw -q -B -pl workflow-service -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 wms
WORKDIR /app
COPY --from=build /src/workflow-service/target/workflow-service-*.jar app.jar
USER wms
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:${SERVER_PORT:-8082}/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
