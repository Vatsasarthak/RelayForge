package com.vatsasarthak.notifyr.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vatsasarthak.notifyr.exception.GlobalExceptionHandler;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(
            new ErrorResponseWriter(JsonMapper.builder().findAndAddModules().build()), DataSize.ofBytes(10));

    @Test
    void declaredContentLengthOverTheLimitIsRejectedWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("exceeds the limit of 10 bytes");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyWithinTheLimitIsPassedOn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent("0123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    // chunked requests have no Content-Length: the body is counted while it is read
    @Test
    void bodyWithoutContentLengthFailsOnceItExceedsTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("01234567890123456789".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws java.io.IOException {
                req.getInputStream().readAllBytes();
            }
        })).isInstanceOf(RequestSizeLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void bodyTooLargeWhileParsingIsReportedAs413() {
        HttpMessageNotReadableException parseFailure = new HttpMessageNotReadableException("I/O error",
                new RequestSizeLimitFilter.BodyTooLargeException("Request body exceeds the limit of 10 bytes"),
                new MockHttpInputMessage(new byte[0]));

        var response = new GlobalExceptionHandler().handleUnreadableBody(parseFailure,
                new MockHttpServletRequest("POST", "/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error()).contains("exceeds the limit");
    }
}
