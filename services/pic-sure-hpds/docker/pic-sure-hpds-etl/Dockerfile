FROM openjdk:11-jre-slim as loader

RUN apt-get update -y && apt-get install -y gnupg openssl && rm -rf /var/lib/apt/lists/*

ADD create_key.sh .
ADD SQLLoader-jar-with-dependencies.jar .
ADD CSVLoader-jar-with-dependencies.jar .
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

