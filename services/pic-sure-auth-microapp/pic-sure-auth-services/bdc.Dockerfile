FROM maven:3.9.6-amazoncorretto-21 as build

# Copy the source code into the container
COPY ./ /app

# Change the working directory
WORKDIR /app

# Build the jar
RUN mvn clean install -DskipTests

FROM amazoncorretto:21.0.1-alpine3.18

ARG DATASOURCE_URL
ARG DATASOURCE_USERNAME
ARG STACK_SPECIFIC_APPLICATION_ID

ENV DATASOURCE_URL=${DATASOURCE_URL}
ENV DATASOURCE_USERNAME=${DATASOURCE_USERNAME}
ENV STACK_SPECIFIC_APPLICATION_ID=${application_id_for_base_query}

# Copy jar and access token from maven build
COPY --from=build /app/pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

# Copy additional bdc configuration files. Root of the project
COPY config/psama/bdc/psama-db-config.properties /config/psama-db-config.properties

# Set SPRING_CONFIG_ADDITIONAL_LOCATION
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/psama-db-config.properties

# Copy the AWS certificate
COPY  pic-sure-auth-services/aws_certs/certificate.der /certificate.der

# Import the certificate into the Java trust store
RUN keytool -noprompt -import -alias aws_cert -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file /certificate.der

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]