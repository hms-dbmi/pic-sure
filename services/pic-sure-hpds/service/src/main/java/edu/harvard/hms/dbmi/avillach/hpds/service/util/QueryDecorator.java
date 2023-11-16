package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QueryDecorator {
    private static final Logger LOG = LoggerFactory.getLogger(QueryDecorator.class);
    
    public void setId(Query query) {
        query.setId(""); // the id is included in the toString
        // I clear it here to keep the ID setting stable for any query
        // of identical structure and content
        
        // Some places where we call toString, we call mergeFilterFieldsIntoSelectedFields
        // first. This can mutate the query, resulting in shifting UUIDs
        // To stabilize things, we're always going to call that, and shift the logic here
        mergeFilterFieldsIntoSelectedFields(query);

        // we also sort things sometimes
        List<String> fields = query.getFields();
        Collections.sort(fields);
        query.setFields(fields);
        
        String id = UUIDv5.UUIDFromString(query.toString()).toString();
        query.setId(id);
    }

    public void mergeFilterFieldsIntoSelectedFields(Query query) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(query.getFields());
        
        if(!query.getCategoryFilters().isEmpty()) {
            Set<String> categoryFilters = new TreeSet<>(query.getCategoryFilters().keySet());
            Set<String> toBeRemoved = new TreeSet<>();
            for(String categoryFilter : categoryFilters) {
                LOG.debug("In : {}", categoryFilter);
                if(VariantUtils.pathIsVariantSpec(categoryFilter)) {
                    toBeRemoved.add(categoryFilter);
                }
            }
            categoryFilters.removeAll(toBeRemoved);
            for(String categoryFilter : categoryFilters) {
                LOG.debug("Out : {}", categoryFilter);
            }
            fields.addAll(categoryFilters);
        }
        fields.addAll(query.getAnyRecordOf());
        fields.addAll(query.getRequiredFields());
        fields.addAll(query.getNumericFilters().keySet());
        query.setFields(fields);
    }
}
