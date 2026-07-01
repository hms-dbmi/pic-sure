FROM --platform=linux/amd64 jboss/wildfly:23.0.0.Final

COPY  /pic-sure-api-war/target/pic-sure-api-war.war /tmp/pic-sure-api-2.war

USER root

RUN mv /tmp/*.war /opt/jboss/wildfly/standalone/deployments/

USER jboss