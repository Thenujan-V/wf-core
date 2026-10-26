package lk.auradot.wms.workflow;

import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the sample form definitions in src/test/resources/forms. */
public final class TestForms {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TestForms() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String name) {
        try (InputStream in = TestForms.class.getResourceAsStream("/forms/" + name)) {
            return MAPPER.readValue(in, LinkedHashMap.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
