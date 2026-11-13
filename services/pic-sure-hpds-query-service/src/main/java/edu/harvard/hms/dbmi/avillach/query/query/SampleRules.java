package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;

/**
 * Decides whether a query that asks for a sample dataset may run. A query that does not set {@code sample} is never refused here.
 *
 * <p>A sample query is refused with 400 by the first rule it breaks, in this order: <ol> <li>sampling is turned off ({@code sample.enabled}
 * is false): error type {@code sample_disabled}</li> <li>the backend is not {@code auth}: error type {@code bad_request}</li> <li>the
 * result type is not one of the dataframe exports: error type {@code bad_request}</li> </ol>
 *
 * <p>A body that is not a typed {@link Query} is converted to one first. When that conversion fails, the body is refused with 400
 * {@code bad_request} if it carries a {@code sample} member holding anything other than literal {@code false}, so a body these rules cannot
 * read never passes as a query that is not a sample.
 */
@Component
public class SampleRules {

    static final String SAMPLE_DISABLED = "sample_disabled";
    static final String SAMPLE_DISABLED_MESSAGE = "Sample datasets are not available at this time";
    static final String WRONG_BACKEND_MESSAGE = "Sample datasets are only available for authorized access queries";
    static final String WRONG_RESULT_TYPE_MESSAGE =
        "Sample datasets are only available for the result types DATAFRAME, DATAFRAME_PFB and DATAFRAME_TIMESERIES";
    static final String UNREADABLE_SAMPLE_MESSAGE = "A query that sets sample must be a v3 query";

    private static final Set<ResultType> SAMPLED_RESULT_TYPES =
        EnumSet.of(ResultType.DATAFRAME, ResultType.DATAFRAME_PFB, ResultType.DATAFRAME_TIMESERIES);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SampleProperties properties;

    public SampleRules(SampleProperties properties) {
        this.properties = properties;
    }

    /**
     * Refuses a sample query that breaks one of the rules. Returns normally for a query that is not a sample, for an absent body, and for
     * an untyped body that cannot be read as a v3 query and carries no {@code sample} member or a literal {@code false} one.
     *
     * @param backend the ingress {@code {backend}} segment
     * @param queryBody the query of the request envelope: a v3 {@link Query}, or an untyped body shaped like one
     * @throws PicsureException 400 {@code sample_disabled} when sampling is turned off, 400 {@code bad_request} when the backend is not
     *         {@code auth}, the result type is not a dataframe export, or an untyped body asks for a sample and cannot be read as a v3
     *         query
     */
    public void check(String backend, Object queryBody) {
        Query query = asQuery(queryBody);
        if (query == null || !query.sample()) {
            return;
        }
        checkEnabled(query);
        if (!HpdsBackendSelector.AUTH.equals(backend)) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", WRONG_BACKEND_MESSAGE);
        }
        if (query.expectedResultType() == null || !SAMPLED_RESULT_TYPES.contains(query.expectedResultType())) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", WRONG_RESULT_TYPE_MESSAGE);
        }
    }

    /**
     * Refuses a sample query while sampling is turned off. This is the first rule of {@link #check}, on its own, for a caller that has to
     * apply it before the result type of the query is known. It reads nothing of the query but its {@code sample} flag.
     *
     * @param query a v3 query, or null
     * @throws PicsureException 400 {@code sample_disabled} when the query sets {@code sample} and sampling is turned off
     */
    public void checkEnabled(Query query) {
        if (query != null && query.sample() && !properties.isEnabled()) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, SAMPLE_DISABLED, SAMPLE_DISABLED_MESSAGE);
        }
    }

    /**
     * Returns the body as a v3 query, or null when it is absent or is not shaped like one and does not ask for a sample.
     *
     * @throws PicsureException 400 {@code bad_request} when the body is not shaped like a v3 query and asks for a sample
     */
    private static Query asQuery(Object queryBody) {
        if (queryBody == null || queryBody instanceof Query) {
            return (Query) queryBody;
        }
        try {
            return MAPPER.convertValue(queryBody, Query.class);
        } catch (IllegalArgumentException notAQuery) {
            if (asksForSample(queryBody)) {
                throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", UNREADABLE_SAMPLE_MESSAGE);
            }
            return null;
        }
    }

    /**
     * Returns whether a body that is not a v3 query carries a {@code sample} member holding anything other than literal {@code false}. A
     * body that cannot be read as JSON at all counts as asking, so it is refused instead of passed on.
     */
    private static boolean asksForSample(Object queryBody) {
        JsonNode tree;
        try {
            tree = MAPPER.valueToTree(queryBody);
        } catch (IllegalArgumentException unreadable) {
            return true;
        }
        JsonNode sample = tree.get("sample");
        return sample != null && !(sample.isBoolean() && !sample.booleanValue());
    }
}
