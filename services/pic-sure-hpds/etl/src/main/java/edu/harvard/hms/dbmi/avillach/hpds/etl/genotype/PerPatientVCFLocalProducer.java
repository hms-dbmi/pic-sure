package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.GZIPInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * This file is UDN specific currently. IndexedVCFLocalLoader should make it 
 * unnecessary moving forward, but we shouldn't delete it until we prove that 
 * a UDN load can happen using the new class.
 * 
 * @author jason
 *
 */
public class PerPatientVCFLocalProducer {

	private File inputFile;
	public ArrayBlockingQueue<VCFLine> vcfLineQueue;
	public String patientId;

	public PerPatientVCFLocalProducer(File inputFile, ArrayBlockingQueue<VCFLine> vcfLineQueue) {
		this.inputFile = inputFile;
		String[] segments = inputFile.getName().split("[-_]");
		if(segments[1].equalsIgnoreCase("UDN")) {
			this.patientId = segments[1] + segments[2];
		} else {			
			this.patientId = segments[1];
		}
		this.vcfLineQueue = vcfLineQueue;
	}

	private Thread thread;

	public ArrayList<String> headerLines = new ArrayList<String>();

	public void start() {
		thread = new Thread(()->{
			try(
					InputStream in = new FileInputStream(this.inputFile);
					GZIPInputStream gzis = new GZIPInputStream(in);
					InputStreamReader reader = new InputStreamReader(gzis);
					final CSVParser parser = CSVFormat.DEFAULT.withDelimiter('\t').parse(reader);
					){
				Iterator<CSVRecord> iter = parser.iterator();
				while(iter.hasNext()) {
					CSVRecord line = iter.next();
					if(line.get(0).startsWith("#")) {
						headerLines.add(line.get(0));
						System.out.println("Header" + line.get(0));
					} else {
						try {
							vcfLineQueue.put(new VCFLine(line));
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}						
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
	}

	public boolean isRunning() {
		return thread.isAlive();
	}
}
