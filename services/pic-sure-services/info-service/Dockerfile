FROM amazoncorretto:25

# Runtime-only image (reactor convention): build the jar first with
#   mvn -pl services/pic-sure-services/info-service -am package
# from the monorepo root, then build this image from the module directory.
COPY target/infoservice-*.jar /service.jar

# Time zone
ENV TZ="US/Eastern"

ENTRYPOINT ["java", "-Xmx8192m", "-jar", "/service.jar"]
