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

To verify the number of concepts, perform a search for "" against the instance and count the concepts. This should be equal to the number of folders and containers in your i2b2 ontology which actually have values for patients.

To verify the number of patients, run a count query for a concept that all patients have. What concept will depend on your dataset.

To verify the total number of facts is a bit more complicated. You will need to run a query for all concepts and all patients then count all non-blank fields in the response. A blank field means there was no value for that patient and concept. You should then compare this to all non-modifier observation facts in Oracle.


