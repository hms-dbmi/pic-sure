package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.hms.dbmi.avillach.data.entity.Site;
import edu.harvard.hms.dbmi.avillach.data.repository.SiteRepository;

/**
 * The internal site-lookup API: gated the same way as {@link InternalQueryController} -- {@code /internal/**} passes
 * {@code WebSecurityConfig}'s {@code anyRequest().permitAll()} unauthenticated, and it is {@link InternalTokenFilter} that actually
 * enforces {@code X-PIC-SURE-INTERNAL-TOKEN} on every request here.
 *
 * <p>{@code GET /internal/sites/by-domain/{domain}} is a FIXED external contract: the hpds-query-service's (DB-free)
 * {@code SiteParsingService} calls {@code OperationsClient.findSitesByDomain(domain)}, which does {@code GET
 * {base}/internal/sites/by-domain/{domain}} expecting exactly a JSON {@code List<String>} of the matching {@link Site}'s {@code code} --
 * never a single object. {@code domain} carries a UNIQUE constraint (see {@code Site}'s {@code @Table}), so the result is always 0 or 1
 * elements: {@code []} when no site has that domain, {@code ["<code>"]} when one does.
 */
@RestController
@RequestMapping("/internal/sites")
public class InternalSiteController {

    private final SiteRepository siteRepository;

    public InternalSiteController(SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    @GetMapping("/by-domain/{domain}")
    public List<String> byDomain(@PathVariable("domain") String domain) {
        return siteRepository.findByDomain(domain).map(Site::getCode).map(List::of).orElseGet(List::of);
    }
}
