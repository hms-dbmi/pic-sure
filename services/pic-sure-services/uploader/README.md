# GIC S3 Data Sharing Solution

## Overview

This repository is a data sharing solution for GIC. It creates an S3 bucket with
server side encryption, as well as the keys, roles, and policies needed to permit
third party users write / delete access, but not read access to the bucket. It also
contains the source code for a Java application that can authenticate with AWS,
assume the uploader role, and then upload / delete data.

This repository is structured as follows:  
`aws`: Cloud formation template, any other AWS specific resources
`src`: Java source code for Data Uploader
`env-proto`: a template file for creating your .env file
`pom.xml`: Java dependencies
`Dockerfile`: Build specification for the docker image

## Usage

1. Configure Dependencies

You will need at least one AWS to run this application, and preferably two if you
want to properly demonstrate third party uploading. You will also need `docker`
installed to run the data uploader. For development, please install `maven` and
the Java 21 JDK.

2. Build Docker Image

In the root of this repo, run `docker build . -t gic-data-uploader`. This builds
the Data Uploader application, and tags (names) it `gic-data-uploader`.

3. Provision AWS Resources

In the AWS console of the account you would like to provision an S3 bucket for,
go to AWS Cloud Formation Template and create a new stack using the template in
`aws/s3-bucket.yml`. You must specify at least 1 account for the 
`UploaderAccountIds` parameter. When you get a stack status of `UPDATE_COMPLETE`,
continue to the next step.

4. Set Up Environment File

Run the following command to start setting up your env file. `cp env-proto .env`
This file `.env` is not versioned because it contains secret AWS credentials that
you should not share. Fill out the empty environment variables in your new `.env`
file using resources in your new AWS cloud stack and the authentication credentials
that Amazon provides you.

5. Run

Run `docker compose --profile production up -d `. Here is an example of a
successful output, with logging prefixes omitted:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.1.4)

Starting DatauploadApplication v0.0.1-SNAPSHOT using Java 21.0.1 with PID 1 (/dataupload.jar started by root in /)
No active profile set, falling back to 1 default profile: "default"
Tomcat initialized with port(s): 8080 (http)
Starting service [Tomcat]
Starting Servlet engine: [Apache Tomcat/10.1.13]
Initializing Spring embedded WebApplicationContext
Root WebApplicationContext: initialization completed in 1218 ms
Locking s3 client while refreshing session
Attempting to assume data uploader role
Successfully assumed role, using credentials to create new S3 client
Unlocking s3 client. Session refreshed
Checking S3 connection...
Next refresh will be at 2023-10-22T15:33:13Z
Verifying upload capabilities
Tomcat started on port(s): 8080 (http) with context path ''
Started DatauploadApplication in 3.342 seconds (process running for 3.824)
Verifying delete capabilities
S3 connection verified.
```

6. Run Migrations

`docker compose --profile migrate up -d`