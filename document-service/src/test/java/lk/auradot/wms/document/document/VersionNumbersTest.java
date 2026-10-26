package lk.auradot.wms.document.document;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VersionNumbersTest {

    static VersionRow v(int major, int minor) {
        return new VersionRow("v", "d", major, minor, "k", 1, "text/plain", "a".repeat(64), "f", "u", Instant.EPOCH, null);
    }

    @Test
    void startsAtOneAndCountsMinorOrMajorVersions() {
        assertThat(VersionNumbers.next(Optional.empty(), false)).containsExactly(1, 0);
        assertThat(VersionNumbers.next(Optional.empty(), true)).containsExactly(1, 0);
        assertThat(VersionNumbers.next(Optional.of(v(1, 3)), false)).containsExactly(1, 4);
        assertThat(VersionNumbers.next(Optional.of(v(1, 3)), true)).containsExactly(2, 0);
        assertThat(v(2, 11).label()).isEqualTo("2.11");
    }
}
