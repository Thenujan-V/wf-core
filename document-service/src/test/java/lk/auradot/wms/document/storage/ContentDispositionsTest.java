package lk.auradot.wms.document.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentDispositionsTest {

    @Test
    void namesTheFileSafelyForOldAndNewBrowsers() {
        assertThat(ContentDispositions.of("report.pdf", true))
                .isEqualTo("inline; filename=\"report.pdf\"; filename*=UTF-8''report.pdf");
        assertThat(ContentDispositions.of("Relatório \"final\".pdf", false))
                .isEqualTo("attachment; filename=\"Relat_rio _final_.pdf\"; filename*=UTF-8''Relat%C3%B3rio%20%22final%22.pdf");
    }
}
