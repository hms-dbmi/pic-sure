HPDS ETL Process
================

*I2B2 Oracle SQL

To load your I2B2 registry data, fill out the hpds/sql.properties file with your connection details and create an encryption key in the hpds/encryption_key file.

The specific requirements for the sql.properties file will depend on your environment. The encryption_key file must have only 32 hexadecimal characters and no other content.

Build the current ETL artifacts from the repository root before running a loader:
```
mvn -pl services/pic-sure-hpds/etl -am package -DskipTests
export PIC_SURE_HPDS_ETL_WORKTREE_ID=checkmarx-critical-high
export PIC_SURE_HPDS_ETL_IMAGE="pic-sure-hpds-etl:${PIC_SURE_HPDS_ETL_WORKTREE_ID}-$(git rev-parse --short HEAD)"
export COMPOSE_PROJECT_NAME="pic-sure-hpds-etl-${PIC_SURE_HPDS_ETL_WORKTREE_ID}"
```

Once this is done, run the loader. The compose files build a local image from the
current source instead of downloading a historical image. The required image tag
includes both an explicit unique worktree identifier and the checked-out commit, and
the required Compose project name includes the same identifier. Choose a different
lowercase identifier in every concurrent worktree so images, containers, and networks
cannot overwrite one another:
```
docker compose -f docker-compose-sql-loader.yml up --build
```
The logs will show all concepts as they are loaded and some other information. Once this process exits, you should have two new files in the hpds folder:
```
columnMeta.javabin
allObservationsStore.javabin
```
The first holds all of the metadata for all concepts. The second holds the actual concept data. These files are not readable using anything except HPDS.

To make these files available to your HPDS container, volume map them into the following container path:
```
/opt/local/phenocube
```
You will of course need to unlock the HPDS instance once you have done that, which is outside the scope of this `README.md` file.

It is a good idea to validate the following once you have your data hosted in an HPDS instance:

Number of concepts vs expected number of concepts.
Number of patients vs expected number of patients.
Total number of facts.

These values are dumped into the log of the loading process at the end, immediately preceded with statistics for each concept.
