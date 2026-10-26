package lk.auradot.wms.expression;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs shared/expression/condition-vectors.json; the TypeScript port runs the same file. */
class ConditionVectorsTest {

    // Surefire runs with the module directory (backend/expression) as working directory.
    static final Path VECTORS = Path.of("../../shared/expression/condition-vectors.json");

    @SuppressWarnings("unchecked")
    static Map<String, Object> vectors() throws Exception {
        return JsonMapper.builder().build().readValue(Files.readString(VECTORS), Map.class);
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> evaluatesEveryCase() throws Exception {
        Map<String, Object> vectors = vectors();
        Map<String, Object> shared = (Map<String, Object>) vectors.get("variables");
        return ((List<Map<String, Object>>) vectors.get("cases")).stream().map(c -> DynamicTest.dynamicTest(
                (String) c.get("name"), () -> {
                    Map<String, Object> variables = c.containsKey("variables")
                            ? (Map<String, Object>) c.get("variables") : shared;
                    Condition condition = ConditionParser.parse(c.get("condition"));
                    assertThat(ConditionEvaluator.evaluate(condition, variables)).isEqualTo(c.get("expected"));
                }));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> rejectsEveryInvalidCondition() throws Exception {
        return ((List<Map<String, Object>>) vectors().get("invalid")).stream().map(c -> DynamicTest.dynamicTest(
                (String) c.get("name"),
                () -> assertThatThrownBy(() -> ConditionParser.parse(c.get("condition")))
                        .isInstanceOf(ConditionParseException.class)));
    }
}
