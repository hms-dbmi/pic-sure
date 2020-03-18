package edu.harvard.hms.dbmi.avillach.hpds.data.genotype;

import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class VariantMetadataIndexTest {

	private VariantMetadataIndex vmi;
	public static String binFile = "/opt/local/hpds/all/VariantMetadata.javabin";

	@BeforeEach
	public void initialize() {
		try {
			vmi = new VariantMetadataIndex();
			vmi.initializeRead(binFile);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	@Test
	@DisplayName("Single VariantSpec Lookup")
	public void testSingleVariantSpec() {
		String[] data = vmi.findBySingleVariantSpec("14,19000096,G,C11111");  
		assertNotNull(data);

		data = vmi.findBySingleVariantSpec("14,19000096,G,C"); 
		assertNotNull(data);

		//data = vmi.findBySingleVariantSpec(null); 
		//assertNotNull(data);
	}

	@Test
	@DisplayName("Multiple VariantSpec Lookup")
	public void testMultipleVariantSpec() {
		Map<String, String[]> data = vmi.findByMultipleVariantSpec(List.of("14,19000096,G,C", "14,19000105,A,G")); 
		assertNotNull(data);

		data = vmi.findByMultipleVariantSpec(List.of("14,19000096,G,C")); 
		assertNotNull(data);

		//data = vmi.findByMultipleVariantSpec(null); 
		//assertNotNull(data);
	}
}
