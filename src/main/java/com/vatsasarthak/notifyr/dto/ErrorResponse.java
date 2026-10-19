package com.vatsasarthak.notifyr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vatsasarthak.notifyr.config.CorrelationIdFilter;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

// Body of every error response. "error" is the human-readable message; "details" holds per-field
// validation errors. correlationId matches the X-Correlation-Id response header and the logs.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String error, String path, String correlationId,
                            Map<String, String> details) {

    public static ErrorResponse of(int status, String error, String path) {
        return of(status, error, path, null);
    }

    public static ErrorResponse of(int status, String error, String path, Map<String, String> details) {
        return new ErrorResponse(Instant.now(), status, error, path, MDC.get(CorrelationIdFilter.MDC_KEY), details);
    }
}
