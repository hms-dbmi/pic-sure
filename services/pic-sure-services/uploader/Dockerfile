FROM amazoncorretto:25

# Runtime-only image (reactor convention): build the jar first with
#   mvn -pl services/pic-sure-services/uploader -am package
# from the monorepo root, then build this image from the module directory.
COPY target/uploader-*.jar /dataupload.jar

# Time zone
ENV TZ="US/Eastern"

ENTRYPOINT java $DEBUG_VARS $PROXY_VARS -Xmx8192m -jar /dataupload.jar
