FROM amazoncorretto:24.0.1-alpine3.18

COPY target/dump-*.jar /dump.jar

# Time zone
ENV TZ="UTC"

ARG DATASOURCE_URL
ARG DATASOURCE_USERNAME
ARG SPRING_PROFILE

# If a --env-file is passed in, you can override these values
ENV DATASOURCE_URL=${DATASOURCE_URL}
ENV DATASOURCE_USERNAME=${DATASOURCE_USERNAME}
ENV SPRING_PROFILE=${SPRING_PROFILE}

# Default to no profile
ENTRYPOINT java $DEBUG_VARS $PROXY_VARS -Xmx4192m ${JAVA_OPTS} -jar /dump.jar --spring.profiles.active=${SPRING_PROFILE:-production}