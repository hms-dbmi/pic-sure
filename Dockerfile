FROM jboss/wildfly:17.0.0.Final

LABEL maintainer="avillach_lab_developers@googlegroups.com"

# mysql database
ENV PICSURE2_DB_CONNECTION_USER root
ENV PICSURE2_MYSQLADDRESS localhost
ENV PICSURE2_DB_PORT 3306
ENV PICSURE2_MYSQLPASS password

# JWT Token
ENV CLIENT_ID dummyid
ENV CLIENT_SECRET dummysecret
ENV picsure_USER_FIELD sub

# copy modules
COPY pic-sure-api-war/target/modules/system/layers/base/com/sql/mysql/main/* /modules/

# Copy standalone.xml
COPY pic-sure-api-war/src/main/resources/wildfly-configuration/standalone.xml wildfly/standalone/configuration/

# Copy api war file
COPY pic-sure-api-war/target/pic-sure-api-war.war wildfly/standalone/deployments/pic-sure-api-2.war

# Copy passthrough resource war file
COPY pic-sure-resources/pic-sure-passthrough-resource/target/pic-sure-passthrough-resource.war wildfly/standalone/deployments/pic-sure-passthru.war

## install modules
#RUN wildfly/bin/jboss-cli.sh --command="module add --name=com.sql.mysql \
#    --resources=/modules/mysql-connector-java-5.1.38.jar --dependencies=javax.api"

ENTRYPOINT ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
