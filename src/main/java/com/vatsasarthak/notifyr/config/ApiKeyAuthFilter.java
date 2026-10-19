package com.vatsasarthak.notifyr.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter errorResponseWriter;

    // no default on purpose: a missing API_KEY must stop startup instead of falling back to a well-known key
    @Value("${notification.api-key}")
    private String configuredApiKey;
    private static final String API_KEY_HEADER = "X-API-KEY";

    @PostConstruct
    void validateConfiguredKey() {
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            throw new IllegalStateException("API_KEY (notification.api-key) must be set");
        }
        if (configuredApiKey.startsWith("change-me")) {
            log.warn("API_KEY still has the placeholder value from .env.example - replace it before any real use");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response
            , FilterChain filterChain) throws IOException, ServletException {

        String path = request.getRequestURI();

        if(path.startsWith("/actuator") ||
                path.startsWith("/swagger") ||
                path.startsWith("/v3/api-docs") ||
                path.equals("/auth") ||
                path.startsWith("/auth/")
        ){
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);

        if(providedKey == null || !constantTimeEquals(providedKey, configuredApiKey)){
            errorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized: Invalid or missing API key");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
