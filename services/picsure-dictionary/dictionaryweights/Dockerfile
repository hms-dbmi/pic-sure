FROM maven:3.9-eclipse-temurin-22-alpine AS build

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src src

RUN mvn -B package -DskipTests

FROM amazoncorretto:22-alpine

# Copy jar and access token from maven build
COPY --from=build target/dictionaryweights-*.jar /dictionaryweights.jar

ENTRYPOINT java -jar /dictionaryweights.jar