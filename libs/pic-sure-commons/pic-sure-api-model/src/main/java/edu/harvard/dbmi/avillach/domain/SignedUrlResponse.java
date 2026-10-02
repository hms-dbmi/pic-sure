package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A link a query result can be downloaded from")
public class SignedUrlResponse {

    @Schema(
        description = "A time-limited URL that serves the result without a token",
        example = "https://pic-sure-exports.s3.amazonaws.com/8694e3d4-5cb4-410f-8431-993445e6d3f6?X-Amz-Expires=3600"
    )
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
