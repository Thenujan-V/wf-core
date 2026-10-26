package lk.auradot.wms.form.model;

import java.util.Arrays;
import java.util.Optional;

public enum FieldType {
    TEXT("text"), TEXTAREA("textarea"), NUMBER("number"), CURRENCY("currency"), DATE("date"), DATETIME("datetime"),
    SELECT("select"), RADIO("radio"), CHECKBOX("checkbox"), USER("user"), FILE("file"), TABLE("table"),
    HEADING("heading"), DISPLAY("display");

    private final String json;

    FieldType(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    public static Optional<FieldType> fromJson(String name) {
        return Arrays.stream(values()).filter(type -> type.json.equals(name)).findFirst();
    }

    /** Headings and display text only show their label; they never hold a value. */
    public boolean holdsValue() {
        return this != HEADING && this != DISPLAY;
    }

    public boolean hasOptions() {
        return this == SELECT || this == RADIO || this == CHECKBOX;
    }

    /** Types a repeating table may use for its columns. */
    public boolean allowedInTable() {
        return this == TEXT || this == NUMBER || this == CURRENCY || this == DATE || this == SELECT;
    }

    public boolean isNumeric() {
        return this == NUMBER || this == CURRENCY;
    }
}
