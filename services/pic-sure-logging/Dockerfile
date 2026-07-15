# amazoncorretto 25 to match the reactor's enforced Java 25 bytecode
# (temurin 21 JRE fails with UnsupportedClassVersionError on class file 69).
FROM amazoncorretto:25-alpine
WORKDIR /app
COPY target/pic-sure-logging-*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --spider -q http://localhost:80/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
