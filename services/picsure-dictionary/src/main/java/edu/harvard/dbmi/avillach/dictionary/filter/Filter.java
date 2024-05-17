package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;

import java.util.List;

public record Filter(List<Facet> facets, String search) {
}
