package lk.auradot.wms.form;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON columns, using the application's Jackson 3 mapper. */
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
}
