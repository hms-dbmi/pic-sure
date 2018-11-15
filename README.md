# PIC-SURE API

This is the git repository for version 2+ of the PIC-SURE API.

## Pre-requisits

*  Java 9

## Build
The build consists of the following top level maven modules:
*  pic-sure-api-data - for anything database related
*  pic-sure-api-war - the actual packaged web application
*  pic-sure-api-wildfly - a fully configured wildfly environment which serves as an example configuration as well as an integration testing environment.
*  pic-sure-resources - the API that resources must implement to become PIC-SURE compatible as well as any resources we choose to develop(HAIL, i2b2, gNOME, etc)

To build the entire project, change directory to the projects top level, and execute:

```
mvn clean install

```

This command will run all tests, with the included WildFly server.

## Deployment

In order to run the app for development you need to set the following environment variables:

PIC_SURE_CLIENT_SECRET - This can be anything you want for testing, foo, bar, just set it to something.
PIC_SURE_USER_ID_CLAIM - This should be "email" 

To run the app for development, go into the pic-sure-api-wildfly folder and use this:

mvn wildfly:run && mvn wildfly:shutdown

This will start the app with the console output in your terminal session and CTRL-C will kill it correctly.

If you wish to debug your tests from Eclipse, use `mvnDebug clean install` and connect your debugger.

If you wish to debug your services while the tests run, set the suspend=n to suspend=y in 
the wildfly-maven-plugin configuration in the pom file for pic-sure-api-wildfly on the line that looks like:

<java-opt>-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005</java-opt>
						
Both of these will pause the build allowing you to connect your debuggers.


