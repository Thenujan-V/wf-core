package lk.auradot.wms.workflow.instance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record StartRequest(@NotBlank String processKey, @Size(max = 255) String businessKey, Map<String, Object> data) {
}
