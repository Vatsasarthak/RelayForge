package com.vatsasarthak.notifyr.provider;

import com.vatsasarthak.notifyr.security.PiiMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Development-only stand-in for TwilioSmsProvider, enabled with notification.mock-provider=true.
// Nothing is delivered; notifications handled by it end in status SIMULATED, never SENT.
@Component
@ConditionalOnProperty(prefix = "notification", name = "mock-provider", havingValue = "true")
@Slf4j
public class MockSmsProvider implements SmsProvider {

    // optional artificial provider latency, e.g. for load tests
    @Value("${notification.mock-provider-delay-ms:0}")
    private long delayMs;

    @Override
    public void send(String phoneNumber, String message) throws InterruptedException {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new PermanentDeliveryException("Recipient phone number is missing");
        }
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        log.info("[MOCK] SMS NOT delivered (mock provider) to={} messageLength={}",
                PiiMasking.phone(phoneNumber), message != null ? message.length() : 0);
    }

    @Override
    public boolean isSimulated() {
        return true;
    }
}
