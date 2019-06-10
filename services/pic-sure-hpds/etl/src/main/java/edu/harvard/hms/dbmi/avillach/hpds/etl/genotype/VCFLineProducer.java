package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Streams;

import htsjdk.samtools.util.BlockCompressedInputStream;

public class VCFLineProducer {

	private TreeMap<Integer, File> inputFiles;
	public ArrayBlockingQueue<VCFLine> vcfLineQueue;
	public String patientId;
	private int sampleIndex;
	public boolean processAnnotations;
	private boolean vcfIsGzipped;

	public VCFLineProducer(int sampleIndex, boolean processAnnotations, boolean vcfIsGzipped, TreeMap<Integer, File> chromosomeFileMap, String patientId, ArrayBlockingQueue<VCFLine> vcfLineQueue) {
		this.inputFiles = chromosomeFileMap;
		this.patientId = patientId;
		this.vcfLineQueue = vcfLineQueue;
		this.sampleIndex = sampleIndex;
		this.processAnnotations = processAnnotations;
		this.vcfIsGzipped = vcfIsGzipped;
	}

	private Thread thread;

	public ArrayList<String> headerLines = new ArrayList<String>();

	private static LoadingCache<String, VCFLine> vcfLineCache = CacheBuilder.newBuilder().maximumSize(10).build(new CacheLoader<String, VCFLine>(){
		@Override
		public VCFLine load(String key) throws Exception {
			return new VCFLine(splitLineCache.get(key), 0, false);
		}});

	private static LoadingCache<String, String[]> splitLineCache = CacheBuilder.newBuilder().maximumSize(10).build(new CacheLoader<String, String[]>(){

		@Override
		public String[] load(String key) throws Exception {
			return key.split("\t");
		}

	});

	private static HashMap<String, LoadingCache<Long, String>> fileCaches = new HashMap<>();

	public void start() {
		thread = new Thread(()->{
			List<String> filenames = inputFiles.values().stream().map((File f)->{return f.getAbsolutePath();}).collect(Collectors.toList());
			String cacheKey = String.join("+", filenames);
			System.out.println("Making sure cache exists for key : " + cacheKey);
			synchronized(fileCaches) {
				System.out.println("Checking if cache exists : " + cacheKey);
				LoadingCache<Long, String> lineCacheForFile = fileCaches.get(cacheKey);
				if(lineCacheForFile==null) {
					System.out.println("Creating Cache : " + cacheKey);
					lineCacheForFile = CacheBuilder.newBuilder().maximumSize(IndexedVCFLocalLoader.VCF_LINE_QUEUE_SIZE+100).build(new CacheLoader<Long, String>(){
						InputStream in = new SequenceInputStream(Collections.enumeration(inputFiles.keySet().stream().map((key)->{try {
							return  vcfIsGzipped ? new BlockCompressedInputStream(new FileInputStream(inputFiles.get(key))) : new FileInputStream(inputFiles.get(key));
						} catch (FileNotFoundException e) {
							throw new RuntimeException("File not found, please fix the VCF index file : " + inputFiles.get(key).getAbsolutePath(), e);
						}}).collect(Collectors.toList())));
						InputStreamReader reader = new InputStreamReader(in);
						BufferedReader reader2 = new BufferedReader(reader);
						@Override
						public String load(Long key) throws Exception {
							String line = reader2.readLine();
							return line == null ? "" : line;
						}
					});
					System.out.println("Created cache for : " + cacheKey);
					fileCaches.put(cacheKey, lineCacheForFile);
				}
			}

			long lineNumber = 0;
			LoadingCache<Long, String> fileCache = fileCaches.get(cacheKey);
			try{
				String rawline = fileCache.get(lineNumber);
				while(!rawline.isEmpty()) {
					if(rawline.startsWith("#")) {
						System.out.println("Header" + rawline);
					} else {
						VCFLine lineHusk = vcfLineCache.get(rawline);

						try {
							VCFLine vcfLine = lineHusk.clone();
							vcfLine.patientId = patientId;
							vcfLine.data = splitLineCache.get(rawline)[9+sampleIndex];
							vcfLineQueue.put(vcfLine);	
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}							

					}
					lineNumber++;
					rawline = fileCache.get(lineNumber);
				}			
			}catch(ExecutionException e) {
				throw new RuntimeException("Why did this happen? ", e);
			}

		});
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
	}

	public boolean isRunning() {
		return thread.isAlive();
	}
}
