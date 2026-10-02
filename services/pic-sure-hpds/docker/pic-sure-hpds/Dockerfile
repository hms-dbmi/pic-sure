FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7

WORKDIR /app

COPY /service/target/service-*.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]