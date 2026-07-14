FROM maven:3.9-amazoncorretto-25

RUN yum update -y && yum install -y git && yum clean all