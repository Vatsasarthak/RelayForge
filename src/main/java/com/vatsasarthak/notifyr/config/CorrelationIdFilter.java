package com.vatsasarthak.notifyr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// Gives every request a correlation id (the caller's X-Correlation-Id if it is well-formed, else a new
// UUID), puts it in the logging MDC and echoes it in the response. The outbox copies it into AMQP
// headers so worker logs for the same request carry the same id.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || !VALID_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    // for message listeners: run with the correlation id carried by the message (or a new one)
    public static void putFromMessage(String correlationId) {
        MDC.put(MDC_KEY, correlationId != null && VALID_ID.matcher(correlationId).matches()
                ? correlationId : UUID.randomUUID().toString());
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
