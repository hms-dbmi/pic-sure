FROM maven:3.9.9-amazoncorretto-24

RUN yum update -y && yum install -y git && yum clean all