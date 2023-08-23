package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;

public class VCFPerPatientInfoStoreToFBBIISConverter {
	private static Logger logger = LoggerFactory.getLogger(NewVCFLoader.class);

	public static void convertAll(String inputPath, String outputPath) throws ClassNotFoundException,
			FileNotFoundException, IOException, InterruptedException, ExecutionException {
		File[] inputFiles = new File(inputPath).listFiles((path) -> {
			return path.getName().endsWith("infoStore.javabin");
		});
		Arrays.sort(inputFiles);

		File outputFolder = new File(outputPath);

		List<File> inputFileList = Arrays.asList(inputFiles);
		inputFileList.stream().forEach((file) -> {
			convert(outputFolder, file);
		});
	}

	public static void convert(File outputFolder, File file) {
		logger.info("Converting InfoStore file: " + file.getAbsolutePath());
		try (FileInputStream fisi = new FileInputStream(file);
				GZIPInputStream gzisi = new GZIPInputStream(fisi);
				ObjectInputStream objectInputStream = new ObjectInputStream(gzisi);) {
			InfoStore store = (InfoStore) objectInputStream.readObject();

			if (store.allValues.size() > 0) {
				FileBackedByteIndexedInfoStore fbbiis = new FileBackedByteIndexedInfoStore(outputFolder, store);

				fbbiis.write(new File(outputFolder, file.getName()));
				logger.info("Completed converting InfoStore file: " + file.getAbsolutePath());
			} else {
				logger.info("Skipping empty InfoStore file: " + file.getAbsolutePath() + "");
			}
		} catch (FileNotFoundException e) {
			logger.error("InfoStore file not found: " + file.getAbsolutePath());
		} catch (IOException e) {
			logger.error("Error converting InfoStore file: " + file.getAbsolutePath());
		} catch (ClassNotFoundException e) {
			logger.error("Error converting InfoStore file: " + file.getAbsolutePath());
		}
	}

}