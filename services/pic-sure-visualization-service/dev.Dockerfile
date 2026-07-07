FROM maven:3.9-amazoncorretto-25 AS build

COPY --from=m2_cache / /root/.m2/repository/
COPY ./ /app

WORKDIR /app
RUN mvn clean install -DskipTests -nsu -Dmaven.test.skip=true

FROM amazoncorretto:25-alpine

COPY --from=build /app/target/pic-sure-visualization-service-*.jar /pic-sure-visualization-service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-visualization-service.jar"]
