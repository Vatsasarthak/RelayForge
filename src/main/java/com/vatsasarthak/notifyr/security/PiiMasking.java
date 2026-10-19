package com.vatsasarthak.notifyr.security;

// Masks personal data before it is written to logs: enough remains to correlate a log line with a record,
// but not to read the address or reuse a device token.
public final class PiiMasking {

    private PiiMasking() {
    }

    // jane.doe@example.com -> j***@example.com
    public static String email(String value) {
        if (value == null || value.isBlank()) {
            return String.valueOf(value);
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    // +15551234567 -> +1*******567
    public static String phone(String value) {
        if (value == null || value.isBlank()) {
            return String.valueOf(value);
        }
        if (value.length() <= 5) {
            return "***";
        }
        return value.substring(0, 2) + "*".repeat(value.length() - 5) + value.substring(value.length() - 3);
    }

    // device tokens are credentials for push delivery: keep only a short prefix and the length
    public static String token(String value) {
        if (value == null || value.isBlank()) {
            return String.valueOf(value);
        }
        return value.substring(0, Math.min(4, value.length())) + "…(" + value.length() + " chars)";
    }
}
