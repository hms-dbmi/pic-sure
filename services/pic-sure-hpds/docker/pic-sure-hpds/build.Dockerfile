FROM maven:3.9.9-amazoncorretto-24 AS build

## do we need this?
RUN yum update -y && yum install -y git && yum clean all

WORKDIR /app

COPY .m2 /root/.m2

COPY . .

RUN mvn clean install -DskipTests