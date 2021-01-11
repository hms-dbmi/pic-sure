FROM hms-dbmi/pic-sure-api:TARGET_BUILD_VERSION

# Copy war file
COPY target/pic-sure-aggregate-data-sharing-resource.war wildfly/standalone/deployments/aggregate-resource.war

