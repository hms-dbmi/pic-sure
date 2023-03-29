FROM docker.io/alpine:3.16

RUN apk add --no-cache --purge -uU bash &&     rm -rf /var/cache/apk/* /tmp/*

RUN apk add --no-cache --purge -uU curl wget unzip

RUN apk add --no-cache --purge openjdk11

ADD hpds-war-1.0-SNAPSHOT-war-exec.jar /hpds.jar

EXPOSE 8080
