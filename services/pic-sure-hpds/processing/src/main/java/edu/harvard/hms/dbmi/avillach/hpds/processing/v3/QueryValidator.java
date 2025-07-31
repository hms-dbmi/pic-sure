package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QueryValidator {

    private static final Logger log = LoggerFactory.getLogger(QueryValidator.class);

    private final PhenotypicQueryExecutor phenotypicQueryExecutor;

    private final PhenotypicFilterValidator phenotypicFilterValidator;

    @Autowired
    public QueryValidator(PhenotypicQueryExecutor phenotypicQueryExecutor, PhenotypicFilterValidator phenotypicFilterValidator) {
        this.phenotypicQueryExecutor = phenotypicQueryExecutor;
        this.phenotypicFilterValidator = phenotypicFilterValidator;
    }

    public void validate(Query query) {
        Map<String, ColumnMeta> metaStore = phenotypicQueryExecutor.getMetaStore();

        query.allFilters().forEach(phenotypicFilter -> phenotypicFilterValidator.validate(phenotypicFilter, metaStore));

        query.authorizationFilters().forEach(authorizationFilter -> {
            phenotypicFilterValidator.validate(authorizationFilter, metaStore);
        });

        query.select().forEach(select -> {
            if (!metaStore.containsKey(select)) {
                log.debug(select + " is not a valid concept path");
            }
        });
    }
}
