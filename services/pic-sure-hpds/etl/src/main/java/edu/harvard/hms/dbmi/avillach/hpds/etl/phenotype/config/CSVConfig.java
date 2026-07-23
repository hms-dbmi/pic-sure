package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.config;

/**
 * Configuration class for CSV processing. The names are using snake_case to match the JSON keys in the config file. This is used to map the
 * JSON keys to the Java fields.
 */
public class CSVConfig {

    private String dataset_name;
    private boolean dataset_name_as_root_node;

    public String getDataset_name() {
        return dataset_name;
    }

    public void setDataset_name(String dataset_name) {
        this.dataset_name = dataset_name;
    }

    public boolean getDataset_name_as_root_node() {
        return dataset_name_as_root_node;
    }

    public void setDataset_name_as_root_node(boolean dataset_name_as_root_node) {
        this.dataset_name_as_root_node = dataset_name_as_root_node;
    }
}
