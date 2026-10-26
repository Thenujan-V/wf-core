package lk.auradot.wms.document.written;

import lk.auradot.wms.document.document.DocumentKind;
import lk.auradot.wms.document.document.DocumentRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WrittenStateTest {

    private static DocumentRow row(DocumentKind kind, String currentVersionId, String linkedInstanceId) {
        Instant now = Instant.parse("2026-10-06T08:00:00Z");
        return new DocumentRow("d1", "f1", "Policy", null, Map.of(), currentVersionId, null, null, linkedInstanceId,
                "bob", now, now, kind);
    }

    @Test
    void aFileHasNoState() {
        assertThat(WrittenState.of(row(DocumentKind.FILE, "v1", null), false)).isNull();
    }

    @Test
    void theStateFollowsTheDraftTheApprovedVersionAndTheRequest() {
        assertThat(WrittenState.of(row(DocumentKind.WRITTEN, null, null), true)).isEqualTo(WrittenState.DRAFT);
        assertThat(WrittenState.of(row(DocumentKind.WRITTEN, null, "i1"), true)).isEqualTo(WrittenState.IN_APPROVAL);
        assertThat(WrittenState.of(row(DocumentKind.WRITTEN, "v1", null), false)).isEqualTo(WrittenState.APPROVED);
        assertThat(WrittenState.of(row(DocumentKind.WRITTEN, "v1", null), true)).isEqualTo(WrittenState.REVISION);
        assertThat(WrittenState.of(row(DocumentKind.WRITTEN, "v1", "i2"), true)).isEqualTo(WrittenState.IN_APPROVAL);
    }
}
