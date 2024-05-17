FROM maven:3-amazoncorretto-21 as build

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src src

RUN mvn -B package -DskipTests

FROM amazoncorretto:21.0.1-alpine3.18

# Copy jar and access token from maven build
COPY --from=build target/dictionary-*.jar /dictionary.jar

# Time zone
ENV TZ="US/Eastern"

ENTRYPOINT java $DEBUG_VARS $PROXY_VARS -Xmx8192m -jar /dataupload.jar
