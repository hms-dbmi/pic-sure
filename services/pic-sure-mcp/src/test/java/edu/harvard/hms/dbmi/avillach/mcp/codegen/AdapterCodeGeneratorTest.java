package edu.harvard.hms.dbmi.avillach.mcp.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.List;

/**
 * Covers the walk every generator shares: it refuses the filter shapes the adapters reject, naming the field, refuses values no generated
 * language can hold, warns about genomic filters on a site without genomic support, and registers each language exactly once.
 */
class AdapterCodeGeneratorTest {

    private static final AdapterCodeGenerator GENERATOR = CodegenCases.generator(CodegenCases.SETUP);

    private static String filter(String fields) {
        return "{\"phenotypicClause\":{\"conceptPath\":\"\\\\p\\\\x\\\\\"," + fields + "}}";
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {"REQUIRED|\"values\":[\"a\"]", "REQUIRED|\"min\":1", "ANY_RECORD_OF|\"max\":2",
            "ANY_RECORD_OF|\"values\":[\"a\"],\"min\":1"}
    )
    void requiredAndAnyRecordOfTakeNoValuesOrBounds(String type, String extra) {
        String json = filter("\"phenotypicFilterType\":\"" + type + "\"," + extra);

        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(json), ResultKind.count)).isInstanceOf(ToolFailure.class).hasMessage(
            "Filter '\\p\\x\\' has phenotypicFilterType " + type
                + ", which takes no 'values', 'min', or 'max'. Use FILTER to match values or a range."
        );
    }

    @Test
    void aFilterWithBothValuesAndBoundsIsRefused() {
        String json = filter("\"phenotypicFilterType\":\"FILTER\",\"values\":[\"a\"],\"max\":3");

        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(json), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Filter '\\p\\x\\' has both 'values' and 'min' or 'max'. A FILTER takes one or the other.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"phenotypicFilterType\":\"FILTER\"", "\"phenotypicFilterType\":\"FILTER\",\"values\":[]"})
    void aFilterWithNeitherValuesNorBoundsIsRefused(String fields) {
        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(filter(fields)), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Filter '\\p\\x\\' is a FILTER with no 'values', 'min', or 'max'. Add them, or use REQUIRED to match any value.");
    }

    @Test
    void aBlankCategoryIsRefused() {
        String json = filter("\"phenotypicFilterType\":\"FILTER\",\"values\":[\"a\",\" \"]");

        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(json), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Filter '\\p\\x\\' has a blank entry in 'values'.");
    }

    @Test
    void aNulOrAnUnpairedSurrogateInAValueIsRefused() {
        String nul = filter("\"phenotypicFilterType\":\"FILTER\",\"values\":[\"a\\u0000b\"]");
        String surrogate = filter("\"phenotypicFilterType\":\"FILTER\",\"values\":[\"a\\ud800b\"]");
        String genomic = "{\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"\\udc00\"]}]}";

        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(nul), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'values' must not contain a NUL character.");
        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(surrogate), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'values' must not contain an unpaired UTF-16 surrogate.");
        assertThatThrownBy(() -> GENERATOR.walk(CodegenCases.input(genomic), ResultKind.count)).isInstanceOf(ToolFailure.class)
            .hasMessage("Field 'values' must not contain an unpaired UTF-16 surrogate.");
    }

    @Test
    void aPairedSurrogateAndAFilterOfEachValidShapeAreAccepted() throws IOException {
        String json = """
            {"phenotypicClause":{"operator":"AND","phenotypicClauses":[
              {"phenotypicFilterType":"FILTER","conceptPath":"\\\\p\\\\a\\\\","values":["\\ud83d\\ude00"]},
              {"phenotypicFilterType":"FILTER","conceptPath":"\\\\p\\\\b\\\\","min":0},
              {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\p\\\\c\\\\"},
              {"phenotypicFilterType":"ANY_RECORD_OF","conceptPath":"\\\\p\\\\d\\\\"}]}}""";

        AdapterQuery query = GENERATOR.walk(CodegenCases.input(json), ResultKind.count);

        assertThat(query.conceptPaths()).containsExactly("\\p\\a\\", "\\p\\b\\", "\\p\\c\\", "\\p\\d\\");
    }

    @Test
    void genomicFiltersOnASiteWithoutGenomicSupportAreAWarning() throws IOException {
        String json = "{\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}]}";
        AdapterQuery query = GENERATOR.walk(CodegenCases.input(json), ResultKind.count);
        AdapterCodeGenerator genomicSite = CodegenCases.generator(CodegenCases.GENOMIC_SETUP);
        AdapterQuery noGenomic = GENERATOR.walk(CodegenCases.input(CodegenCases.named("flat_filter").query()), ResultKind.count);

        assertThat(GENERATOR.warnings(query)).containsExactly(
            "This site's configuration does not declare genomic support, so the genomic filters in this code may be refused when it runs."
        );
        assertThat(genomicSite.warnings(query)).isEmpty();
        assertThat(GENERATOR.warnings(noGenomic)).isEmpty();
    }

    @Test
    void eachLanguageIsRegisteredOnceAndAMissingOneIsAToolFailure() throws IOException {
        AdapterCodeGenerator pythonOnly = new AdapterCodeGenerator(CodegenCases.SETUP, List.of(new PythonGenerator()));
        AdapterQuery query = pythonOnly.walk(CodegenCases.input(CodegenCases.named("flat_filter").query()), ResultKind.count);

        assertThatThrownBy(() -> pythonOnly.generate(query, Language.bash)).isInstanceOf(ToolFailure.class)
            .hasMessage("Code in bash is not available on this server.");
        assertThatThrownBy(() -> new AdapterCodeGenerator(CodegenCases.SETUP, List.of(new RGenerator(), new RGenerator())))
            .isInstanceOf(IllegalStateException.class).hasMessage("Two code generators write r.");
        for (Language language : Language.values()) {
            assertThat(GENERATOR.generate(query, language).language()).isEqualTo(language);
        }
    }
}
