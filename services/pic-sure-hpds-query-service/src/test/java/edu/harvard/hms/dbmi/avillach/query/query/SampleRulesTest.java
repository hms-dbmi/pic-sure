package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;

class SampleRulesTest {

    private static final String AUTH = "auth";
    private static final String OPEN = "open";

    private static SampleRules rules(boolean enabled) {
        SampleProperties props = new SampleProperties();
        props.setEnabled(enabled);
        return new SampleRules(props);
    }

    private static Query query(ResultType type, boolean sample) {
        return new Query(List.of(), List.of(), null, List.of(), type, null, null, sample);
    }

    private static void assertRefused(Runnable check, String errorType) {
        assertThatThrownBy(check::run).isInstanceOfSatisfying(PicsureException.class, refused -> {
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getErrorType()).isEqualTo(errorType);
        });
    }

    @Test
    void sampleWithFlagOffIs400SampleDisabled() {
        assertThatThrownBy(() -> rules(false).check(AUTH, query(ResultType.DATAFRAME, true)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getErrorType()).isEqualTo("sample_disabled");
                assertThat(refused.getMessage()).isEqualTo("Sample datasets are not available at this time");
            });
    }

    @Test
    void flagOffIsReportedBeforeBackendAndResultType() {
        assertRefused(() -> rules(false).check(OPEN, query(ResultType.COUNT, true)), "sample_disabled");
    }

    @ParameterizedTest
    @EnumSource(value = ResultType.class, mode = EnumSource.Mode.EXCLUDE, names = {"DATAFRAME", "DATAFRAME_PFB", "DATAFRAME_TIMESERIES"})
    void sampleOnEachNonExportResultTypeIs400(ResultType type) {
        assertThatThrownBy(() -> rules(true).check(AUTH, query(type, true))).isInstanceOfSatisfying(PicsureException.class, refused -> {
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getErrorType()).isEqualTo("bad_request");
            assertThat(refused.getMessage()).contains("DATAFRAME", "DATAFRAME_PFB", "DATAFRAME_TIMESERIES");
        });
    }

    @Test
    void sampleWithNoResultTypeIs400() {
        assertRefused(() -> rules(true).check(AUTH, query(null, true)), "bad_request");
    }

    @ParameterizedTest
    @EnumSource(value = ResultType.class, names = {"DATAFRAME", "DATAFRAME_PFB", "DATAFRAME_TIMESERIES"})
    void sampleOnEachDataframeTypePasses(ResultType type) {
        assertThatCode(() -> rules(true).check(AUTH, query(type, true))).doesNotThrowAnyException();
    }

    @Test
    void sampleOnOpenBackendIs400() {
        assertThatThrownBy(() -> rules(true).check(OPEN, query(ResultType.DATAFRAME, true)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getErrorType()).isEqualTo("bad_request");
                assertThat(refused.getMessage()).doesNotContain("DATAFRAME_PFB");
            });
    }

    @ParameterizedTest
    @EnumSource(ResultType.class)
    void nonSampleQueryIsNeverChecked(ResultType type) {
        assertThatCode(() -> rules(false).check(OPEN, query(type, false))).doesNotThrowAnyException();
        assertThatCode(() -> rules(true).check(OPEN, query(type, false))).doesNotThrowAnyException();
    }

    @Test
    void bodyThatIsNotAQueryIsNeverChecked() {
        assertThatCode(() -> rules(false).check(AUTH, null)).doesNotThrowAnyException();
        assertThatCode(() -> rules(false).check(AUTH, "q")).doesNotThrowAnyException();
    }

    @Test
    void untypedBodyCarryingSampleIsChecked() {
        Map<String, Object> untyped = Map.of("expectedResultType", "DATAFRAME", "sample", true);

        assertRefused(() -> rules(false).check(AUTH, untyped), "sample_disabled");
    }

    @Test
    void untypedBodyThatIsNotAQueryAndAsksForSampleIs400() {
        Map<String, Object> unreadable = Map.of("expectedResultType", "DATAFRAME", "sample", true, "notAQueryMember", 1);

        assertRefused(() -> rules(true).check(AUTH, unreadable), "bad_request");
        assertRefused(() -> rules(false).check(AUTH, unreadable), "bad_request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"true\"", "\"false\"", "1", "0", "null", "{}", "[]"})
    void untypedBodyThatIsNotAQueryIsRefusedForAnySampleValueButLiteralFalse(String sampleJson) throws Exception {
        Object unreadable = new ObjectMapper().readValue("{\"notAQueryMember\":1,\"sample\":" + sampleJson + "}", Object.class);

        assertRefused(() -> rules(true).check(AUTH, unreadable), "bad_request");
    }

    @Test
    void untypedTreeThatIsNotAQueryAndAsksForSampleIs400() throws Exception {
        Object tree = new ObjectMapper().readTree("{\"notAQueryMember\":1,\"sample\":true}");

        assertRefused(() -> rules(true).check(AUTH, tree), "bad_request");
    }

    @Test
    void untypedBodyThatCannotBeReadAsJsonIs400() {
        Object unserializable = new Object();

        assertRefused(() -> rules(true).check(AUTH, unserializable), "bad_request");
    }

    @Test
    void untypedBodyThatIsNotAQueryAndDoesNotAskForSamplePasses() throws Exception {
        Object sampleFalse = new ObjectMapper().readValue("{\"notAQueryMember\":1,\"sample\":false}", Object.class);
        Object sampleAbsent = Map.of("notAQueryMember", 1);

        assertThatCode(() -> rules(false).check(AUTH, sampleFalse)).doesNotThrowAnyException();
        assertThatCode(() -> rules(false).check(AUTH, sampleAbsent)).doesNotThrowAnyException();
    }
}
