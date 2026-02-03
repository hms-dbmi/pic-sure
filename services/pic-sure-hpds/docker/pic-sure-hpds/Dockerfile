FROM hms-dbmi/pic-sure-hpds-build:LATEST AS hpds-build

FROM amazoncorretto:24-alpine

WORKDIR /app

COPY --from=hpds-build /app/service/target/service-*-SNAPSHOT.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]