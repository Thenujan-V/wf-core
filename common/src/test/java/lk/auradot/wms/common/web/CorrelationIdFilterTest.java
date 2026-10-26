package lk.auradot.wms.common.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsValidIncomingId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc12345-xyz");
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(seenInMdc.get()).isEqualTo("abc12345-xyz");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc12345-xyz");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void replacesUnsafeOrOversizedIds() {
        assertThat(CorrelationIdFilter.resolve("bad\r\nInjected: yes")).doesNotContain("Injected");
        assertThat(CorrelationIdFilter.resolve("a".repeat(65))).hasSize(36);
        assertThat(CorrelationIdFilter.resolve("short")).hasSize(36);
    }

    @Test
    void clearsMdcWhenChainThrows() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
