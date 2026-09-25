FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7

# Copy jar and access token from maven build
COPY target/dictionary-*.jar /dictionary.jar

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
ENTRYPOINT java $DEBUG_VARS $PROXY_VARS -Xmx8192m ${JAVA_OPTS} -jar /dictionary.jar --spring.profiles.active=${SPRING_PROFILE:-}