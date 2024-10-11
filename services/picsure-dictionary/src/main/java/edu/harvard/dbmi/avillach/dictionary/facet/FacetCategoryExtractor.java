package edu.harvard.dbmi.avillach.dictionary.facet;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class FacetCategoryExtractor implements ResultSetExtractor<List<FacetCategory>> {

    private record Pair(String parent, String category) {
        Pair(Facet facet) {
            this(facet.name(), facet.category());
        }
    };

    @Override
    public List<FacetCategory> extractData(ResultSet rs) throws SQLException, DataAccessException {
        List<Facet> facets = new ArrayList<>();
        Map<String, FacetCategory> categories = new HashMap<>();
        Map<Pair, List<Facet>> childrenForParent = new HashMap<>();

        while (rs.next()) {
            // build out all the facets and make shells of the facet categories
            String category = rs.getString("category_name");
            Facet facet = new Facet(
                rs.getString("name"), rs.getString("display"), rs.getString("description"), rs.getString("full_name"),
                rs.getInt("facet_count"), List.of(), category, null
            );
            FacetCategory facetCategory =
                new FacetCategory(category, rs.getString("category_display"), rs.getString("category_description"), List.of());
            String parentName = rs.getString("parent_name");
            if (StringUtils.hasLength(parentName)) {
                Pair key = new Pair(parentName, category);
                List<Facet> facetsForParent = childrenForParent.getOrDefault(key, new ArrayList<>());
                facetsForParent.add(facet);
                childrenForParent.put(key, facetsForParent);
            } else {
                facets.add(facet);
            }
            categories.put(category, facetCategory);
        }
        facets = facets.stream().map(f -> f.withChildren(childrenForParent.getOrDefault(new Pair(f), List.of()))).toList();
        // group facets by category, then add them to their respective category
        Map<String, List<Facet>> grouped = facets.stream().collect(Collectors.groupingBy(Facet::category));
        return categories.entrySet().stream()
            .map(
                e -> new FacetCategory(
                    e.getValue(),
                    grouped.getOrDefault(e.getKey(), List.of()).stream().sorted(Comparator.comparingInt(Facet::count).reversed()).toList()
                )
            ).toList();
    }
}
