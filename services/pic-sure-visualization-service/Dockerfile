FROM amazoncorretto:24-alpine
EXPOSE 80
COPY target/pic-sure-visualization-service-*.jar /pic-sure-visualization-service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /pic-sure-visualization-service.jar"]
