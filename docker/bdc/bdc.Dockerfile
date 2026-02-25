# This image layer is used to cache dependencies specifically used for BDC. The dependencies can be found in the
# pom.xml in this directory.
FROM maven:3.6.3-jdk-11 as dependencies
COPY docker/bdc/pom.xml /tmp/
# Resolve and download dependencies, potentially using the dependency:copy-dependencies goal to place them into a target directory
RUN mvn -f /tmp/pom.xml dependency:copy-dependencies -DoutputDirectory=/tmp/dependencies

FROM jboss/wildfly:17.0.0.Final

USER root

# Now, copy the resolved dependencies from the 'dependencies' stage into the WildFly deployments directory
COPY --from=dependencies /tmp/dependencies/*.jar /opt/jboss/wildfly/modules/system/layers/base/com/sql/mysql/main/

# Copy the script that generates module.xml
COPY docker/bdc/generate-module-xml.sh /tmp/generate-module-xml.sh
RUN chmod +x /tmp/generate-module-xml.sh && /tmp/generate-module-xml.sh

# cat the generated module.xml file to see if it was generated correctly
RUN cat /opt/jboss/wildfly/modules/system/layers/base/com/sql/mysql/main/module.xml

USER jboss

# Copy pre-built WAR files from workspace
COPY pic-sure-api-war/target/pic-sure-api-war.war /tmp/pic-sure-api-2.war
COPY pic-sure-resources/pic-sure-aggregate-data-sharing-resource/target/pic-sure-aggregate-data-sharing-resource.war /tmp/pic-sure-aggregate-resource.war
COPY pic-sure-resources/pic-sure-visualization-resources/target/pic-sure-visualization-resource.war /tmp/pic-sure-visualization-resource.war

USER root

RUN mv /tmp/*.war /opt/jboss/wildfly/standalone/deployments/

USER jboss
