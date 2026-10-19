package com.vatsasarthak.notifyr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

// Rejects request bodies larger than notification.http.max-request-body-size with 413, before authentication
// and before Jackson buffers them (the public /auth endpoints would otherwise read a body of any size).
// A declared Content-Length is checked up front; a body without one (chunked) is counted while it is read,
// and GlobalExceptionHandler turns the resulting BodyTooLargeException into 413 as well.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // right after CorrelationIdFilter, so the error carries the correlation id
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter errorResponseWriter;
    private final long maxBytes;

    public RequestSizeLimitFilter(ErrorResponseWriter errorResponseWriter,
                                  @Value("${notification.http.max-request-body-size:1MB}") DataSize maxSize) {
        this.errorResponseWriter = errorResponseWriter;
        this.maxBytes = maxSize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            errorResponseWriter.write(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, limitMessage());
            return;
        }
        filterChain.doFilter(declaredLength < 0 ? new LimitedRequest(request) : request, response);
    }

    private String limitMessage() {
        return "Request body exceeds the limit of " + maxBytes + " bytes";
    }

    public static class BodyTooLargeException extends IOException {
        BodyTooLargeException(String message) {
            super(message);
        }
    }

    private final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream inputStream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedInputStream(super.getInputStream());
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long bytesRead;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int read) throws BodyTooLargeException {
            bytesRead += read;
            if (bytesRead > maxBytes) {
                throw new BodyTooLargeException(limitMessage());
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
