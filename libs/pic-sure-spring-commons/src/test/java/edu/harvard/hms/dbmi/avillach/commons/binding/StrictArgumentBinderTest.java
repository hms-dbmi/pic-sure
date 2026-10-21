package edu.harvard.hms.dbmi.avillach.commons.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class StrictArgumentBinderTest {

    enum Color {
        RED, GREEN
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(Leaf.class), @JsonSubTypes.Type(Branch.class)})
    interface Clause {}

    record Leaf(String path) implements Clause {}

    record Branch(List<Clause> children) implements Clause {}

    record Input(String name, Integer count, Color color, Clause clause) {}

    record Primitive(int count) {}

    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private final StrictArgumentBinder strict = new StrictArgumentBinder(Clause.class, true, Failure::new);
    private final StrictArgumentBinder lenientEnums = new StrictArgumentBinder(Clause.class, false, IllegalArgumentException::new);

    @Test
    void bindsValidArguments() {
        Input input = strict.bind(Map.of("name", "n", "count", 2, "color", "RED", "clause", Map.of("path", "/a")), Input.class);

        assertThat(input).isEqualTo(new Input("n", 2, Color.RED, new Leaf("/a")));
    }

    @Test
    void nullArgumentsBindToAnEmptyInput() {
        assertThat(strict.bind(null, Input.class)).isEqualTo(new Input(null, null, null, null));
    }

    @Test
    void anUnknownFieldIsNamed() {
        assertThatThrownBy(() -> strict.bind(Map.of("not", true), Input.class)).isInstanceOf(Failure.class)
            .hasMessage("Field 'not' is not part of this tool's input.");
    }

    @Test
    void anUnknownFieldNameIsStrippedOfControlCharactersAndCapped() {
        String name = "a\nb\u0000" + "x".repeat(StrictArgumentBinder.MAX_FIELD_NAME_LENGTH * 2);

        assertThatThrownBy(() -> strict.bind(Map.of(name, 1), Input.class)).isInstanceOf(Failure.class)
            .hasMessage("Field 'ab" + "x".repeat(StrictArgumentBinder.MAX_FIELD_NAME_LENGTH - 2) + "' is not part of this tool's input.");
    }

    @Test
    void aWrongTypeNamesTheField() {
        assertThatThrownBy(() -> strict.bind(Map.of("name", Map.of("a", 1)), Input.class)).isInstanceOf(Failure.class)
            .hasMessage("Field 'name' has the wrong type.");
    }

    @Test
    void aNullForAPrimitiveIsRefused() {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("count", null);

        assertThatThrownBy(() -> strict.bind(arguments, Primitive.class)).isInstanceOf(Failure.class);
    }

    @Test
    void aBadEnumValueListsTheAllowedValues() {
        assertThatThrownBy(() -> strict.bind(Map.of("color", "BLUE"), Input.class)).isInstanceOf(Failure.class)
            .hasMessage("Field 'color' must be one of RED, GREEN.");
    }

    @Test
    void anUnresolvableClauseGetsTheClauseShapeMessage() {
        assertThatThrownBy(() -> strict.bind(Map.of("clause", Map.of("neither", 1)), Input.class)).isInstanceOf(Failure.class)
            .hasMessageStartingWith("Each phenotypic clause must be a filter");
    }

    @Test
    void anEnumIndexIsRefusedWhenConfigured() {
        assertThatThrownBy(() -> strict.bind(Map.of("color", 1), Input.class)).isInstanceOf(Failure.class)
            .hasMessage("Field 'color' must be one of RED, GREEN.");
        assertThatThrownBy(() -> strict.bind(Map.of("color", "1"), Input.class)).isInstanceOf(Failure.class);
    }

    @Test
    void anEnumIndexIsAcceptedWhenNotConfigured() {
        assertThat(lenientEnums.bind(Map.of("color", 1), Input.class).color()).isEqualTo(Color.GREEN);
    }

    @Test
    void theFailureTypeComesFromTheSuppliedFactory() {
        assertThatThrownBy(() -> lenientEnums.bind(Map.of("not", true), Input.class)).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.bind(Map.of("not", true), Input.class)).isExactlyInstanceOf(Failure.class);
    }
}
