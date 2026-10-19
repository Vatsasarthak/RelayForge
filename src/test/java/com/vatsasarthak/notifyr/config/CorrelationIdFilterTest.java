package com.vatsasarthak.notifyr.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void wellFormedIncomingIdIsKeptInMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        assertThat(seenInMdc.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();   // cleaned up after the request
    }

    @Test
    void missingOrUnsafeIdIsReplacedWithAGeneratedOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "bad id\nwith newline");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .isNotEqualTo("bad id\nwith newline")
                .matches("[0-9a-f-]{36}");
    }
}
