package lk.auradot.wms.form.submission;

import java.time.Instant;
import java.util.Map;

public record Submission(String id, String formKey, int formVersion, Map<String, Object> data, String submittedBy,
                         Instant submittedAt, String contextRef) {
}
