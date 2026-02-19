# Stage 1: Build
# For reproducible builds, pin to a digest: maven:3.9-eclipse-temurin-21-alpine@sha256:<digest>
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
# For reproducible builds, pin to a digest: eclipse-temurin:21-jre-alpine@sha256:<digest>
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/pic-sure-logging-*.jar app.jar
RUN mkdir -p /app/logs && chown appuser:appgroup /app/logs
USER appuser
EXPOSE ${PORT:-8080}
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --spider -q http://localhost:${PORT:-8080}/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
