package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.common.cache.LoadingCache;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

public class HideAnnotationCategoryValue {
	protected static LoadingCache<String, PhenoCube<?>> store;

	protected static TreeMap<String, ColumnMeta> metaStoreSource;

	protected static TreeSet<Integer> allIds;
	
	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
		String infoStoreToModify = args[0];
		String valueToScrub = args[1];
		
		String infoStoreFilename = "/opt/local/hpds/all/" + infoStoreToModify.trim();
		try (
				FileInputStream fis = new FileInputStream(infoStoreFilename);
				GZIPInputStream gis = new GZIPInputStream(fis);
				ObjectInputStream ois = new ObjectInputStream(gis)
				){
			FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
			infoStore.allValues.keys().remove(valueToScrub);
			try(
					FileOutputStream fos = new FileOutputStream(infoStoreFilename);
					GZIPOutputStream gos = new GZIPOutputStream(fos);
					ObjectOutputStream oos = new ObjectOutputStream(gos);
					){
				oos.writeObject(infoStore);
				oos.flush();oos.close();
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
}