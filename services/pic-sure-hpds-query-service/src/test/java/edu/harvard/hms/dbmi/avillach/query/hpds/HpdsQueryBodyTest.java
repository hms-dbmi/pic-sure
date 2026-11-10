package edu.harvard.hms.dbmi.avillach.query.hpds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

class HpdsQueryBodyTest {

    private static final UUID CLIENT_ID = UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6");
    private static final UUID SERVER_ID = UUID.fromString("5f0b6c1e-1d1f-4f6e-8a51-3f3a52b7c001");
    private static final UUID QUERY_ID = UUID.fromString("0b0e2f0c-6a55-4f0e-9a3c-2f4a3a1d9c11");

    private static Query typed(UUID picsureId) {
        return new Query(
            List.of("\\demographics\\AGE\\"), List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1"))), null, List.of(),
            ResultType.DATAFRAME, picsureId, QUERY_ID, true
        );
    }

    private static Map<String, Object> untyped(Object picsureId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("expectedResultType", "COUNT");
        query.put("picsureId", picsureId);
        query.put("id", QUERY_ID.toString());
        return query;
    }

    @Test
    void typedQueryLosesTheClientPicsureIdAndKeepsEverythingElse() {
        assertThat(HpdsQueryBody.of(typed(CLIENT_ID), null)).isEqualTo(typed(null));
    }

    @Test
    void typedQueryWithoutAPicsureIdIsSentAsItIs() {
        assertThat(HpdsQueryBody.of(typed(null), null)).isEqualTo(typed(null));
    }

    @Test
    void typedQueryCarriesTheServerResultIdInPlaceOfTheClientPicsureId() {
        assertThat(HpdsQueryBody.of(typed(CLIENT_ID), SERVER_ID)).isEqualTo(typed(SERVER_ID));
    }

    @Test
    void untypedQueryLosesTheClientPicsureIdAndKeepsEverythingElse() {
        Map<String, Object> inbound = untyped(CLIENT_ID.toString());

        assertThat(HpdsQueryBody.of(inbound, null)).isEqualTo(untyped(null));
        assertThat(inbound).containsEntry("picsureId", CLIENT_ID.toString());
    }

    @Test
    void untypedQueryCarriesTheServerResultIdInPlaceOfTheClientPicsureId() {
        assertThat(HpdsQueryBody.of(untyped(CLIENT_ID.toString()), SERVER_ID)).isEqualTo(untyped(SERVER_ID));
    }

    @Test
    void searchTermAndAbsentQueryAreSentAsTheyAre() {
        assertThat(HpdsQueryBody.of("\\_studies_consents\\", null)).isEqualTo("\\_studies_consents\\");
        assertThat(HpdsQueryBody.of(null, null)).isNull();
    }
}
