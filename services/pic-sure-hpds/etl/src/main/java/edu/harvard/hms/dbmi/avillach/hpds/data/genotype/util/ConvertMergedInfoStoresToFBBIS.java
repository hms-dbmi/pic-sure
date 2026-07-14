package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;
import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader;

import java.io.FileNotFoundException;
import java.io.IOException;


public class ConvertMergedInfoStoresToFBBIS {

	public static void main(String[] args) throws IOException {

		new NewVCFLoader().convertInfoStoresToByteIndexed();

	}

}
