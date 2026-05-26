FROM hms-dbmi/pic-sure-hpds-build:LATEST AS hpds-build
FROM maven:3.9.9-amazoncorretto-21 AS build

COPY --from=hpds-build /root/.m2 /root/.m2/
COPY ./ /app
COPY ./.m2/*.xml /root/.m2/

WORKDIR /app
RUN mvn clean install -DskipTests

FROM amazoncorretto:21-alpine

COPY --from=build /app/target/pic-sure-visualization-service-*.jar /pic-sure-visualization-service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-visualization-service.jar"]
