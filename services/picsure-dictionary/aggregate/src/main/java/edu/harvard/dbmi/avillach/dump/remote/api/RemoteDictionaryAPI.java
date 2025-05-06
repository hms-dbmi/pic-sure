package edu.harvard.dbmi.avillach.dump.remote.api;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import edu.harvard.dbmi.avillach.dump.entities.*;
import edu.harvard.dbmi.avillach.dump.local.DumpTable;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class RemoteDictionaryAPI {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final Logger log = LoggerFactory.getLogger(RemoteDictionaryAPI.class);
    private final CloseableHttpClient client;
    private final ObjectMapper mapper =
        new ObjectMapper().registerModule(new ParameterNamesModule()).setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    private static final String rootURL = "http://passthru:80/dictionary-dump/?/last-updated";

    @Autowired
    public RemoteDictionaryAPI(CloseableHttpClient client) {
        this.client = client;
        mapper.registerModule(new JavaTimeModule());
    }

    public Optional<LocalDateTime> fetchUpdateTimestamp(String name) {
        HttpGet request = new HttpGet(rootURL + name + "/last-updated");
        return runRequest(new TypeReference<String>() {}, request).map(iso -> LocalDateTime.parse(iso, formatter));
    }

    public Optional<List<ConceptNodeDump>> fetchConcepts(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.ConceptNode.name());
        return runRequest(new TypeReference<List<ConceptNodeDump>>() {}, request);
    }

    public Optional<List<FacetCategoryDump>> fetchFacetCategories(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.FacetCategory.name());
        return runRequest(new TypeReference<List<FacetCategoryDump>>() {}, request);
    }

    public Optional<List<FacetDump>> fetchFacets(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.FacetCategory.name());
        return runRequest(new TypeReference<List<FacetDump>>() {}, request);
    }

    public Optional<List<ConceptNodeMetaDump>> fetchConceptMetas(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.ConceptNode.name());
        return runRequest(new TypeReference<List<ConceptNodeMetaDump>>() {}, request);
    }

    public Optional<List<FacetCategoryMetaDump>> fetchFacetCategoryMetas(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.FacetCategory.name());
        return runRequest(new TypeReference<List<FacetCategoryMetaDump>>() {}, request);
    }

    public Optional<List<FacetMetaDump>> fetchFacetMetas(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.FacetCategory.name());
        return runRequest(new TypeReference<List<FacetMetaDump>>() {}, request);
    }

    public Optional<List<FacetConceptPair>> fetchFacetConceptPairs(String siteName) {
        HttpGet request = new HttpGet(rootURL + siteName + "/" + DumpTable.FacetCategory.name());
        return runRequest(new TypeReference<List<FacetConceptPair>>() {}, request);
    }


    private <T> Optional<T> runRequest(TypeReference<T> returnType, HttpGet request) {
        try (CloseableHttpResponse response = client.execute(request)) {
            String entityStr = EntityUtils.toString(response.getEntity());
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
