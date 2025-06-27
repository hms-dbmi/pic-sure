FROM maven:3.9.9-amazoncorretto-24 AS build

RUN yum update -y && yum install -y git && yum clean all

WORKDIR /app

COPY .m2 /root/.m2

COPY . .

RUN mvn clean install -DskipTests

FROM amazoncorretto:24-alpine

WORKDIR /app

COPY --from=build /app/service/target/service-*-SNAPSHOT.jar /service.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /service.jar"]