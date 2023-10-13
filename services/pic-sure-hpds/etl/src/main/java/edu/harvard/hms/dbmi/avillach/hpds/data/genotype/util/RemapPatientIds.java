package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import de.siegmar.fastcsv.reader.CsvContainer;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRow;
import de.siegmar.fastcsv.writer.CsvWriter;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.LoadingStore;

public class RemapPatientIds {
	protected static LoadingCache<String, PhenoCube<?>> store;

	protected static TreeMap<String, ColumnMeta> metaStoreSource;

	protected static TreeSet<Integer> allIds;

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;
	
	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		VariantStore variantStore = VariantStore.readInstance("/opt/local/hpds/all/");
		
		String[] oldPatientIds = variantStore.getPatientIds();
		String[] newPatientIds = new String[oldPatientIds.length];
		
		CsvReader reader = new CsvReader();
		CsvContainer csv = reader.read(new FileReader("/opt/local/hpds/mappings.csv"));
		for(CsvRow row : csv.getRows()) {
			String oldId = row.getField(0);
			String newId = row.getField(1);
			for(int x = 0;x<oldPatientIds.length;x++) {
				if(oldPatientIds[x].contentEquals(oldId.trim())) {
					newPatientIds[x] = newId;
					System.out.println(oldId + ", " + newId);
				}
			}
		}
		variantStore.setPatientIds(newPatientIds);
		
		ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream("/opt/local/hpds/all/variantStoreRemapped.javabin")));
		out.writeObject(variantStore);
		out.flush();out.close();
	}
	
}
