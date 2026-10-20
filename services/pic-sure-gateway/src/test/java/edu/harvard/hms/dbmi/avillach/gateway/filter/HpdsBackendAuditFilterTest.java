package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import jakarta.servlet.http.HttpServletResponse;

/** Covers {@link HpdsBackendAuditFilter}: the backend header becomes audit metadata and never reaches the client; other headers pass. */
class HpdsBackendAuditFilterTest {

    private final AuditContext audit = new AuditContext();
    private final HpdsBackendAuditFilter filter = new HpdsBackendAuditFilter(audit);

    @Test
    void anAddedBackendHeaderIsRecordedAndRemoved() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/hpds/auth/query/sync"), response, (req, res) -> {
            ((HttpServletResponse) res).addHeader("x-pic-sure-hpds-backend", "rust");
            ((HttpServletResponse) res).addHeader("queryMetadata", "q-1");
        });

        assertThat(audit.getMetadata()).containsEntry(HpdsBackendAuditFilter.METADATA_KEY, "rust");
        assertThat(response.getHeader(HpdsBackendAuditFilter.HEADER)).isNull();
        assertThat(response.getHeader("queryMetadata")).isEqualTo("q-1");
    }

    @Test
    void aSetBackendHeaderIsRecordedAndRemoved() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
            new MockHttpServletRequest("POST", "/hpds/auth/search"), response,
            (req, res) -> ((HttpServletResponse) res).setHeader(HpdsBackendAuditFilter.HEADER, "java-shadow")
        );

        assertThat(audit.getMetadata()).containsEntry(HpdsBackendAuditFilter.METADATA_KEY, "java-shadow");
        assertThat(response.getHeaderNames()).doesNotContain(HpdsBackendAuditFilter.HEADER);
    }

    @Test
    void noHeaderLeavesTheAuditUntouched() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/hpds/auth/query/x/status"), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(audit.getMetadata()).doesNotContainKey(HpdsBackendAuditFilter.METADATA_KEY);
    }
}
