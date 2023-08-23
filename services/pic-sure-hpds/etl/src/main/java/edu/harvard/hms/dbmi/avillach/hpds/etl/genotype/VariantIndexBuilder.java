package edu.harvard.hms.dbmi.avillach.hpds.etl.genotype;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VariantIndexBuilder {

    private static Logger logger = LoggerFactory.getLogger(VariantIndexBuilder.class);

    private final LinkedList<String> variantSpecIndex = new LinkedList<>();
    private final Map<String, Integer> variantSpecToIndexMap = new ConcurrentHashMap<>();

    public synchronized Integer getIndex(String variantSpec) {
        Integer variantIndex = variantSpecToIndexMap.get(variantSpec);
        if (variantIndex == null) {
            variantIndex = variantSpecIndex.size();
            variantSpecIndex.add(variantSpec);
            variantSpecToIndexMap.put(variantSpec, variantIndex);
        }
        return variantIndex;
    }

    public LinkedList<String> getVariantSpecIndex() {
        return variantSpecIndex;
    }
}
