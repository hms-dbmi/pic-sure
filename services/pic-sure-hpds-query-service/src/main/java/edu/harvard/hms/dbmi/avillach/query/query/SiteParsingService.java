package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Ported from the legacy WAR's {@code SiteParsingService} (only producer of the {@code commonAreaUUID} metadata that
 * {@link QueryService#queryMetadata} reads for the GIC common-area lookup). AIO may not exercise GIC; this preserves the
 * {@code ?isInstitute=true} {@code /query} contract.
 *
 * <p>DB-free adaptation: the legacy version resolved a domain to a site code via a local {@code SiteRepository} (JPA). This module owns no
 * database at all -- not even for this small institutional reference table -- so the lookup goes over HTTP via
 * {@link OperationsClient#findSitesByDomain(String)} instead, exactly like query persistence goes through {@code OperationsClient} rather
 * than a local {@code QueryRepository}.
 */
@Service
public class SiteParsingService {

    private static final Logger LOG = LoggerFactory.getLogger(SiteParsingService.class);
    private static final Pattern EMAIL = Pattern.compile("^([^@]+)(@)(.*)$");

    private final OperationsClient operationsClient;

    public SiteParsingService(OperationsClient operationsClient) {
        this.operationsClient = operationsClient;
    }

    public Optional<String> parseSiteOfOrigin(String email) {
        Matcher m = EMAIL.matcher(email == null ? "" : email);
        if (!m.find()) {
            LOG.warn("Unable to parse domain for email: {}", email);
            return Optional.empty();
        }
        List<String> matches = operationsClient.findSitesByDomain(m.group(3));
        if (matches == null || matches.isEmpty()) {
            LOG.warn("No site for domain: {}", m.group(3));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            LOG.warn("Multiple sites match email — should never happen: {}", email);
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }
}
