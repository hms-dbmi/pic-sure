FROM jboss/wildfly:17.0.0.Final

LABEL maintainer="avillach_lab_developers@googlegroups.com"

# Copy war file
COPY target/pic-sure-passthrough-resource.war wildfly/standalone/deployments/pic-sure-passthru.war

ENTRYPOINT ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
