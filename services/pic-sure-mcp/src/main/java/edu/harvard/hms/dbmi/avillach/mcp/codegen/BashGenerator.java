package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicSubquery;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes a bash script that does what an adapter would with plain REST calls through {@code curl} and {@code jq}. The v3 query is
 * serialized with Jackson inside the {@code {"query": ...}} envelope and written into the script as a quoted heredoc, so the shell expands
 * nothing in it. The token comes from {@code PICSURE_TOKEN} and reaches {@code curl} through a header file from a process substitution, so
 * it never appears in the process list. A count or cross count makes one synchronous request and prints only the counts. A participant or
 * timestamp result is submitted, polled with a bounded backoff, and downloaded to {@code picsure_results/}, and only its line count and
 * path are printed.
 *
 * <p>This class writes the authorized channel's paths as text for the user's script. It holds no HTTP client and cannot make a request.
 */
@Component
public final class BashGenerator implements LanguageGenerator {

    /** The tools the script calls. */
    public static final String PACKAGE = "curl and jq";

    /** The shell the script runs in. */
    public static final String RUNTIME = "bash";

    /** Prints the versions of both tools, or fails when either is missing. */
    public static final String CHECK = "curl --version | head -n 1 && jq --version";

    /** How to install the tools; there is no connector package to install. */
    public static final String INSTALL =
        "Install curl and jq with the system package manager, for example: brew install curl jq (macOS) or sudo apt-get install curl jq (Debian, Ubuntu)";

    /** The directory participant and timestamp results are written to. */
    public static final String RESULTS_DIRECTORY = "picsure_results";

    /** The line that ends the query heredoc. It cannot appear as a line of the JSON, which is checked before the script is returned. */
    static final String HEREDOC_DELIMITER = "PICSURE_QUERY_JSON";

    /** The authorized query path under the site's public URL, as the user's script calls it. */
    static final String QUERY_PATH = "/picsure/hpds/auth/query";

    /** Most status checks before the script gives up: waits of 2, 4, 8, and 16 seconds, then 30 seconds each, about ten minutes. */
    static final int MAX_STATUS_CHECKS = 25;

    private static final String INDENT = "  ";

    private static final ObjectWriter JSON = jsonWriter();

    @Override
    public Language language() {
        return Language.bash;
    }

    @Override
    public GeneratedCode generate(AdapterQuery query, AdapterSetup setup) {
        List<String> lines = new ArrayList<>();
        lines.add("#!/usr/bin/env bash");
        lines.add("set -euo pipefail");
        lines.add("");
        lines.add("if [[ -z \"${PICSURE_TOKEN:-}\" ]]; then");
        lines.add(INDENT + "echo \"Set PICSURE_TOKEN to your PIC-SURE token before running this script.\" >&2");
        lines.add(INDENT + "exit 1");
        lines.add("fi");
        lines.add("");
        lines.add("query_url=" + quote(stripTrailingSlashes(setup.baseUrl()) + QUERY_PATH));
        lines.add("query_file=\"$(mktemp)\"");
        lines.add("trap 'rm -f \"$query_file\"' EXIT");
        lines.add("");
        lines.add("cat > \"$query_file\" <<'" + HEREDOC_DELIMITER + "'");
        lines.addAll(json(query));
        lines.add(HEREDOC_DELIMITER);
        lines.add("");
        lines.add("picsure_post() {");
        lines.add(INDENT + "curl --silent --show-error --fail \\");
        lines.add(INDENT + INDENT + "-H @<(printf 'Authorization: Bearer %s\\n' \"$PICSURE_TOKEN\") \\");
        lines.add(INDENT + INDENT + "-H 'Content-Type: application/json' \\");
        lines.add(INDENT + INDENT + "--data-binary @\"$query_file\" \\");
        lines.add(INDENT + INDENT + "\"$@\"");
        lines.add("}");
        lines.add("");
        lines.addAll(run(query.resultKind()));
        String code = String.join("\n", lines) + "\n";
        return new GeneratedCode(Language.bash, PACKAGE, null, RUNTIME, CHECK, INSTALL, code);
    }

    private static List<String> run(ResultKind kind) {
        return switch (kind) {
            case count -> List.of(
                "picsure_post \"${query_url}/sync\" | jq -r 'if type == \"number\" then . else error(\"PIC-SURE did not return a count\") end'"
            );
            case cross_count -> List.of("picsure_post \"${query_url}/sync\" | jq -r 'to_entries[] | \"\\(.key) \\(.value)\"'");
            case participant, timestamp -> rows(kind);
        };
    }

    private static List<String> rows(ResultKind kind) {
        List<String> lines = new ArrayList<>();
        lines.add("query_id=\"$(picsure_post \"$query_url\" | jq -r '.picsureResultId // empty')\"");
        lines.add("if [[ ! \"$query_id\" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then");
        lines.add(INDENT + "echo \"PIC-SURE did not return a query id.\" >&2");
        lines.add(INDENT + "exit 1");
        lines.add("fi");
        lines.add("");
        lines.add("query_status() {");
        lines
            .add(INDENT + "picsure_post \"${query_url}/${query_id}/status\" | jq -r '(.status // .resourceStatus // \"\") | ascii_upcase'");
        lines.add("}");
        lines.add("");
        lines.add("max_checks=" + MAX_STATUS_CHECKS);
        lines.add("checks=1");
        lines.add("delay=2");
        lines.add("status=\"$(query_status)\"");
        lines.add("while [[ \"$status\" != \"AVAILABLE\" ]]; do");
        lines.add(INDENT + "if [[ \"$status\" == \"ERROR\" ]]; then");
        lines.add(INDENT + INDENT + "echo \"PIC-SURE reported an error for query ${query_id}.\" >&2");
        lines.add(INDENT + INDENT + "exit 1");
        lines.add(INDENT + "fi");
        lines.add(INDENT + "if (( checks >= max_checks )); then");
        lines.add(INDENT + INDENT + "echo \"Query ${query_id} did not finish after ${max_checks} status checks.\" >&2");
        lines.add(INDENT + INDENT + "exit 1");
        lines.add(INDENT + "fi");
        lines.add(INDENT + "sleep \"$delay\"");
        lines.add(INDENT + "delay=$(( delay * 2 > 30 ? 30 : delay * 2 ))");
        lines.add(INDENT + "checks=$(( checks + 1 ))");
        lines.add(INDENT + "status=\"$(query_status)\"");
        lines.add("done");
        lines.add("");
        lines.add("mkdir -p " + RESULTS_DIRECTORY);
        lines.add("output_path=" + quote(RESULTS_DIRECTORY + "/" + kind.name() + ".csv"));
        lines.add("picsure_post \"${query_url}/${query_id}/result\" --output \"$output_path\"");
        lines.add("line_count=\"$(wc -l < \"$output_path\" | tr -d ' ')\"");
        lines.add("printf 'Saved %s lines to %s\\n' \"$line_count\" \"$output_path\"");
        return lines;
    }

    /**
     * Serializes the request envelope for a query as indented, ASCII-only JSON lines. The query is the v3 record the tool would send, with
     * categorical values in sorted order so the output is deterministic, and empty fields left out.
     *
     * @param query the intermediate form
     * @return the JSON, one entry per line
     * @throws IllegalStateException if the JSON cannot be written, or if a line equals the heredoc delimiter
     */
    static List<String> json(AdapterQuery query) {
        String text;
        try {
            text = JSON.writeValueAsString(Map.of("query", toQuery(query)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The query could not be written as JSON.", e);
        }
        List<String> lines = text.lines().toList();
        if (lines.contains(HEREDOC_DELIMITER)) {
            throw new IllegalStateException("The query JSON holds a line equal to the heredoc delimiter.");
        }
        return lines;
    }

    private static Query toQuery(AdapterQuery query) {
        PhenotypicClause clause = query.phenotypicClause() == null ? null : clause(query.phenotypicClause());
        List<GenomicFilter> genomic =
            query.genomicFilters().stream().map(filter -> new GenomicFilter(filter.key(), filter.values(), null, null)).toList();
        return new Query(query.includeConcepts(), List.of(), clause, genomic, query.resultKind().resultType(), null, null);
    }

    private static PhenotypicClause clause(AdapterQuery.Clause clause) {
        if (clause instanceof AdapterQuery.Filter filter) {
            return new PhenotypicFilter(
                filter.type(), filter.conceptPath(), new LinkedHashSet<>(filter.categories()), filter.min(), filter.max(), null
            );
        }
        AdapterQuery.Group group = (AdapterQuery.Group) clause;
        return new PhenotypicSubquery(null, group.clauses().stream().map(BashGenerator::clause).toList(), group.operator());
    }

    private static String stripTrailingSlashes(String url) {
        String stripped = url;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    /**
     * Quotes a string for bash. Printable ASCII goes in single quotes, with a single quote written as {@code '\''}; any other character is
     * written in an ANSI-C quoted segment as {@code $'\\xNN'} per UTF-8 byte, so the script stays ASCII.
     *
     * @param value the string
     * @return the quoted word
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("'");
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c == '\'') {
                out.append("'\\''");
            } else if (c >= 0x20 && c < 0x7f) {
                out.append((char) c);
            } else {
                out.append("'$'").append(String.format("\\x%02x", c)).append("''");
            }
        }
        return out.append("'").toString();
    }

    private static ObjectWriter jsonWriter() {
        JsonFactory factory = JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        factory.setCharacterEscapes(new AsciiEscapes());
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
        DefaultIndenter indenter = new DefaultIndenter(INDENT, "\n");
        return mapper.writer(new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter));
    }

    /** Jackson's standard escapes, plus DEL, so the JSON holds printable ASCII only. */
    private static final class AsciiEscapes extends CharacterEscapes {

        private final int[] escapes;

        AsciiEscapes() {
            escapes = CharacterEscapes.standardAsciiEscapesForJSON();
            escapes[0x7f] = CharacterEscapes.ESCAPE_STANDARD;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return escapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return null;
        }
    }
}
