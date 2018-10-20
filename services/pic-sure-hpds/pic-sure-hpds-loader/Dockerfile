FROM openjdk:11-jre-slim as loader

ADD pic-sure-hpds-1.0-SNAPSHOT-jar-with-dependencies.jar .

ENTRYPOINT java -Xmx${HEAPSIZE}m -cp experimental-service-1.0-SNAPSHOT-jar-with-dependencies.jar edu.harvard.hms.dbmi.avillach.pheno.Loader 

