package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@ConditionalOnProperty("dictionary.host")
public class DictionaryService {

    public static final ParameterizedTypeReference<List<Concept>> CONCEPT_LIST_TYPE_REFERENCE = new ParameterizedTypeReference<>() {
    };
    private final String dictionaryHost;
    private final RestTemplate restTemplate;

    @Autowired
    public DictionaryService(@Value("${dictionary.host}") String dictionaryHostTemplate, @Value("${TARGET_STACK:}") String targetStack) {
        if (targetStack != null && !targetStack.isEmpty()) {
            this.dictionaryHost = dictionaryHostTemplate.replace("___TARGET_STACK___", targetStack);
        } else {
            this.dictionaryHost = dictionaryHostTemplate;
        }
        this.restTemplate = new RestTemplate();
    }

    public List<Concept> getConcepts(List<String> conceptPaths) {
        return restTemplate.exchange(dictionaryHost + "/pic-sure-api-2/PICSURE/proxy/dictionary-api/concepts/detail", HttpMethod.POST, new HttpEntity<>(conceptPaths), CONCEPT_LIST_TYPE_REFERENCE).getBody();
    }
}
