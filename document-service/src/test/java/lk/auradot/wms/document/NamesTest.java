package lk.auradot.wms.document;

import lk.auradot.wms.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamesTest {

    @Test
    void trimsAndAcceptsOrdinaryNames() {
        assertThat(Names.check("  Invoices 2026 ")).isEqualTo("Invoices 2026");
        assertThat(Names.check("Relatório final.pdf")).isEqualTo("Relatório final.pdf");
    }

    @Test
    void refusesEmptyLongOrPathLikeNames() {
        for (String bad : new String[] {"", "   ", "a/b", "a\\b", "tab\there", ".", "..", "x".repeat(256)}) {
            assertThatThrownBy(() -> Names.check(bad)).as(bad).isInstanceOf(ApiException.class)
                    .hasMessageContaining("name");
        }
    }
}
