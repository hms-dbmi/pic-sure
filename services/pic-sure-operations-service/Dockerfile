FROM amazoncorretto:25
WORKDIR /app
COPY target/pic-sure-operations-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
