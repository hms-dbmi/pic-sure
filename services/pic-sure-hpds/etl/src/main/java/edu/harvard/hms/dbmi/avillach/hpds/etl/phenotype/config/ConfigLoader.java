package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.NotNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String INPUT_DIR = "/opt/local/hpds_input";
    private Map<String, CSVConfig> csvConfigMap = new HashMap<>();

    /**
     * Constructor for ConfigLoader. It loads the configuration from a JSON file located in the INPUT_DIR. The JSON file should contain a
     * mapping of CSV file names to their respective configurations.
     */
    public ConfigLoader() {
        File file = new File(INPUT_DIR);
        if (file.exists()) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                this.csvConfigMap = objectMapper.readValue(new File(INPUT_DIR + "/config.json"), new TypeReference<>() {});

                // Remove the ".csv" extension from the keys in the map if they exist
                for (String key : csvConfigMap.keySet()) {
                    if (key.endsWith(".csv")) {
                        String newKey = key.substring(0, key.length() - 4);
                        csvConfigMap.put(newKey, csvConfigMap.get(key));
                        csvConfigMap.remove(key);
                    }
                }

                log.info("Loaded config from {}", file.getAbsolutePath());
                log.info("CSV Config Map: {}", csvConfigMap);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        } else {
            log.error("ConfigLoader: Input directory does not exist: {}", INPUT_DIR);
        }
    }

    /**
     * Returns the CSVConfig for the given csvName. If the config does not exist, it returns an empty Optional.
     *
     * @param fileName The name of the CSV file (without .csv extension)
     * @return An Optional containing the CSVConfig if it exists, or an empty Optional if it does not
     */
    public CSVConfig getConfigFor(@NotNull String fileName) {
        if (fileName.endsWith(".csv") || fileName.endsWith(".sql")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        CSVConfig csvConfig = this.csvConfigMap.get(fileName);
        if (csvConfig != null) {
            log.info("Found config for file {}, using dataset_name {}", fileName, csvConfig.getDataset_name());
        } else {
            log.warn("No config found for file {}, using default settings", fileName);
        }

        return csvConfig;
    }

    /**
     * Returns all the CSV configurations loaded from the config.json file.
     * @return A map of CSV file names to their respective configurations
     */
    public Map<String, CSVConfig> getAllConfigs() {
        return csvConfigMap;
    }

    public void add(String fileName, CSVConfig csvConfig) {
        this.csvConfigMap.put(fileName, csvConfig);
    }

}
