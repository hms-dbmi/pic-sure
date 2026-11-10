package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body of the open access query, {@code POST /hpds/open/query/sync}: an {@link OpenQuery} under the {@code query} key. As with the
 * authorized request, members of the envelope other than {@code query} are ignored, while the query itself is read strictly, so a member
 * outside {@link OpenQuery} or its clauses answers 400 instead of running as a query with fewer filters than the caller wrote.
 *
 * @param query the open access query, or null when the body carries none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "An open access query under the query key. Members other than query are ignored.")
public record OpenQueryRequest(
    @JsonDeserialize(using = OpenQueryRequest.StrictOpenQueryDeserializer.class) @Schema(
        description = "The open access query.", requiredMode = Schema.RequiredMode.REQUIRED
    ) OpenQuery query
) {

    /**
     * Reads an {@link OpenQuery} with a reader that fails on unknown members, derived from the mapper binding the request so its other
     * settings still apply.
     */
    static final class StrictOpenQueryDeserializer extends JsonDeserializer<OpenQuery> {

        private static final ObjectMapper FALLBACK = JsonMapper.builder().build();

        @Override
        public OpenQuery deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            ObjectMapper mapper = parser.getCodec() instanceof ObjectMapper codec ? codec : FALLBACK;
            return mapper.readerFor(OpenQuery.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(parser);
        }
    }
}
