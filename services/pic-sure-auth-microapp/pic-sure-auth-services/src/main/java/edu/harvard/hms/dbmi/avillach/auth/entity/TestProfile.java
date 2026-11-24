package edu.harvard.hms.dbmi.avillach.auth.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;

import java.util.Set;

/**
 * A named set of consent strings (e.g. {@code phs000007.c1}). At login, a user with an enabled {@link UserTestProfile} is granted these
 * consents in place of the ones from their identity provider.
 */
@Entity(name = "test_profile")
public class TestProfile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    @Convert(converter = ConsentSetJsonConverter.class)
    private Set<String> consents;

    public String getName() {
        return name;
    }

    public TestProfile setName(String name) {
        this.name = name;
        return this;
    }

    public Set<String> getConsents() {
        return consents;
    }

    public TestProfile setConsents(Set<String> consents) {
        this.consents = consents;
        return this;
    }

    static class ConsentSetJsonConverter implements AttributeConverter<Set<String>, String> {
        private static final ObjectMapper objectMapper = new ObjectMapper();
        private static final TypeReference<Set<String>> SET_OF_STRING = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(Set<String> consents) {
            try {
                return objectMapper.writeValueAsString(consents);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public Set<String> convertToEntityAttribute(String json) {
            try {
                return objectMapper.readValue(json, SET_OF_STRING);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }
}
