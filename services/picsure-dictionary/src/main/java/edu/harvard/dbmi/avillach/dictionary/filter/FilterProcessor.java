package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Component
public class FilterProcessor {

    public Filter processsFilter(Filter filter) {
        List<Facet> newFacets = filter.facets();
        List<String> newConsents = filter.consents();
        if (filter.facets() != null) {
            newFacets = new ArrayList<>(filter.facets());
            newFacets.sort(Comparator.comparing(Facet::name));
        }
        if (filter.consents() != null) {
            newConsents = new ArrayList<>(newConsents);
            newConsents.sort(Comparator.comparing(Function.identity()));
        }
        filter = new Filter(newFacets, filter.search(), newConsents);

        if (StringUtils.hasLength(filter.search())) {
            filter = new Filter(filter.facets(), filter.search().replaceAll("_", "/"), filter.consents());
        }
        return filter;
    }

}
