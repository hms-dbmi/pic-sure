package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a tool query into adapter code. {@link #walk} checks the query the same way the count tools do and reduces it to an
 * {@link AdapterQuery}; {@link #generate} hands that to the generator for the requested language. It makes no network call.
 */
@Component
public class AdapterCodeGenerator {

    private final AdapterSetup setup;

    private final Map<Language, LanguageGenerator> generators = new EnumMap<>(Language.class);

    /**
     * Creates the generator from every {@link LanguageGenerator} bean.
     *
     * @param setup the deployment's connection details and adapter versions
     * @param languageGenerators one generator per language
     * @throws IllegalStateException if two generators write the same language
     */
    public AdapterCodeGenerator(AdapterSetup setup, List<LanguageGenerator> languageGenerators) {
        this.setup = setup;
        for (LanguageGenerator generator : languageGenerators) {
            if (generators.put(generator.language(), generator) != null) {
                throw new IllegalStateException("Two code generators write " + generator.language().name() + ".");
            }
        }
    }

    /**
     * Notes about a query that the code cannot settle by itself: today, genomic filters on a site whose configuration does not declare
     * genomic support.
     *
     * @param query the intermediate form from {@link #walk}
     * @return the warnings, possibly empty
     */
    public List<String> warnings(AdapterQuery query) {
        if (!query.genomicFilters().isEmpty() && !setup.supportsGenomic()) {
            return List.of(
                "This site's configuration does not declare genomic support, so the genomic filters in this code may be refused when it runs."
            );
        }
        return List.of();
    }

    /**
     * Checks a query and reduces it to the intermediate form. Categorical values are sorted so the output does not depend on set order, and
     * {@code select} becomes the extra output columns except for a count, which has none.
     *
     * @param input the query as the tool received it
     * @param resultKind the result the code asks for
     * @return the intermediate form
     * @throws ToolFailure if the query is incomplete, has a filter or genomic filter the adapters cannot express, or is empty
     */
    public AdapterQuery walk(QueryInput input, ResultKind resultKind) {
        Query query = input.toQuery(resultKind.resultType());
        AdapterQuery.Clause clause = query.phenotypicClause() == null ? null : clause(query.phenotypicClause());
        List<AdapterQuery.Genomic> genomic =
            query.genomicFilters() == null ? List.of() : query.genomicFilters().stream().map(AdapterCodeGenerator::genomic).toList();
        List<String> include = query.select() == null ? List.of() : List.copyOf(new LinkedHashSet<>(query.select()));
        if (clause == null && genomic.isEmpty() && include.isEmpty()) {
            throw new ToolFailure(
                "Adapter code needs a query with at least one filter or genomic filter, or select paths for a participant or timestamp result."
            );
        }
        return new AdapterQuery(resultKind, clause, genomic, include);
    }

    /**
     * Writes code for a query in one language.
     *
     * @param query the intermediate form from {@link #walk}
     * @param language the language to write
     * @return the code and its setup
     * @throws ToolFailure if no generator for the language is registered
     */
    public GeneratedCode generate(AdapterQuery query, Language language) {
        LanguageGenerator generator = generators.get(language);
        if (generator == null) {
            throw new ToolFailure("Code in " + language.name() + " is not available on this server.");
        }
        return generator.generate(query, setup);
    }

    private static AdapterQuery.Clause clause(PhenotypicClause clause) {
        if (clause instanceof PhenotypicFilter filter) {
            List<String> categories =
                filter.values() == null ? List.of() : noNulls(filter.values()).stream().map(AdapterCodeGenerator::text).sorted().toList();
            AdapterQuery.Filter checked = new AdapterQuery.Filter(
                filter.conceptPath(), filter.phenotypicFilterType(), categories, finite(filter.min()), finite(filter.max())
            );
            return expressible(checked);
        }
        PhenotypicSubquery subquery = (PhenotypicSubquery) clause;
        return new AdapterQuery.Group(
            subquery.phenotypicClauses().stream().map(AdapterCodeGenerator::clause).toList(), subquery.operator()
        );
    }

    private static AdapterQuery.Genomic genomic(GenomicFilter filter) {
        if (filter.min() != null || filter.max() != null) {
            throw new ToolFailure("Genomic filter '" + filter.key() + "' has a min or max. The adapters take genomic values only.");
        }
        if (filter.values() == null || filter.values().isEmpty()) {
            throw new ToolFailure("Genomic filter '" + filter.key() + "' needs at least one value.");
        }
        return new AdapterQuery.Genomic(filter.key(), noNulls(filter.values()).stream().map(AdapterCodeGenerator::text).toList());
    }

    private static AdapterQuery.Filter expressible(AdapterQuery.Filter filter) {
        String path = filter.conceptPath();
        boolean categorical = !filter.categories().isEmpty();
        boolean numeric = filter.min() != null || filter.max() != null;
        if (filter.type() != PhenotypicFilterType.FILTER && (categorical || numeric)) {
            throw new ToolFailure(
                "Filter '" + path + "' has phenotypicFilterType " + filter.type().name()
                    + ", which takes no 'values', 'min', or 'max'. Use FILTER to match values or a range."
            );
        }
        if (filter.type() == PhenotypicFilterType.FILTER && categorical && numeric) {
            throw new ToolFailure("Filter '" + path + "' has both 'values' and 'min' or 'max'. A FILTER takes one or the other.");
        }
        if (filter.type() == PhenotypicFilterType.FILTER && !categorical && !numeric) {
            throw new ToolFailure(
                "Filter '" + path + "' is a FILTER with no 'values', 'min', or 'max'. Add them, or use REQUIRED to match any value."
            );
        }
        if (filter.categories().stream().anyMatch(String::isBlank)) {
            throw new ToolFailure("Filter '" + path + "' has a blank entry in 'values'.");
        }
        return filter;
    }

    private static String text(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new ToolFailure("Field 'values' must not contain a NUL character.");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new ToolFailure("Field 'values' must not contain an unpaired UTF-16 surrogate.");
            }
        }
        return value;
    }

    private static Double finite(Double bound) {
        if (bound != null && !Double.isFinite(bound)) {
            throw new ToolFailure("Filter bounds min and max must be finite numbers.");
        }
        return bound;
    }

    private static <T extends Collection<String>> T noNulls(T values) {
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new ToolFailure("Filter values must not be null.");
        }
        return values;
    }
}
