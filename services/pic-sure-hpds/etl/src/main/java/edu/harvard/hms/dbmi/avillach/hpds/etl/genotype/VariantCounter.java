package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;

public class VariantCounter {
	public static void main(String[] args) throws ClassNotFoundException, FileNotFoundException, IOException, InterruptedException, ExecutionException {
		try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream("/opt/local/hpds/all3/variantStore.javabin"));){
			VariantStore variantStore = (VariantStore) ois.readObject();
			variantStore.open();
			ExecutorService ex = Executors.newFixedThreadPool(3);
			Future<int[]> infoCountFuture = ex.submit(()->{
				int[] infoCounts = variantStore.countInfo();
				return infoCounts;
			});
			Future<int[][]> variantCountFuture = ex.submit(()->{
				int[][] variantCounts = variantStore.countVariants();
				return variantCounts;
			});
			Future<int[]> qualityCountFuture = ex.submit(()->{
				int[] qualityCounts = variantStore.countQuality();
				return qualityCounts;
			});
			int[] infoCounts = infoCountFuture.get();
			int[] qualityCounts = qualityCountFuture.get();
			int[][] variantCounts = variantCountFuture.get();
			ex.shutdown();
			ex.awaitTermination(1, TimeUnit.SECONDS);
			
			System.out.println("Variant Counts");
			System.out.println("Chromosome,Heterozygous Variants,Homozygous Variants,Combined Variants");
			for(int x = 1;x<variantCounts.length;x++) {
				System.out.println(x + "," + variantCounts[x][0] + "," + variantCounts[x][1]+","+variantCounts[x][2]);
			}
			System.out.println("Info Counts");
			System.out.println("Chromosome,Info Blobs");
			for(int x = 1;x<infoCounts.length;x++) {
				System.out.println(x + "," + infoCounts[x]);
			}
			System.out.println("Quality Counts");
			System.out.println("Chromosome,Quality Values");
			for(int x = 1;x<qualityCounts.length;x++) {
				System.out.println(x + "," + qualityCounts[x]);
			}
		}
	}
}
