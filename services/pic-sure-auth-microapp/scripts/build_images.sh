#!/bin/sh
logger() {
	MSG=$*
	TIMESTAMP=`date +%c`
	echo "${TIMESTAMP} ${MSG}"
}
#
# This script will build psama and psamaui images to be pushed to the Docker hub, from the local depository.
# This script will NOT push the generated images automatically.
# This script will tag the generated images as:
#    "dbmi/pic-sure-auth-services:${GITHUB_BRANCH}_${GIT_COMMIT_HASH}"
# for the PSAMA back-end image and 
#    "dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}_${GIT_COMMIT_HASH}"
# for the PSAMA UI (front-end) image
#
# Currently, the script requires Java 11 (11.0.2-open) and Maven to be 
# installed on the machine that is executing the build process.

# Check if Java is 11.0.2
CURRENT_JAVA_VERSION=$(java --version | head -1 | cut -d " " -f 2)
if [ "${CURRENT_JAVA_VERSION}" != "11.0.2" ];
then
	logger "Incorrect Java version. It is ${CURRENT_JAVA_VERSION}, but it should be 11.0.2."
	logger "Cannot proceed with docker image build."
	exit 255
else
	logger "Current Java version has been verified. Proceed with Maven build"
fi

# TODO: Could use a check on the docker version and wether it is running or not.

# Do the build from the root directory of the repo
cd ..
mvn clean install
MAVEN_COMPLETION_STATUS=$?

# The maven build will generate the PSAMA back-end .war file
if [ $MAVEN_COMPLETION_STATUS -eq 0 ];
then
	logger "Building .war file was successful."
	find ./ -name "*.war" -ls
else
	logger "Error building .war files."
fi

# Build the PSAMA back-end docker image.
#    sub-task 1., Get the current GitHub branch and commit hash
GITHUB_BRANCH=$(git branch | grep "*" | cut -d " " -f 2)
GITHUB_COMMIT_HASH=$(git log | head -1 | cut -d " " -f 2 | cut -c 1-12)
cd pic-sure-auth-services
docker build . --tag "dbmi/pic-sure-auth-services:${GITHUB_BRANCH}_${GITHUB_COMMIT_HASH}"
CMD_STATUS=$?
if [ $CMD_STATUS -eq 0 ];
then
	logger "Successfully built PSAMA back-end docker image dbmi/pic-sure-auth-services:${GITHUB_BRANCH}_${GITHUB_COMMIT_HASH} locally."
else
	logger "Failed to build PSAMA back-end docker image locally."
	exit 255
fi

# Build the PSAMA UI front-end docker image.
#    sub-task 1., Get the current GitHub branch and commit hash
cd pic-sure-auth-ui
docker build . --tag "dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}_${GITHUB_COMMIT_HASH}"
CMD_STATUS=$?
if [ $CMD_STATUS -eq 0 ];
then
	logger "Successfully built PSAMA UI docker image dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}_${GITHUB_COMMIT_HASH} locally."
else
	logger "Failed to build PSAMA UI docker image locally."
	exit 255
fi

logger "Done."
