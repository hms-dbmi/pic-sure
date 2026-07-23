package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import edu.harvard.hms.dbmi.avillach.hpds.etl.genotype.NewVCFLoader;

import java.io.IOException;


public class ReSplitMergeInfoStores {

    public static void main(String[] args) throws IOException {

        NewVCFLoader newVCFLoader = new NewVCFLoader();

        newVCFLoader.splitInfoStoresByColumn();

        newVCFLoader.convertInfoStoresToByteIndexed();

    }

}
