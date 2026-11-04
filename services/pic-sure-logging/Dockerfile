# Digest-pinned Corretto 25 Alpine runtime image.
FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7
WORKDIR /app
# spring-boot-maven-plugin:repackage also emits *.jar.original, which this glob excludes.
COPY target/pic-sure-logging-*.jar app.jar
RUN mkdir -p /app/logs && chown -R 10001:10001 /app
USER 10001:10001
# logging.env sets PORT=80; the gateway reaches this service on port 80.
EXPOSE 80
# No HEALTHCHECK: the base image has no wget/curl, and gateway/operations-service
# carry none either. Health is polled externally via GET /health.
# exec makes java PID 1 in every shell, so SIGTERM reaches the JVM and Spring's
# graceful shutdown (server.shutdown: graceful) actually runs.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
