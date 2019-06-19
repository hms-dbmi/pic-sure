FROM openjdk:11-jre-slim as loader

FROM alpine:3.8 as encryption_key

RUN apk add gnupg
RUN apk add openssl

ADD create_key.sh
ADD SQLLoader-jar-with-dependencies.jar .
ADD CSVLoader-jar-with-dependencies.jar .
ADD VCFLocalLoader-jar-with-dependencies.jar .

ENTRYPOINT java -Xmx${HEAPSIZE:-2048}m -jar ${LOADER_NAME:-CSVLoader}-jar-with-dependencies.jar 

