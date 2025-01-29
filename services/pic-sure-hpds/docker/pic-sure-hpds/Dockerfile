FROM maven:3.9.4-amazoncorretto-21 AS build

RUN yum update -y && yum install -y git && yum clean all

WORKDIR /app

COPY .m2 /root/.m2

COPY . .

RUN mvn clean install -DskipTests

FROM amazoncorretto:21.0.1-alpine3.18

WORKDIR /app

COPY --from=build /app/service/target/service-*-SNAPSHOT.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]