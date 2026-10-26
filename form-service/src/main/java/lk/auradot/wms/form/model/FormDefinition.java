package lk.auradot.wms.form.model;

import java.util.List;

public record FormDefinition(List<Section> sections, List<Field> fields) {

    public FormDefinition {
        sections = List.copyOf(sections);
        fields = List.copyOf(fields);
    }
}
