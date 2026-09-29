package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
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
     * Creates the generator with every language implemented so far.
     *
     * @param setup the deployment's connection details and adapter versions
     */
    public AdapterCodeGenerator(AdapterSetup setup) {
        this.setup = setup;
        LanguageGenerator python = new PythonGenerator();
        generators.put(python.language(), python);
    }

    /**
     * Checks a query and reduces it to the intermediate form. Categorical values are sorted so the output does not depend on set order, and
     * {@code select} becomes the extra output columns except for a count, which has none.
     *
     * @param input the query as the tool received it
     * @param resultKind the result the code asks for
     * @return the intermediate form
     * @throws ToolFailure if the query is incomplete, has a genomic filter the adapters cannot express, or is empty
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
     * @throws ToolFailure if no generator for the language exists yet
     */
    public GeneratedCode generate(AdapterQuery query, Language language) {
        LanguageGenerator generator = generators.get(language);
        if (generator == null) {
            throw new ToolFailure("Code in " + language.name() + " is not available yet. Use python.");
        }
        return generator.generate(query, setup);
    }

    private static AdapterQuery.Clause clause(PhenotypicClause clause) {
        if (clause instanceof PhenotypicFilter filter) {
            List<String> categories = filter.values() == null ? List.of() : noNulls(filter.values()).stream().sorted().toList();
            return new AdapterQuery.Filter(
                filter.conceptPath(), filter.phenotypicFilterType(), categories, finite(filter.min()), finite(filter.max())
            );
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
        return new AdapterQuery.Genomic(filter.key(), List.copyOf(noNulls(filter.values())));
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
