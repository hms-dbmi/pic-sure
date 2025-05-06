package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.remote.api.RemoteDictionaryAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@Service
public class DataRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DataRefreshService.class);

    private final RemoteDictionaryRepository repository;
    private final RemoteDictionaryAPI api;

    private final List<Predicate<RemoteDictionary>> updateSequence;

    public DataRefreshService(RemoteDictionaryRepository repository, RemoteDictionaryAPI api) {
        this.repository = repository;
        this.api = api;
        updateSequence = List.of(
            (d) -> updateObject(repository::addConceptsForSite, api::fetchConcepts, "concepts", d),
            (d) -> updateObject(repository::addFacetCategoriesForSite, api::fetchFacetCategories, "facet categories", d),
            (d) -> updateObject(repository::addFacetsForSite, api::fetchFacets, "facets", d),
            (d) -> updateObject(repository::addFacetConceptPairsForSite, api::fetchFacetConceptPairs, "facet concept pairs", d),
            (d) -> updateObject(repository::addConceptMetasForSite, api::fetchConceptMetas, "concept metas", d),
            (d) -> updateObject(repository::addFacetCategoryMetasForSite, api::fetchFacetCategoryMetas, "facet category metas", d),
            (d) -> updateObject(repository::addFacetMetasForSite, api::fetchFacetMetas, "facet metas", d)
        );
    }

    public void refreshDictionary(RemoteDictionary dictionary) {
        log.info("Updating {}", dictionary.fullName());
        repository.dropValuesForSite(dictionary.name());
        repository.pruneHangingEntries();
        log.info("All values for site {} have been dropped", dictionary.fullName());
        boolean success = true;
        for (int i = 0; i < updateSequence.size() && success; i++) {
            success = updateSequence.get(i).test(dictionary);
        }
        if (success) {
            LocalDateTime updated = LocalDateTime.now();
            log.info("Done refreshing values for site {}, setting local update time to {}", dictionary.fullName(), updated);
            repository.setUpdateTimestamp(dictionary.name(), updated);
        } else {
            log.warn("Failed to refresh values for site {}", dictionary.fullName());
        }
    }

    @FunctionalInterface
    private interface RepoAddFn<T> {
        void addObjects(String name, List<T> rows);
    }

    @FunctionalInterface
    private interface PullObjectsFn<T> {
        Optional<List<T>> fetchFromRemote(String name);
    }

    private <T> boolean updateObject(RepoAddFn<T> adder, PullObjectsFn<T> puller, String objectName, RemoteDictionary site) {
        log.info("Refreshing {} for {}", objectName, site.fullName());
        Optional<List<T>> maybeRows = puller.fetchFromRemote(site.name());
        if (maybeRows.isEmpty()) {
            log.error("Error updating {}. Wiping {} and giving up", objectName, site.fullName());
            repository.dropValuesForSite(site.name());
            repository.pruneHangingEntries();
            return false;
        }
        log.info("Successfully fetched {} for {}", objectName, site.fullName());
        adder.addObjects(site.name(), maybeRows.get());
        log.info("Added {} for {}", objectName, site.fullName());
        return true;
    }
}
