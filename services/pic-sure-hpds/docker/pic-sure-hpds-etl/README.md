HPDS ETL Process
================

*I2B2 Oracle SQL

To load your I2B2 registry data, fill out the hpds/sql.properties file with your connection details and create an encryption key in the hpds/encryption_key file.

The specific requirements for the sql.properties file will depend on your environment. The encryption_key file must have only 32 hexadecimal characters and no other content.

Once this is done, run the loader:

docker-compose -f docker-compose-sql-loader.yml up

The logs will show all concepts as they are loaded and some other information. Once this process exits, you should have two new files in the hpds folder:

columnMeta.javabin
allObservationsStore.javabin

The first holds all of the metadata for all concepts. The second holds the actual concept data. These files are not readable using anything except HPDS.

To make these files available to your HPDS container, volume map them into the following container path:

/opt/local/phenocube

You will of course need to unlock the HPDS instance once you have done that, which is outside the scope of this README.md file.

It is a good idea to validate the following once you have your data hosted in an HPDS instance:

Number of concepts vs expected number of concepts.
Number of patients vs expected number of patients.
Total number of facts.

These values are dumped into the log of the loading process at the end, immediately preceded with statistics for each concept.

