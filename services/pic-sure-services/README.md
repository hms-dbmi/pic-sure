# PIC-SURE Services

This is a collection of microservices. You can deploy any number of these microservices
to a PIC-SURE environment and access their respective APIs via the /proxy/ endpoint.
Every directory in this repo is a separate microservice.

## Deploying a Service

1. Pick a tag in this repo
2. On the server you wish to deploy to, run the Build and Deploy Microservice job  
  - If the service requires an .env file, figure that out
  - The name the job asks for is the name of the service directory in this repo
  - I do not care what you put for the description
3. The auth logic needed to permit roles access to your API is on you. Those migrations
belong in the custom UI repo for your environment. 
4. That's it. You don't need to restart PIC-SURE or anything.

## Using a Service

You can hit your API through PIC-SURE via the proxy endpoint. Requests to 
`/proxy/service-name` will be routed to your container. The service name is determined
by the `container_name` attribute in your `*-docker-compose.yml` file.

## Creating a Service

Your service should be a REST API, and should implement the `/info` endpoint as
implemented in PIC-SURE. If you want to implement your service in a language other
than Java, you're on your own. For java, you can look at the example `infoservice`.
One note: pulling in that ResourceInfo class was tricky. I used jitpack because
relying on the `.m2` cache leads to docker images with nasty builds. You can 
pull in the artifact by including the following in your pom:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```
...
```xml
<dependency>
<groupId>com.github.hms-dbmi</groupId>
<artifactId>pic-sure</artifactId>
<version>e421ae9caff5ed02e9cd27812edfe24ec455281c</version>
<exclusions>
    <!--Exclude all transitive dependencies. I just want the ResourceInfo class-->
    <exclusion>
        <groupId>org.slf4j</groupId>
        <artifactId>*</artifactId>
    </exclusion>
</exclusions>
</dependency>
```

When implementing the info endpoint, you can assume your service's resource ID will
be available inside your container via the environment variable `RESOURCE_UUID`

