package lk.auradot.wms.workflow;

import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the sample graphs in src/test/resources/processes. */
public final class TestProcesses {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TestProcesses() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String name) {
        try (InputStream in = TestProcesses.class.getResourceAsStream("/processes/" + name)) {
            return MAPPER.readValue(in, LinkedHashMap.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The webhook sample with its URL replaced. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> webhookTo(String url) {
        Map<String, Object> graph = load("webhook-then-review.json");
        for (Map<String, Object> node : (List<Map<String, Object>>) graph.get("nodes")) {
            if ("hook".equals(node.get("id"))) {
                node.put("config", Map.of("url", url));
            }
        }
        return graph;
    }
}
