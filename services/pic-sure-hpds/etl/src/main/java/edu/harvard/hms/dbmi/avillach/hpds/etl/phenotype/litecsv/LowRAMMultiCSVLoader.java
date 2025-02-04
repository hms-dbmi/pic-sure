package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
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
        if (args.length > 1) {
            if (args[0].equalsIgnoreCase("NO_ROLLUP")) {
                log.info("Configured to not roll up variable names");
                rollUpVarNames = false;
            }
        }
        String inputDir = "/opt/local/hpds_input";
        LowRAMLoadingStore store = new LowRAMLoadingStore();
        LowRAMCSVProcessor lowRAMCSVProcessor = new LowRAMCSVProcessor(store, rollUpVarNames, 5D);
        int exitCode = new LowRAMMultiCSVLoader(store, lowRAMCSVProcessor, inputDir).processCSVsFromHPDSDir();
        try {
            store.saveStore();
        } catch (IOException | ClassNotFoundException e) {
            log.error("Error saving store: ", e);
            System.exit(1);
        }
        System.exit(exitCode);
    }

    protected int processCSVsFromHPDSDir() {
        // find all files
        log.info("Looking for files to process. All files must be smaller than 5G");
        log.info("Files larger than 5G should be split into a series of CSVs");
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
