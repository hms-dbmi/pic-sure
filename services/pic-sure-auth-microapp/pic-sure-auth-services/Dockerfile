FROM amazoncorretto:24-alpine

# Copy jar and access token from maven build
COPY pic-sure-auth-services/target/pic-sure-auth-services-*.jar /pic-sure-auth-service.jar

# Copy the AWS certificate
COPY  pic-sure-auth-services/aws_certs/certificate.der /certificate.der

# Import the certificate into the Java trust store
RUN keytool -noprompt -import -alias aws_cert -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file /certificate.der

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-auth-service.jar"]