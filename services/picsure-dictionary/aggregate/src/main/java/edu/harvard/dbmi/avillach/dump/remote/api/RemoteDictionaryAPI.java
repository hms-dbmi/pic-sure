package edu.harvard.dbmi.avillach.dump.remote.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.harvard.dbmi.avillach.dump.entities.*;
import edu.harvard.dbmi.avillach.dump.local.DumpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class RemoteDictionaryAPI {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final Logger log = LoggerFactory.getLogger(RemoteDictionaryAPI.class);
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private static final String rootURL = "http://passthru:80/dictionary-dump/";

    @Autowired
    public RemoteDictionaryAPI(RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
        mapper.registerModule(new JavaTimeModule());
    }

    public Optional<LocalDateTime> fetchUpdateTimestamp(String name) {
        return runRequest(new TypeReference<String>() {}, rootURL + name + "/last-updated").filter(StringUtils::hasLength)
            .map(iso -> LocalDateTime.parse(iso, formatter));
    }

    public Optional<Integer> fetchDatabaseVersion(String siteName) {
        return runRequest(new TypeReference<Integer>() {}, rootURL + siteName + "/database-version");
    }

    public Optional<List<ConceptNodeDump>> fetchConcepts(String siteName) {
        return runRequest(new TypeReference<List<ConceptNodeDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.ConceptNode.name());
    }

    public Optional<List<FacetCategoryDump>> fetchFacetCategories(String siteName) {
        return runRequest(new TypeReference<List<FacetCategoryDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.FacetCategory.name());
    }

    public Optional<List<FacetDump>> fetchFacets(String siteName) {
        return runRequest(new TypeReference<List<FacetDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.Facet.name());
    }

    public Optional<List<ConceptNodeMetaDump>> fetchConceptMetas(String siteName) {
        return runRequest(
            new TypeReference<List<ConceptNodeMetaDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.ConceptNodeMeta.name()
        );
    }

    public Optional<List<FacetCategoryMetaDump>> fetchFacetCategoryMetas(String siteName) {
        return runRequest(
            new TypeReference<List<FacetCategoryMetaDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.FacetCategoryMeta.name()
        );
    }

    public Optional<List<FacetMetaDump>> fetchFacetMetas(String siteName) {
        return runRequest(new TypeReference<List<FacetMetaDump>>() {}, rootURL + siteName + "/dump/" + DumpTable.FacetMeta.name());
    }

    public Optional<List<FacetConceptPair>> fetchFacetConceptPairs(String siteName) {
        return runRequest(
            new TypeReference<List<FacetConceptPair>>() {}, rootURL + siteName + "/dump/" + DumpTable.FacetConceptNode.name()
        );
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> runRequest(TypeReference<T> returnType, String url) {
        try {
            String entityStr = restClient.get().uri(url).retrieve().body(String.class);
            if (entityStr == null) {
                return Optional.empty();
            }
            if (returnType.getType().equals(entityStr.getClass())) {
                return Optional.of((T) entityStr);
            } else {
                return Optional.of(mapper.readValue(entityStr, returnType));
            }
        } catch (Exception e) {
            log.info("Exception running request:: ", e);
            return Optional.empty();
        }
    }
}
