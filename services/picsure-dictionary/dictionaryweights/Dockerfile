FROM maven:3.9-eclipse-temurin-22-alpine AS build

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src src

RUN mvn -B package -DskipTests

FROM amazoncorretto:22-alpine

# Copy jar and access token from maven build
COPY --from=build target/dictionaryweights-*.jar /dictionaryweights.jar

ARG DATASOURCE_URL
ARG DATASOURCE_USERNAME
ARG SPRING_PROFILE

# If a --env-file is passed in, you can override these values
ENV DATASOURCE_URL=${DATASOURCE_URL}
ENV DATASOURCE_USERNAME=${DATASOURCE_USERNAME}
ENV SPRING_PROFILE=${SPRING_PROFILE}

ENTRYPOINT java -jar /dictionaryweights.jar --spring.profiles.active=${SPRING_PROFILE:-}