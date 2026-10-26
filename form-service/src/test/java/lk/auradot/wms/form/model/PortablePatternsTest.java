package lk.auradot.wms.form.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PortablePatternsTest {

    @Test
    void matchesTheWholeValue() {
        assertThat(PortablePatterns.matches("ab|c", "abx")).isFalse();
        assertThat(PortablePatterns.matches("ab|c", "c")).isTrue();
    }

    @Test
    void aRunawayPatternGivesUpQuicklyInsteadOfTyingUpTheServer() {
        long start = System.nanoTime();

        assertThat(PortablePatterns.matches("(.*a){12}", "a".repeat(31) + "b")).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
}
