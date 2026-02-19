FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY target/pic-sure-logging-*.jar app.jar
RUN mkdir -p /app/logs && chown appuser:appgroup /app/logs
USER appuser
EXPOSE ${PORT:-8080}
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --spider -q http://localhost:${PORT:-8080}/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
