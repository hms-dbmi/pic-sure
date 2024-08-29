package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class SignedUrlResponse {

    private final String signedUrl;

    @JsonCreator
    public SignedUrlResponse(@JsonProperty("signedUrl") String signedUrl) {
        this.signedUrl = signedUrl;
    }

    public String getSignedUrl() {
        return signedUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SignedUrlResponse that = (SignedUrlResponse) o;
        return Objects.equals(signedUrl, that.signedUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signedUrl);
    }
}
