package edu.harvard.dbmi.avillach.dictionary.facet;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class FacetCategoryExtractor implements ResultSetExtractor<List<FacetCategory>> {

    @Override
    public List<FacetCategory> extractData(ResultSet rs) throws SQLException, DataAccessException {
        List<Facet> facets = new ArrayList<>();
        Map<String, FacetCategory> categories = new HashMap<>();

        while (rs.next()) {
            // build out all the facets and make shells of the facet categories
            String category = rs.getString("category_name");
            Facet facet = new Facet(
                rs.getString("name"), rs.getString("display"),
                rs.getString("description"), rs.getString("full_name"), rs.getInt("facet_count"),
                null, category, null
            );
            FacetCategory facetCategory = new FacetCategory(
                category, rs.getString("category_display"),
                rs.getString("category_description"), List.of()
            );
            facets.add(facet);
            categories.put(category, facetCategory);
        }
        // group facets by category, then add them to their respective category
        Map<String, List<Facet>> grouped = facets.stream().collect(Collectors.groupingBy(Facet::category));
        return categories.entrySet().stream()
            .map(e -> new FacetCategory(
                e.getValue(),
                grouped.getOrDefault(e.getKey(), List.of()).stream().sorted(Comparator.comparingInt(Facet::count).reversed()).toList()
            ))
            .toList();
    }
}
