## Overview
This directory contains dockerfiles and supporting scripts for building and running the PIC-SURE WildFly container.

### Build Dockerfile
This `build.Dockerfile` builds the PIC-SURE-API project using maven and the appropriate Java version. This Dockerfile
should always be built using the project root as context. This means you will need to use `-f docker/build.Dockerfile`
with your `docker build` command.

### BDC
This directory contains dockerfiles and supporting scripts for building the Biodatacatalyst PIC-SURE WildFly container.
This Dockerfile uses the `build.Dockerfile` as a base to copy the built WAR files into the WildFly container.

#### Generate-module-xml.sh
- `generate-module-xml.sh`: Generates a module.xml which is copied into the WildFly container's module directory. This
  is necessary for the WildFly container to recognize and use the dependencies in the bdc/pom.xml file.

#### pom.xml (In the bdc directory)
- `pom.xml`: Contains dependencies that are specific to the Biodatacatalyst PIC-SURE WildFly container.

## all-in-one
This directory contains a dockerfile named `all-in-one.Dockerfile`. This dockerfile uses the `build.Dockerfile` as a 
base to copy the built WAR files into the WildFly container.
