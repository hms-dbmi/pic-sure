package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

public class InfoStore implements Serializable {
	
	private static final long serialVersionUID = 6478256007934827195L;
	public final String column_key;
	public final String description;

	public ConcurrentHashMap<String, ConcurrentSkipListSet<String>> allValues = new ConcurrentHashMap<>();
	private String prefix;

	public List<String> search(String term) {
		if(isNumeric()) {
			return new ArrayList<String>();
		}else {
			return allValues.keySet().stream().filter((value)->{
				String lowerTerm = term.toLowerCase();
				return value.toLowerCase().contains(lowerTerm);
			}).collect(Collectors.toList());			
		}
	}

	public void processRecord(VariantSpec spec, String[] values) {
		for(String value : values) {
			if(value.startsWith(column_key + "=")) {
				String valueWithoutkey = value.replaceFirst(column_key + "=", "");
				ConcurrentSkipListSet<String> entriesForValue = allValues.get(valueWithoutkey);
				if(entriesForValue == null) {
					entriesForValue = new ConcurrentSkipListSet<>();
					allValues.put(valueWithoutkey, entriesForValue);
				}
				entriesForValue.add(spec.specNotation());
			}
		}
	}


	public InfoStore(String description, String delimiter, String key) {
		this.prefix = key + "=";
		this.description = description;
		this.column_key = key;
	}

	public boolean isNumeric() {
		int nonNumericCount = 0;
		int numericCount = 0;
		System.out.println("Testing for numeric : " + this.column_key + " : " + allValues.size() + " values");
		KeySetView<String, ConcurrentSkipListSet<String>> allKeys = allValues.keySet();
		for(String key : allKeys){
			try {
				Double.parseDouble(key);
				numericCount++;
			}catch(NumberFormatException e) {
				String[] keys = key.split(",");
				for(String key2 : keys) {
					try {
						Double.parseDouble(key2);
						numericCount++;
					}catch(NumberFormatException e2) {
						if(key.contentEquals(".")) {
							nonNumericCount++;							
						}
					}
				}
			}
			if(nonNumericCount > 1) {
				System.out.println(this.column_key + " is not numeric");
				return false;
			}
			if(numericCount > 10000 || numericCount > (allKeys.size()/2)) {
				System.out.println(this.column_key + " is numeric");
				return true;
			}
		};
		System.out.println(this.column_key + " is not numeric");
		return false;
	}

	public void processRecord(String specNotation, String[] infoValues) {
		for(String value : infoValues) {
			if(value.startsWith(prefix)) {
				String valueWithoutkey = value.replaceFirst(prefix, "");
				ConcurrentSkipListSet<String> entriesForValue = allValues.get(valueWithoutkey);
				if(entriesForValue == null) {
					entriesForValue = new ConcurrentSkipListSet<>();
					allValues.put(valueWithoutkey, entriesForValue);
				}
				entriesForValue.add(specNotation);
			}
		}
	}

}