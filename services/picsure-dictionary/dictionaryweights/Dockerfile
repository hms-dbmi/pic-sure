FROM amazoncorretto:25-alpine

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