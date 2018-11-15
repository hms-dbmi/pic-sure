# pic-sure-auth-microapp

Instructions to launch in dev mode:

Prerequisites: Maven 3+, Java, Docker and docker-compose

```
git clone https://github.com/hms-dbmi/pic-sure-auth-microapp.git
cd pic-sure-auth-microapp
mvn clean install && docker-compose build && docker-compose up -d

```

If you make source code changes, just re-run the same command and it will redeploy the stack for you.

This was changed from the much shorter maven based deployment to resolve a certificate issue with grin-docker-dev. Once the cert issue is resolved the maven tomcat configs will work again.

Then open your browser at http://<your docker-machine ip>


