FROM dbmi/wildfly:13 AS production

# TODO: It'd be nice if wildfly used alpine -Andre

LABEL maintainer="avillach_lab_developers@googlegroups.com"

# mysql database
ENV AUTH_DB_CONNECTION_USER root
ENV AUTH_MYSQLADDRESS localhost
ENV AUTH_DB_PORT 3306
ENV AUTH_MYSQLPASS password

# JWT Token
ENV AUTH_CLIENT_SECRET secret
ENV AUTH_USER_ID_CLAIM sub

# root required to create default scratch directory - Andre
USER root

# Add certs for AWS to trust store
COPY aws_certs/root_cert.der root_cert.der
COPY aws_certs/cert_1.der cert_1.der
COPY aws_certs/cert_2.der cert_2.der
COPY aws_certs/cert_3.der cert_3.der
RUN keytool -noprompt -import -alias root_cert -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file root_cert.der \
    && keytool -noprompt -import -alias cert_1 -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file cert_1.der \
    && keytool -noprompt -import -alias cert_2 -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file cert_2.der \
    && keytool -noprompt -import -alias cert_3 -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file cert_3.der

# copy modules
COPY target/modules/system/layers/base/com/sql/mysql/main/* /modules/

# install modules
RUN wildfly/bin/jboss-cli.sh --command="module add --name=com.sql.mysql \
    --resources=/modules/mysql-connector-java-5.1.38.jar --dependencies=javax.api"

# Copy standalone.xml
COPY src/main/resources/wildfly-configuration/standalone.xml wildfly/standalone/configuration/

# Copy war file
COPY target/pic-sure-auth-services.war wildfly/standalone/deployments/pic-sure-auth-services.war

#USER jboss

ENTRYPOINT ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]