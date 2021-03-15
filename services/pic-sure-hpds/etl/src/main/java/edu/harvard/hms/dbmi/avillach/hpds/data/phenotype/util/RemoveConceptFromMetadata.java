package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TreeMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
/**
 * 
 * This util will remove a concept path from the columnMetadata.java
 * 
 * 
 * @author Thomas DeSain
 *
 */
public class RemoveConceptFromMetadata {
	
	protected static final String COLUMN_META_FILE = "/opt/local/hpds/columnMeta.javabin";
		
	protected static final String COLUMNS_TO_REMOVE = "/opt/local/hpds/columnsToRemove.txt";
	
	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException {
	
		if(Files.exists(Paths.get(COLUMNS_TO_REMOVE))) {
			throw new RuntimeException("Columns to remove file - " + COLUMNS_TO_REMOVE  + " -  does not exist!");

		}
		if(Files.exists(Paths.get(COLUMN_META_FILE))) {
			throw new RuntimeException("Column Metadata file - " + COLUMN_META_FILE  + " -  does not exist!");
		}			

		TreeMap<String, ColumnMeta> metadata = removeMetadata(Paths.get(COLUMNS_TO_REMOVE));
			
		ObjectOutputStream metaOut = new ObjectOutputStream(new FileOutputStream(new File(COLUMN_META_FILE)));
			
		metaOut.writeObject(metadata); 

	}
	
	protected static TreeMap<String, ColumnMeta> removeMetadata(Path filePath) {
		
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream(COLUMN_META_FILE));){
		
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			try(BufferedReader reader = Files.newBufferedReader(filePath)) {
				
				String conceptPathToRemove;
		
				while((conceptPathToRemove = reader.readLine()) != null) {
					
					metastore.remove(conceptPathToRemove);
					
				}
				
			}			
			
			return metastore;
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		} 
	}
}
