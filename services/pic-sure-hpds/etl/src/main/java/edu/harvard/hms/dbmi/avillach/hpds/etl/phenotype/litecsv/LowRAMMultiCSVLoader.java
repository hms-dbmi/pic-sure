package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class LowRAMMultiCSVLoader {

    private static final Logger log = LoggerFactory.getLogger(LowRAMMultiCSVLoader.class);
    private final LowRAMCSVProcessor processor;
    private final String inputDir;

    public LowRAMMultiCSVLoader(LowRAMLoadingStore store, LowRAMCSVProcessor processor, String inputDir) {
        this.inputDir = inputDir == null ? "/opt/local/hpds_input" : inputDir;
        store = store == null ? new LowRAMLoadingStore() : store;
        this.processor = processor == null ? new LowRAMCSVProcessor(store, false, 5D) : processor;
        Crypto.loadDefaultKey();
    }

    public static void main(String[] args) {
        boolean rollUpVarNames = true;
        double maxChunkSize = 5D;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("NO_ROLLUP")) {
                log.info("Configured to not roll up variable names");
                rollUpVarNames = false;
            }

            if (arg.contains("MAX_CHUNK_SIZE")) {
                String[] parts = arg.split("=");
                if (parts.length == 2) {
                    try {
                        maxChunkSize = Double.parseDouble(parts[1]);
                        log.info("Configured to use a max chunk size of {} GB", maxChunkSize);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid max chunk size " + maxChunkSize);
                    }
                }
            }
        }

        String inputDir = "/opt/local/hpds_input";
        ConfigLoader configLoader = new ConfigLoader();
        LowRAMLoadingStore store = new LowRAMLoadingStore();
        LowRAMCSVProcessor lowRAMCSVProcessor = new LowRAMCSVProcessor(store, rollUpVarNames, maxChunkSize, configLoader);
        int exitCode = new LowRAMMultiCSVLoader(store, lowRAMCSVProcessor, inputDir).processCSVsFromHPDSDir(maxChunkSize);
        try {
            store.saveStore();
        } catch (IOException | ClassNotFoundException e) {
            log.error("Error saving store: ", e);
            System.exit(1);
        }
        System.exit(exitCode);
    }

    protected int processCSVsFromHPDSDir(double maxChunkSize) {
        // find all files
        log.info("Looking for files to process. All files must be smaller than {}G", maxChunkSize);
        log.info("Files larger than {}G should be split into a series of CSVs", maxChunkSize);
        try (Stream<Path> input_files = Files.list(Path.of(inputDir))) {
            input_files.map(Path::toFile).filter(File::isFile).peek(f -> log.info("Found file {}", f.getAbsolutePath()))

                .filter(f -> f.getName().endsWith(".csv")).peek(f -> log.info("Confirmed file {} is a .csv", f.getAbsolutePath()))

                .map(processor::process).forEach(status -> log.info("Finished processing file {}", status));
            return 0;
        } catch (IOException e) {
            log.error("Exception processing files: ", e);
            return 1;
        }
    }


}
