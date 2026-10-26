package lk.auradot.wms.workflow;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON columns and payloads, using the application's Jackson 3 mapper. */
@Component
public class Json {

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String text) {
        return text == null ? new LinkedHashMap<>() : mapper.readValue(text, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object value) {
        return mapper.convertValue(value, LinkedHashMap.class);
    }
}
