package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;
import java.io.FileNotFoundException;
import java.io.IOException;

import static edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader.convertInfoStoresToByteIndexed;

public class ConvertMergedInfoStoresToFBBIS {

	public static void main(String[] args) throws FileNotFoundException, IOException {

		convertInfoStoresToByteIndexed();

	}

}
