FROM jboss/wildfly:15.0.1.Final

LABEL maintainer="avillach_lab_developers@googlegroups.com"

# The format that IRCT returns, if don't set, default it JSON
ENV RESULT_FORMAT JSON

# Copy war file
COPY target/pic-sure-irct-resource.war wildfly/standalone/deployments/pic-sure-irct-resource.war

ENTRYPOINT ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0"]
