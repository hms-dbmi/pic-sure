package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.columnmeta;

import edu.harvard.hms.dbmi.avillach.hpds.etl.LoadingStore;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;

public class CreateColumnmetaCSV {
    private static LoadingStore store = new LoadingStore();

       private static Logger log = LoggerFactory.getLogger(CSVLoader.class);

       private static String HPDS_DIRECTORY = "/opt/local/hpds/";

       public static void main(String[] args) throws IOException {
           if (args.length > 0) {
               HPDS_DIRECTORY = args[0] + "/";
           }
           store.allObservationsStore = new RandomAccessFile(HPDS_DIRECTORY + "allObservationsStore.javabin", "rw");
           store.dumpStatsAndColumnMeta(HPDS_DIRECTORY);
       }

}
