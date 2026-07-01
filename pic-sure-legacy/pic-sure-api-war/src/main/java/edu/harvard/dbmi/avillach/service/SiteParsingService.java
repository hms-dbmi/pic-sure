package edu.harvard.dbmi.avillach.service;

import edu.harvard.dbmi.avillach.data.entity.Site;
import edu.harvard.dbmi.avillach.data.repository.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiteParsingService {

    private static final Logger LOG = LoggerFactory.getLogger(SiteParsingService.class);

    private static final Pattern emailRegex = Pattern.compile("^([^@]+)(@)(.*)$");

    @Inject
    SiteRepository repository;

    public Optional<String> parseSiteOfOrigin(String email) {
        Matcher matcher = emailRegex.matcher(email);
        if (!matcher.find()) {
            LOG.warn("Unable to parse domain for email: {}", email);
            return Optional.empty();
        }

        List<Site> matchingDomains = repository.getByColumn("domain", matcher.group(3));
        if (matchingDomains.isEmpty()) {
            LOG.warn("Unable to match domain for email: {}, looked for domain: {}", email, matcher.group(3));
            return Optional.empty();
        }
        if (matchingDomains.size() > 1) {
            LOG.warn("Multiple domains match email. This should never happen! Email: {}", email);
            return Optional.empty();
        }
        return Optional.of(matchingDomains.get(0).getCode());
    }
}
