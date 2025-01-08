# pic-sure-hpds

PIC-SURE-HPDS was built from the ground up to support biomedical informatic use cases without requiring massive clustering as the datasets increase in scale. As such, PIC-SURE-HPDS can manage arbitrarily large datasets with very little computing.

For clinical data, datasets are stored as two files: metadata and data. The metadata file contains the internal data dictionary, high-level dataset-specific information, and file offsets for each variable's data within the data file. The data file contains data for three concepts: patient index, numerical index, and categorical index. [How to load phenotypic data into HPDS](https://github.com/hms-dbmi/pic-sure-hpds-phenotype-load-example/tree/master/nhanes-load-example)

For genomic data, variants that are not represented in the database are not stored. Genomic sample data is stored separately from variant annotations in HPDS. Variant annotations are stored using the same Numerical Index, and Categorical Index described above, indexing variant IDs instead of patient IDs. [How to load genomic data into HPDS](https://github.com/hms-dbmi/pic-sure-hpds-genotype-load-example)


## Pre-requisites

* Java 21
* Before contributing code, please set up our git hook:
  `cp code-formatting/pre-commit.sh .git/hooks/pre-commit`
    * To skip formatting on a block of code, wrap in `spotless:off`, `spotless:on` comments
