package edu.harvard.hms.dbmi.avillach.geno.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

public class VariantLoader {

	public static void main(String[] args) throws IOException {
		String sourceDir = args[0];
		String targetFolder = args[1];
		
		VariantStore store = new VariantStore(new File(sourceDir), new File(targetFolder));
		ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(targetFolder, "variantStore.javabin")));
		oos.writeObject(store);
		oos.flush();
		oos.close();
	}
}
