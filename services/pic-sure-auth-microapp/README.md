# PIC-SURE Auth MicroApp

## Run with Docker

### Prerequisites for running in a Docker container
* Verify docker is installed and it is v18.+
* Verify that you have a _docker-machine_ installed and/or running. If not, [follow the instructions](https://docs.docker.com/machine/install-machine/) to install it.
* Verify that you have _docker-compose_ installed. If not, [follow the instructions](https://docs.docker.com/compose/install/) to install it.
* Verify that you have a VirtualBox running, or [follow the instructions to create a new VirtualBox](https://github.com/hms-dbmi/docker-images/wiki/Local-Development-Setup)

Configure your docker environment, to point to a running VirtualBox.

```
docker-machine env <NAME>
eval $(docker-machine env <NAME>)

```

Ensure that your VirtualBox, named &lt;NAME&gt;, has no container using port 80 and 443. If it does, either shut down the containers using those ports, or create a new VirtualBox, with a different name. ([Follow the instructions to create a new VirtualBox](https://github.com/hms-dbmi/docker-images/wiki/Local-Development-Setup)
)

## Deployment of Docker containers

The below command will

1. download the repository
1. compile the code with Maven
1. build the Docker images in the local registry
1. start the Docker containers on the configured VirtualBox
2. open a browser window (on MacOS) for the URL to the MicroApp UI container\


```
git clone https://github.com/hms-dbmi/pic-sure-auth-microapp.git
cd pic-sure-auth-microapp
mvn clean install && docker-compose build && docker-compose up -d
DOCKER_IP=`echo $DOCKER_HOST | cut -d ":" -f 2`
open http://${DOCKER_IP}/


```

After the commands successfully executed, list the three containers, that comprise the PIC-SURE Auth MicroApp.

```
docker ps | grep pic-sure
############ pic-sure-auth-microapp_picsureauth  "/opt/jboss/wildfly/…"   $$$$$$$ ago  Up ## days  0.0.0.0:8080->8080/tcp, 0.0.0.0:8787->8787/tcp   pic-sure-auth-microapp_picsureauth_1
############ pic-sure-auth-microapp_httpd        "httpd-foreground"       $$$$$$$ ago  Up ## days  0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp         pic-sure-auth-microapp_httpd_1

```

If you make source code changes, just re-run the same command and it will redeploy the stack for you.

Note: <small>This was changed from the much shorter maven based deployment to resolve a certificate issue with grin-docker-dev. Once the cert issue is resolved the maven tomcat configs will work again.</small>

Then open your browser at http://<your docker-machine ip>


