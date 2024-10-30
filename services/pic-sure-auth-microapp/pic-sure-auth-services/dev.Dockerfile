FROM maven:3.9.6-amazoncorretto-21 as build

# Copy the source code into the container
COPY ../ /app

# Change the working directory
WORKDIR /app

# Build the jar
RUN mvn clean install -DskipTests

FROM amazoncorretto:21.0.1-alpine3.18

# Copy jar and access token from maven build
#COPY target/pic-sure-auth-services.jar /pic-sure-auth-service.jar
COPY --from=build /app/pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]