FROM eclipse-temurin:21-alpine

RUN apk add --no-cache --purge -uU bash &&     rm -rf /var/cache/apk/* /tmp/*

RUN apk add --no-cache --purge -uU curl wget unzip gnupg openssl

ADD create_key.sh .
ADD SQLLoader-jar-with-dependencies.jar .
ADD CSVLoader-jar-with-dependencies.jar .
ADD CSVLoaderNewSearch-jar-with-dependencies.jar .
ADD CSVDumper-jar-with-dependencies.jar .
ADD VCFLocalLoader-jar-with-dependencies.jar .
ADD VariantMetadataLoader-jar-with-dependencies.jar .
ADD UnifiedVCFLocalLoader-jar-with-dependencies.jar .
ADD MultialleleCounter-jar-with-dependencies.jar .
ADD RekeyDataset-jar-with-dependencies.jar .
ADD RemoveConceptFromMetadata-jar-with-dependencies.jar .
ADD HideAnnotationCategoryValue-jar-with-dependencies.jar .
ADD SequentialLoader-jar-with-dependencies.jar .

ENTRYPOINT java $JAVA_OPTS -Xmx${HEAPSIZE:-2048}m -jar ${LOADER_NAME:-CSVLoader}-jar-with-dependencies.jar
