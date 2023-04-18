# PIC-SURE API

This is the git repository for version 2+ of the PIC-SURE API.

## Pre-requisits

*  Java 11

## Build
The build consists of the following top level maven modules:
*  pic-sure-api-data - for anything database related
*  pic-sure-api-war - the actual packaged web application
*  pic-sure-resources - the API that resources must implement to become PIC-SURE compatible as well as any resources we choose to develop(HAIL, i2b2, gNOME, etc)

To build the entire project, change directory to the projects top level, and execute:

```shell
mvn clean install
```

This command will run all tests and build all artifacts.

## Deployment

In order to run the app for development you need to set the following environment variables:

PIC_SURE_CLIENT_SECRET - This can be anything you want for testing, foo, bar, just set it to something.
PIC_SURE_USER_ID_CLAIM - This should be "email" 

In its current state, there's no simple way to run this app locally for development. There was a
wildfly setup, but it hasn't compiled since 2019, so we deleted it. If you want to try and revive it,
look at the commit associated with this comment.

If you wish to debug your tests from Eclipse, use `mvnDebug clean install` and connect your debugger.


