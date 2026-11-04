FROM amazoncorretto:25-alpine@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7

# Jar comes pre-built from the pic-sure reactor (PIC-SURE Maven Build job);
# this image only packages it.
COPY target/dictionaryweights-*.jar /dictionaryweights.jar

ARG DATASOURCE_URL
ARG DATASOURCE_USERNAME
ARG SPRING_PROFILE

# If a --env-file is passed in, you can override these values
ENV DATASOURCE_URL=${DATASOURCE_URL}
ENV DATASOURCE_USERNAME=${DATASOURCE_USERNAME}
ENV SPRING_PROFILE=${SPRING_PROFILE}

ENTRYPOINT java -jar /dictionaryweights.jar --spring.profiles.active=${SPRING_PROFILE:-}