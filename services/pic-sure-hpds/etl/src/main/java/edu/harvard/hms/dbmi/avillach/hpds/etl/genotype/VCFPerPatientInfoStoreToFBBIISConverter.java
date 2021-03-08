package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.InfoStore;

public class VCFPerPatientInfoStoreToFBBIISConverter {
	public static void convertAll(String inputPath, String outputPath) throws ClassNotFoundException, FileNotFoundException, IOException, InterruptedException, ExecutionException {
		File[] inputFiles = new File(inputPath).listFiles(
				(path)->{
					return path.getName().endsWith("infoStore.javabin");
				});
		Arrays.sort(inputFiles);

		File outputFolder = new File(outputPath);

		List<File> inputFileList = Arrays.asList(inputFiles);
		inputFileList.stream().forEach((file)->{
			convert(outputFolder, file);
		});
	}

	public static void convert(File outputFolder, File file) {
		System.out.println("opening : " + file.getAbsolutePath());
		try (
				FileInputStream fisi = new FileInputStream(file);
				GZIPInputStream gzisi = new GZIPInputStream(fisi);
				ObjectInputStream objectInputStream = new ObjectInputStream(gzisi);
				){
			InfoStore store = (InfoStore) objectInputStream.readObject();

			if(store.allValues.size()>0) {
				FileBackedByteIndexedInfoStore fbbiis = new FileBackedByteIndexedInfoStore(outputFolder, store);

				writeStore(new File(outputFolder, file.getName()), fbbiis);
				System.out.println("completed converting " + file.getAbsolutePath());				
			}else {
				System.out.println("Skipping empty InfoStore : " + file.getAbsolutePath());
			}
		}catch(FileNotFoundException e) {
			System.out.println("file not found " + file.getAbsolutePath());
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static synchronized void writeStore(File outputFile, FileBackedByteIndexedInfoStore fbbiis)
			throws FileNotFoundException, IOException {
		FileOutputStream fos = new FileOutputStream(outputFile);
		GZIPOutputStream gzos = new GZIPOutputStream(fos);
		ObjectOutputStream oos = new ObjectOutputStream(gzos);
		oos.writeObject(fbbiis);
		oos.flush();oos.close();
		gzos.flush(); gzos.close();
		fos.flush();fos.close();
	}

}
