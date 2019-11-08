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

if [ "${CONFIG_DIR}" == "" ];
then
	logger "CONFIG_DIR environment variable is not set. Using default /usr/local/docker-config"
	mkdir -p /usr/local/docker-config/images
	export CONFIG_DIR=/usr/local/docker-config
else
	logger "Using CONFIG_DIR variable, with value ${CONFIG_DIR}"
fi

if [ -d ${CONFIG_DIR}/images ];
then
	logger "images subdirectory exists"
else
	logger "images subdirectory does not exist. creating it"
	mkdir -p ${CONFIG_DIR}/images
fi

# TODO: Could use a check on the docker version and wether it is running or not.

# Do the build from the root directory of the repo
cd ..
# Just in case this is built on a Mac
find ./ -name ".DS_Store" -exec rm -f {} \; 2>/dev/null

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
docker build . --rm --tag "dbmi/pic-sure-auth-services:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}"
CMD_STATUS=$?
if [ $CMD_STATUS -eq 0 ];
then
	logger "Successfully built PSAMA back-end docker image dbmi/pic-sure-auth-services:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH} locally."
else
	logger "Failed to build PSAMA back-end docker image locally."
	exit 255
fi
cd ..

# Build the PSAMA UI front-end docker image.
#    sub-task 1., Get the current GitHub branch and commit hash
cd pic-sure-auth-ui
docker build . --rm --tag "dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}"
CMD_STATUS=$?
if [ $CMD_STATUS -eq 0 ];
then
	logger "Successfully built PSAMA UI docker image dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH} locally."
else
	logger "Failed to build PSAMA UI docker image locally."
	exit 255
fi

logger "Saving current images to ${CONFIG_DIR}/images directory."

#docker save dbmi/pic-sure-auth-services:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH} | \
#	gzip > ${CONFIG_DIR}/images/pic-sure-auth-services_${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}.tar.gz

#docker save dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH} | \
#	gzip > ${CONFIG_DIR}/images/pic-sure-auth-ui_${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}.tar.gz

echo "Images:"
echo "dbmi/pic-sure-auth-ui:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}"
echo "dbmi/pic-sure-auth-services:${GITHUB_BRANCH}.${GITHUB_COMMIT_HASH}"
logger "Done."
