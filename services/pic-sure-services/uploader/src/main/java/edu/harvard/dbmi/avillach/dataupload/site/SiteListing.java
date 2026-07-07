package edu.harvard.dbmi.avillach.dataupload.site;

import java.util.List;

/**
 * A Site is an organization participating in GIC. Don't say institute!
 * These names should be all lower case abbreviations for the site:
 *     bch, cchmc, pitt, washu, chop, uthsc, etc
 * @param sites A list of all sites participating in data uploading
 * @param homeSite The site where this application is installed
 * @param homeSite The short display for the home site
 */
public record SiteListing(List<String> sites, String homeSite, String homeDisplay) {
}
