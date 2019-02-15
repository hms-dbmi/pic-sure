package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

public class VariantLoader {

	public static void main(String[] args) throws IOException {
		String sourceDir = args[0];
		String targetFolder = args[1];

		VariantStore store = new VariantStore();

		for(int x = 0;x<24;x++) {
			store.variantMaskStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(targetFolder, "chr" + x + "masks.bin"));
			store.variantInfoStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(targetFolder, "chr" + x + "info.bin"));
			store.variantQualityStorage[x] = new FileBackedByteIndexedStorage(Integer.class, HashMap.class, new File(targetFolder, "chr" + x + "quality.bin"));
		}

		FileFilter filter = (File file) -> {
			return (file.getName().endsWith("vcf") || file.getName().endsWith("vcf.bgz")) && !file.getName().contains("chrX");
		};

		Arrays.asList(new File(sourceDir).listFiles(filter)).stream().forEach((file)->{
			new VCFConsumer(file, store);
		});


		ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(targetFolder, "variantStore.javabin")));
		oos.writeObject(store);
		oos.flush();
		oos.close();
	}
}
