FROM amazoncorretto:25
WORKDIR /app
COPY target/pic-sure-gateway-*.jar /app/app.jar
EXPOSE 8080

# run unprivileged: the image only reads the root-owned jar, so a bare numeric non-root UID suffices (satisfies runAsNonRoot)
USER 1000:1000
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILE:-aio}"]
