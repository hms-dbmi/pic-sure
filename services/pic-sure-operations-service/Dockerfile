FROM amazoncorretto:25-alpine
WORKDIR /app
COPY target/pic-sure-operations-service-*.jar /app/app.jar
ENV PICSURE_ACTUATOR_EXPOSURE=health
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/operations/actuator/health/liveness || exit 1

# run unprivileged: the image only reads the root-owned jar, so a bare numeric non-root UID suffices (satisfies runAsNonRoot)
USER 1000:1000
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
