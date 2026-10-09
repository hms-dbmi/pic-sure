FROM maven:3.9-amazoncorretto-25@sha256:490bf1b0b852f8ae833f134933f30ca38024e4db475b2db05ee58b2f819179f0 AS build

COPY --from=m2_cache / /root/.m2/repository/
COPY ./ /app

WORKDIR /app
RUN mvn clean install -DskipTests -nsu -Dmaven.test.skip=true

FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7

COPY --from=build /app/target/pic-sure-visualization-service-*.jar /pic-sure-visualization-service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-visualization-service.jar"]
