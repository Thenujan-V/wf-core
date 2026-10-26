package lk.auradot.wms.document.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewsTest {

    @Test
    void onlyPdfImagesAndPlainTextOpenInTheBrowser() {
        assertThat(Previews.inline("application/pdf")).isTrue();
        assertThat(Previews.inline("IMAGE/PNG")).isTrue();
        assertThat(Previews.inline("text/plain; charset=utf-8")).isTrue();
        assertThat(Previews.inline("text/html")).isFalse();
        assertThat(Previews.inline("image/svg+xml")).isFalse();
        assertThat(Previews.inline("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isFalse();
    }
}
