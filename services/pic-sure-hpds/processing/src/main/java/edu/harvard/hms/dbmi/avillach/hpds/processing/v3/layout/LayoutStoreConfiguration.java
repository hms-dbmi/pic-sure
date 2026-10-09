package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import edu.harvard.hms.dbmi.avillach.hpds.processing.PhenotypeMetaStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TreeSet;

/**
 * Beans that replace the javabin ones when {@code HPDS_STORE_FORMAT=layout}. The dictionary that search, health and the legacy beans read
 * comes from {@code pheno.index} instead of {@code columnMeta.javabin}, so the service starts and reports ready with only the layout files
 * present.
 */
@Configuration
@ConditionalOnProperty(name = StoreFormat.PROPERTY, havingValue = StoreFormat.LAYOUT)
public class LayoutStoreConfiguration {

    /**
     * The phenotype dictionary built from the layout index. Its patient id set is left empty: the layout beans read patients from the
     * index's bitmap, and a {@code TreeSet<Integer>} would hold a boxed copy of every patient id that nothing in layout mode reads.
     *
     * @param store the layout store
     * @param childConceptCacheSize {@code CHILD_CONCEPT_CACHE_SIZE}, as for the javabin dictionary
     * @return the dictionary
     */
    @Bean
    public PhenotypeMetaStore layoutPhenotypeMetaStore(
        LayoutPhenotypicObservationStore store, @Value("${CHILD_CONCEPT_CACHE_SIZE:500}") int childConceptCacheSize
    ) {
        return new PhenotypeMetaStore(store.index().toColumnMetaMap(), new TreeSet<>(), childConceptCacheSize);
    }
}
