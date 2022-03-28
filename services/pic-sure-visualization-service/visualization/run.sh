echo Starting the visualization resource...

if [ -z "$1" ]
  then
    echo "ERROR: No environment argument supplied!"
    echo "    Try using 'integration' or 'prod'"
    exit 1
fi
cp -f "./src/main/resources/application-$1.properties" "/usr/local/docker-config/application.properties"
mvn clean install -DskipTests
docker build --tag visualization .
docker stop StatViz || true
docker rm StatViz || true
docker run --name=StatViz -p 8080:8080 -v /usr/local/docker-config/application.properties:/usr/local/docker-config/application.properties visualization
