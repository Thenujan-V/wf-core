package lk.auradot.wms.form.model;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs shared/forms/validation-vectors.json; the Angular form renderer runs the same file. */
class ValidationVectorsTest {

    // Surefire runs with the module directory (backend/form-service) as working directory.
    static final Path VECTORS = Path.of("../../shared/forms/validation-vectors.json");

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> everyCaseMatches() throws Exception {
        Map<String, Object> vectors = JsonMapper.builder().build().readValue(Files.readString(VECTORS), Map.class);
        FormJson.Result parsed = FormJson.read(vectors.get("definition"));
        assertThat(parsed.errors()).isEmpty();
        assertThat(DefinitionChecks.check(parsed.definition())).isEmpty();

        return ((List<Map<String, Object>>) vectors.get("cases")).stream().map(c -> DynamicTest.dynamicTest(
                (String) c.get("name"), () -> {
                    Map<String, Access> access = new LinkedHashMap<>();
                    ((Map<String, String>) c.getOrDefault("access", Map.of()))
                            .forEach((key, value) -> access.put(key, Access.valueOf(value)));
                    SubmissionValidator.Result result = SubmissionValidator.validate(parsed.definition(),
                            (Map<String, Object>) c.get("data"), access, (Map<String, Object>) c.getOrDefault("baseline", Map.of()));

                    assertThat(result.errors().stream().map(e -> e.field() + ":" + e.code()).toList())
                            .containsExactlyInAnyOrderElementsOf((List<String>) c.get("errors"));
                    if (c.containsKey("clean")) {
                        assertThat(result.data()).isEqualTo(c.get("clean"));
                    }
                }));
    }
}
