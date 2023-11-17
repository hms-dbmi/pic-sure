FROM amazoncorretto:21.0.1-alpine3.18

# Copy jar and access token from maven build
ADD service-3.0.0-SNAPSHOT.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]