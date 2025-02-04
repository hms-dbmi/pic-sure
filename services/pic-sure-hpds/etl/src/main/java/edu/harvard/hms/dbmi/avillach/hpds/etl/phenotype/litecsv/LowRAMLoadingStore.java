package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;

import com.google.common.cache.*;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto.DEFAULT_KEY_NAME;
import static java.nio.file.StandardOpenOption.*;

/**
 * This class provides similar functioanlity to the LoadingStore class, but is designed with sequential loading in mind.
 * 
 * This will write out partial cubes to individual files instead of assuming that they can immediately be sequenced into the
 * allObservationStore; This will allow us to pick them back up and add more patients to existing stores so that we can better handle
 * fragmented data.
 * 
 * 
 * @author nchu
 *
 */
public class LowRAMLoadingStore {

    private final String columnmetaFilename;
    protected final String observationsFilename;
    protected final String obsTempFilename;
    private final String encryptionKeyName;

    private final RandomAccessFile allObservationsTemp;

    TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();

    private static Logger log = LoggerFactory.getLogger(LowRAMLoadingStore.class);

    public LowRAMLoadingStore() {
        this(
            "/opt/local/hpds/allObservationsTemp.javabin", "/opt/local/hpds/columnMeta.javabin",
            "/opt/local/hpds/allObservationsStore.javabin", DEFAULT_KEY_NAME
        );
    }

    public LowRAMLoadingStore(
        String observationsTempFile, String columnMetaTempFile, String observationsPermFile, String encryptionKeyName
    ) {
        obsTempFilename = observationsTempFile;
        columnmetaFilename = columnMetaTempFile;
        observationsFilename = observationsPermFile;
        this.encryptionKeyName = encryptionKeyName;
        try {
            allObservationsTemp = new RandomAccessFile(obsTempFilename, "rw");
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    public LoadingCache<String, PhenoCube> loadingCache =
        CacheBuilder.newBuilder().maximumSize(1).removalListener(new RemovalListener<String, PhenoCube>() {

            @Override
            public void onRemoval(RemovalNotification<String, PhenoCube> cubeRemoval) {
                if (cubeRemoval.getValue().getLoadingMap() != null) {
                    try (
                        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(); ObjectOutputStream out =
                            new ObjectOutputStream(byteStream)
                    ) {
                        ColumnMeta columnMeta = new ColumnMeta().setName(cubeRemoval.getKey())
                            .setWidthInBytes(cubeRemoval.getValue().getColumnWidth()).setCategorical(cubeRemoval.getValue().isStringType());
                        columnMeta.setAllObservationsOffset(allObservationsTemp.getFilePointer());
                        // write out the basic key/value map for loading; this will be compacted and finalized after all concepts are read
                        // in.
                        out.writeObject(cubeRemoval.getValue().getLoadingMap());
                        out.flush();

                        allObservationsTemp.write(byteStream.toByteArray());
                        columnMeta.setAllObservationsLength(allObservationsTemp.getFilePointer());
                        metadataMap.put(columnMeta.getName(), columnMeta);
                    } catch (IOException e1) {
                        throw new UncheckedIOException(e1);
                    }
                }
            }
        }).build(new CacheLoader<>() {
            public PhenoCube load(String key) throws Exception {
                ColumnMeta columnMeta = metadataMap.get(key);
                if (columnMeta != null) {
                    log.debug("Loading concept : [" + key + "]");
                    return getCubeFromTemp(columnMeta);
                } else {
                    return null;
                }
            }
        });

    public TreeSet<Integer> allIds = new TreeSet<Integer>();

    public void saveStore() throws FileNotFoundException, IOException, ClassNotFoundException {
        log.info("flushing temp storage");
        loadingCache.invalidateAll();
        loadingCache.cleanUp();

        RandomAccessFile allObservationsStore = new RandomAccessFile(observationsFilename, "rw");
        // we dumped it all in a temp file; now sort all the data and compress it into the real Store
        for (String concept : metadataMap.keySet()) {
            ColumnMeta columnMeta = metadataMap.get(concept);
            log.debug("Writing concept : [{}]", concept);
            PhenoCube cube = getCubeFromTemp(columnMeta);
            complete(columnMeta, cube);
            write(allObservationsStore, columnMeta, cube);
        }
        allObservationsStore.close();

        log.info("Writing metadata");
        ObjectOutputStream metaOut = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(columnmetaFilename))));
        metaOut.writeObject(metadataMap);
        metaOut.writeObject(allIds);
        metaOut.flush();
        metaOut.close();

        log.info("Cleaning up temporary file");

        allObservationsTemp.close();
        File tempFile = new File(obsTempFilename);
        tempFile.delete();
        dumpStatsAndColumnMeta("/opt/local/hpds/");
    }

    private void write(RandomAccessFile allObservationsStore, ColumnMeta columnMeta, PhenoCube cube) throws IOException {
        columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());

        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(byteStream);) {

            out.writeObject(cube);
            out.flush();
            allObservationsStore.write(Crypto.encryptData(encryptionKeyName, byteStream.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        columnMeta.setAllObservationsLength(allObservationsStore.getFilePointer());
    }

    private PhenoCube getCubeFromTemp(ColumnMeta columnMeta) throws IOException, ClassNotFoundException {
        allObservationsTemp.seek(columnMeta.getAllObservationsOffset());
        int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
        byte[] buffer = new byte[length];
        allObservationsTemp.read(buffer);
        allObservationsTemp.seek(allObservationsTemp.length());
        ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(buffer));

        PhenoCube cube = new PhenoCube(columnMeta.getName(), columnMeta.isCategorical() ? String.class : Double.class);
        cube.setLoadingMap((List<KeyAndValue>) inStream.readObject());
        cube.setColumnWidth(columnMeta.getWidthInBytes());
        inStream.close();
        return cube;
    }

    private <V extends Comparable<V>> void complete(ColumnMeta columnMeta, PhenoCube<V> cube) {
        ArrayList<KeyAndValue<V>> entryList = new ArrayList<KeyAndValue<V>>(cube.getLoadingMap().stream().map((entry) -> {
            return new KeyAndValue<V>(entry.getKey(), entry.getValue(), entry.getTimestamp());
        }).collect(Collectors.toList()));

        List<KeyAndValue<V>> sortedByKey =
            entryList.stream().sorted(Comparator.comparing(KeyAndValue<V>::getKey)).collect(Collectors.toList());
        cube.setSortedByKey(sortedByKey.toArray(new KeyAndValue[0]));

        if (cube.isStringType()) {
            TreeMap<V, List<Integer>> categoryMap = new TreeMap<>();
            for (KeyAndValue<V> entry : cube.sortedByValue()) {
                if (!categoryMap.containsKey(entry.getValue())) {
                    categoryMap.put(entry.getValue(), new LinkedList<Integer>());
                }
                categoryMap.get(entry.getValue()).add(entry.getKey());
            }
            TreeMap<V, TreeSet<Integer>> categorySetMap = new TreeMap<>();
            categoryMap.entrySet().stream().forEach((entry) -> {
                categorySetMap.put(entry.getKey(), new TreeSet<Integer>(entry.getValue()));
            });
            cube.setCategoryMap(categorySetMap);
        }

        columnMeta.setObservationCount(cube.sortedByKey().length);
        columnMeta.setPatientCount(Arrays.stream(cube.sortedByKey()).map((kv) -> {
            return kv.getKey();
        }).collect(Collectors.toSet()).size());
        if (columnMeta.isCategorical()) {
            columnMeta.setCategoryValues(new ArrayList<String>(new TreeSet<String>((List) cube.keyBasedArray())));
        } else {
            List<Double> map = cube.keyBasedArray().stream().map((value) -> {
                return (Double) value;
            }).collect(Collectors.toList());
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            for (double f : map) {
                min = Double.min(min, f);
                max = Double.max(max, f);
            }
            columnMeta.setMin(min);
            columnMeta.setMax(max);
        }

    }

    public void dumpStatsAndColumnMeta(String hpdsDirectory) {
        try (
            FileInputStream fIn = new FileInputStream(hpdsDirectory + "columnMeta.javabin"); GZIPInputStream gIn =
                new GZIPInputStream(fIn); ObjectInputStream oIn = new ObjectInputStream(gIn); BufferedWriter csvWriter =
                    Files.newBufferedWriter(Paths.get(hpdsDirectory + "columnMeta.csv"), CREATE, TRUNCATE_EXISTING)
        ) {
            TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) oIn.readObject();
            CSVPrinter printer = new CSVPrinter(csvWriter, CSVFormat.DEFAULT);
            for (String key : metastore.keySet()) {
                String[] columnMetaOut = createRow(key, metastore);
                printer.printRecord(columnMetaOut);
            }
            csvWriter.flush();
        } catch (IOException | ClassNotFoundException e) {
            log.error("Error loading store or dumping store meta to CSV: ", e);
        }
    }

    private static String[] createRow(String key, TreeMap<String, ColumnMeta> metastore) {
        ColumnMeta columnMeta = metastore.get(key);
        String[] columnMetaOut = new String[11];

        StringBuilder listQuoted = new StringBuilder();
        AtomicInteger x = new AtomicInteger(1);

        if (columnMeta.getCategoryValues() != null) {
            if (!columnMeta.getCategoryValues().isEmpty()) {
                columnMeta.getCategoryValues().forEach(string -> {
                    listQuoted.append(string);
                    if (x.get() != columnMeta.getCategoryValues().size()) listQuoted.append("µ");
                    x.incrementAndGet();
                });
            }
        }

        columnMetaOut[0] = columnMeta.getName();
        columnMetaOut[1] = String.valueOf(columnMeta.getWidthInBytes());
        columnMetaOut[2] = String.valueOf(columnMeta.getColumnOffset());
        columnMetaOut[3] = String.valueOf(columnMeta.isCategorical());
        // this should nest the list of values in a list inside the String array.
        columnMetaOut[4] = listQuoted.toString();
        columnMetaOut[5] = String.valueOf(columnMeta.getMin());
        columnMetaOut[6] = String.valueOf(columnMeta.getMax());
        columnMetaOut[7] = String.valueOf(columnMeta.getAllObservationsOffset());
        columnMetaOut[8] = String.valueOf(columnMeta.getAllObservationsLength());
        columnMetaOut[9] = String.valueOf(columnMeta.getObservationCount());
        columnMetaOut[10] = String.valueOf(columnMeta.getPatientCount());
        return columnMetaOut;
    }


}
