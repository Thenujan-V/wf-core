package lk.auradot.wms.form.model;

/** A problem with one field; {@code field} is a key or a path such as {@code lines[2].cost} (null: whole form). */
public record FieldError(String field, String code, String message) {
}
