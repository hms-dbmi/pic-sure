FROM hms-dbmi/pic-sure-hpds-build:LATEST AS hpds-build
FROM maven:3.9.9-amazoncorretto-24 AS build

# Copy the .m2 artifacts from HPDS base image
COPY --from=hpds-build /root/.m2 /root/.m2/

# Copy the source code into the container
COPY ./ /app

# Pull in global maven settings
COPY ./.m2/*.xml /root/.m2/

# Change the working directory
WORKDIR /app

# Build the jar
RUN mvn clean install -DskipTests

FROM amazoncorretto:24-alpine

# Copy jar and access token from maven build
COPY --from=build /app/pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

# Copy the AWS certificate
COPY  pic-sure-auth-services/aws_certs/certificate.der /certificate.der

# Import the certificate into the Java trust store
RUN keytool -noprompt -import -alias aws_cert -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file /certificate.der

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]