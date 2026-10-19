package com.vatsasarthak.notifyr.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingTest {

    @Test
    void emailKeepsFirstCharacterAndDomainOnly() {
        assertThat(PiiMasking.email("jane.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(PiiMasking.email("not-an-email")).isEqualTo("***");
    }

    @Test
    void phoneKeepsCountryPrefixAndLastDigits() {
        assertThat(PiiMasking.phone("+15551234567")).isEqualTo("+1*******567");
        assertThat(PiiMasking.phone("123")).isEqualTo("***");
    }

    @Test
    void deviceTokenKeepsOnlyAShortPrefixAndLength() {
        assertThat(PiiMasking.token("abcdef0123456789")).isEqualTo("abcd…(16 chars)").doesNotContain("0123");
    }

    @Test
    void missingValuesAreLoggedAsSuch() {
        assertThat(PiiMasking.email(null)).isEqualTo("null");
        assertThat(PiiMasking.phone("")).isEqualTo("");
        assertThat(PiiMasking.token(null)).isEqualTo("null");
    }
}
