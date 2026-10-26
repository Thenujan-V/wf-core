package lk.auradot.wms.workflow.instance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** {@code comment} is kept per task as form.comment_<taskId>; {@code data} is the task form's values (if it has one). */
public record CompleteRequest(@NotBlank String outcome, Map<String, Object> data, @Size(max = 2000) String comment) {
}
