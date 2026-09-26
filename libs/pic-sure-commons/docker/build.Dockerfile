FROM maven:3.9.11-amazoncorretto-25@sha256:ac1f9e7cf7f3b9d6ae8035f09e41f2702618f87b8584d75ebb17e18ab25f38fb

RUN yum update -y && yum install -y git && yum clean all