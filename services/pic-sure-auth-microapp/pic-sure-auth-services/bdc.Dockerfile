FROM amazoncorretto:24-alpine

ARG DATASOURCE_URL
ARG DATASOURCE_USERNAME
ARG STACK_SPECIFIC_APPLICATION_ID

ENV DATASOURCE_URL=${DATASOURCE_URL}
ENV DATASOURCE_USERNAME=${DATASOURCE_USERNAME}
ENV STACK_SPECIFIC_APPLICATION_ID=${STACK_SPECIFIC_APPLICATION_ID}

# Copy jar from pre-built workspace
COPY pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

# Copy additional bdc configuration files. Root of the project
COPY config/psama/bdc/psama-db-config.properties /config/psama-db-config.properties

# Set SPRING_CONFIG_ADDITIONAL_LOCATION
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/psama-db-config.properties

# Copy the AWS certificate
COPY  pic-sure-auth-services/aws_certs/certificate.der /certificate.der

# Import the certificate into the Java trust store
RUN keytool -noprompt -import -alias aws_cert -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file /certificate.der

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]