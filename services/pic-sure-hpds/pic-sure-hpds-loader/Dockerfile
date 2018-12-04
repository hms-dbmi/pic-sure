FROM openjdk:11-jre-slim as loader

ADD pic-sure-hpds-SQLLoader-jar-with-dependencies.jar .
ADD pic-sure-hpds-CSVLoader-jar-with-dependencies.jar .
ADD pic-sure-hpds-VariantLoader-jar-with-dependencies.jar .
ADD pic-sure-hpds-VariantCounter-jar-with-dependencies.jar .

ENTRYPOINT java -Xmx${HEAPSIZE:-2048}m -cp pic-sure-hpds-${LOAD_SOURCE}${OPERATION:-Loader}-jar-with-dependencies.jar 

