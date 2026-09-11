HPDS ETL Process
================

*I2B2 Oracle SQL

To load your I2B2 registry data, fill out the hpds/sql.properties file with your connection details and create an encryption key in the hpds/encryption_key file.

The specific requirements for the sql.properties file will depend on your environment. The encryption_key file must have only 32 hexadecimal characters and no other content.

Build the current ETL artifacts from the repository root before running a loader:
```
mvn -pl services/pic-sure-hpds/etl -am package -DskipTests
```

Once this is done, run the loader. The compose files build `pic-sure-hpds-etl:local`
from this checkout rather than pulling a historical image:
```
docker compose -f docker-compose-sql-loader.yml up --build
```
`--build` only re-copies the staged loader jars into the image; it does not compile
them. After any change to ETL Java source, re-run the `mvn package` command above
before `--build`, or the image will carry stale loader code.

Every checkout builds to the same `pic-sure-hpds-etl:local` tag. Set
`COMPOSE_PROJECT_NAME` if you need containers from two checkouts to coexist.

The loaders run as the non-root `etl` user (UID 1000). Docker Desktop remaps
bind-mount ownership, so on macOS and Windows this needs nothing from you. On Linux
the mounted `hpds/` directory must be writable by the UID the container runs as, so
if it is owned by a different user, pass your own:
```
ETL_UID=$(id -u) ETL_GID=$(id -g) docker compose -f docker-compose-sql-loader.yml up --build
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
