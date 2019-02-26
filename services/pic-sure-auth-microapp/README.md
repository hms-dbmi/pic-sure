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

## Deployment using Docker containers and Verify by login from a browser

The below commands need Java 9+ and Maven installed. The commands will do

1. download the repository
1. compile the code with Maven 
1. build the Docker images in the local registry
1. start the Docker containers on the configured VirtualBox
2. open a browser window (on MacOS) for the URL to the MicroApp UI container


```
git clone https://github.com/hms-dbmi/pic-sure-auth-microapp.git
cd pic-sure-auth-microapp
mvn clean install && docker-compose build && docker-compose up -d
DOCKER_IP=`echo $DOCKER_HOST | cut -d ":" -f 2`
open http://${DOCKER_IP}/

```

After the commands successfully executed, list the containers, that comprise the PIC-SURE Auth MicroApp.

```
docker ps | grep pic-sure
############ pic-sure-auth-microapp_picsureauth  "/opt/jboss/wildfly/…"   $$$$$$$ ago  Up ## days  0.0.0.0:8080->8080/tcp, 0.0.0.0:8787->8787/tcp   pic-sure-auth-microapp_picsureauth_1
############ pic-sure-auth-microapp_httpd        "httpd-foreground"       $$$$$$$ ago  Up ## days  0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp         pic-sure-auth-microapp_httpd_1

```

After the commands successfully executed, list the three containers, that comprise the PIC-SURE Auth MicroApp.


Note: <small>This was changed from the much shorter maven based deployment to resolve a certificate issue 
  with grin-docker-dev. Once the cert issue is resolved the maven tomcat configs will work again.</small>

You'll need to provide Auth0 client_id in /admin/overrides/login.js and the client_secret of pic-sure-auth-services 
has to match the one from Auth0 based on client_id.

#### To add an initial top admin user in the system
If you follow the steps above, spins up the docker containers and you can see the login page in the browser, you are almost there.

You just need to add a top admin user in the system to be able to play with all features.

There is a sql script in source code for adding the top admin user with some initial setup, you can import the script
into your database.

Open the file under /{{root_pic-sure-auth-microapp}}/pic-sure-auth-db/db/tools/first_time_run_the_system_and_insert_admin_user.sql,
modify the configuration data - @user_email with your own google email

#### Terms of Service

If a user logging in has not accepted the latest terms of service, they will be directed to the 'Accept Terms of Service' page.
The content of the terms of service is stored in the termsOfService table in the database.  This is html that is rendered on the page.  
To trigger the acceptance of the terms of service, this html must include a button with class 'accept-tos-button'.  Anything with this class,
upon clicking, will register the logged in user as accepting terms of service.  This button can be disabled until criteria are met.  Some example termsOfService content would be:

```aidl
These are the terms of service.
<br>
<input type="checkbox" id="checkMe">This box must be checked</input>
<br>
<button type="button" disabled id="acceptBtn" class="btn btn-info accept-tos-button">
  <span>Accept</span> 
</button>
<script>
 $('#checkMe').on('change', function(){
	$('#acceptBtn').prop("disabled", !this.checked);
	});
</script>
```