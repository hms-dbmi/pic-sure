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

# copy modules
COPY target/modules/system/layers/base/com/sql/mysql/main/* /modules/

# Copy standalone.xml
COPY src/main/resources/wildfly-configuration/standalone.xml wildfly/standalone/configuration/

# Copy war file
COPY target/pic-sure-auth-services.war wildfly/standalone/deployments/pic-sure-auth-services.war

# root required to create default scratch directory - Andre
USER root

# install modules
RUN wildfly/bin/jboss-cli.sh --command="module add --name=com.sql.mysql \
    --resources=/modules/mysql-connector-java-5.1.38.jar --dependencies=javax.api"

#USER jboss

ENTRYPOINT ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]