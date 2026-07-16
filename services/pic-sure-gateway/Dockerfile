FROM amazoncorretto:25
WORKDIR /app
COPY target/pic-sure-gateway-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILE:-aio}"]
