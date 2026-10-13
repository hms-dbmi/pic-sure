FROM maven:3.9-amazoncorretto-25@sha256:490bf1b0b852f8ae833f134933f30ca38024e4db475b2db05ee58b2f819179f0 AS build

# Copy the source code into the container
COPY ../ /app

# Change the working directory
WORKDIR /app

# Build the jar
RUN mvn clean install -DskipTests

FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7

# Copy jar and access token from maven build
#COPY target/pic-sure-auth-services.jar /pic-sure-auth-service.jar
COPY --from=build /app/pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]