FROM amazoncorretto:25-alpine

WORKDIR /app

COPY /service/target/service-*.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]