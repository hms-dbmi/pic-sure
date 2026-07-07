package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
public class FacetService {

    private static final Logger LOG = LoggerFactory.getLogger(FacetService.class);

    private final FacetRepository repository;
    private final FacetQueryGenerator generator;

    @Autowired
    public FacetService(FacetRepository repository, FacetQueryGenerator generator) {
        this.repository = repository;
        this.generator = generator;
    }

    @Cacheable("facets")
    public List<FacetCategory> getFacets(Filter filter) {
        List<FacetCategory> facetCategories =
            isMultiCategory(filter) ? getMultiCategoryFacetsParallel(filter) : repository.getFacets(filter);

        if (facetCategories.isEmpty()) {
            return facetCategories;
        }

        return applyOrdering(facetCategories);
    }

    private boolean isMultiCategory(Filter filter) {
        if (filter.facets() == null || filter.facets().isEmpty()) {
            return false;
        }
        long categoryCount = filter.facets().stream().map(Facet::category).distinct().count();
        return categoryCount > 1;
    }

    private List<FacetCategory> getMultiCategoryFacetsParallel(Filter filter) {
        List<QueryParamPair> blocks = generator.createMultiCategoryCountBlocks(filter);

        // Execute count blocks in parallel using virtual threads
        Map<String, Integer> mergedCounts = new HashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Integer>>> futures =
                blocks.stream().map(block -> executor.submit(() -> repository.executeCountBlock(block))).toList();

            for (Future<Map<String, Integer>> future : futures) {
                mergedCounts.putAll(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel facet count execution interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Parallel facet count execution failed", e);
        }

        // Get metadata (fast — just facet table data, no counts)
        List<FacetCategory> metadata = repository.getFacetMetadata();

        // Inject counts into metadata
        return metadata.stream().map(cat -> new FacetCategory(cat, cat.facets().stream().map(f -> {
            String key = f.category() + "|" + f.name();
            int count = mergedCounts.getOrDefault(key, 0);
            // Rebuild facet with count, preserving children with their own counts
            List<Facet> children = f.children() == null ? List.of() : f.children().stream().map(child -> {
                String childKey = child.category() + "|" + child.name();
                int childCount = mergedCounts.getOrDefault(childKey, 0);
                return new Facet(
                    child.name(), child.display(), child.description(), child.fullName(), childCount, child.children(), child.category(),
                    child.meta()
                );
            }).toList();
            return new Facet(f.name(), f.display(), f.description(), f.fullName(), count, children, f.category(), f.meta());
        }).sorted(Comparator.comparingInt(Facet::count).reversed()).toList())).toList();
    }

    private List<FacetCategory> applyOrdering(List<FacetCategory> facetCategories) {
        List<String> categoryNames = facetCategories.stream().map(FacetCategory::name).toList();
        Map<String, String> order = repository.getFacetCategoryOrder(categoryNames);

        if (order.isEmpty()) {
            return facetCategories.stream().sorted(Comparator.comparing(FacetCategory::display)).toList();
        }

        int unordered = order.values().stream().map(Integer::parseInt).reduce(Integer.MIN_VALUE, Math::max) + 1;
        return facetCategories.stream().sorted(Comparator.comparing((FacetCategory fc) -> {
            String orderValue = order.get(fc.name());
            return orderValue != null ? Integer.parseInt(orderValue) : unordered;
        }).thenComparing(FacetCategory::display)).toList();
    }

    public Optional<Facet> facetDetails(String facetCategory, String facet) {
        return repository.getFacet(facetCategory, facet).map(f -> new Facet(f, repository.getFacetMeta(facetCategory, facet)));
    }
}
