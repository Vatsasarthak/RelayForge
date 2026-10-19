package com.vatsasarthak.notifyr.provider;

import com.vatsasarthak.notifyr.security.PiiMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Development-only stand-in for SmtpEmailProvider, enabled with notification.mock-provider=true.
// Nothing is delivered; notifications handled by it end in status SIMULATED, never SENT.
@Component
@ConditionalOnProperty(prefix = "notification", name = "mock-provider", havingValue = "true")
@Slf4j
public class MockEmailProvider implements EmailProvider {

    // optional artificial provider latency, e.g. for load tests
    @Value("${notification.mock-provider-delay-ms:0}")
    private long delayMs;

    @Override
    public void send(String to, String subject, String body) throws InterruptedException {
        if (to == null || to.isBlank()) {
            throw new PermanentDeliveryException("Recipient email address is missing");
        }
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        log.info("[MOCK] Email NOT delivered (mock provider) to={} subject='{}' bodyLength={}",
                PiiMasking.email(to), subject, body != null ? body.length() : 0);
    }

    @Override
    public boolean isSimulated() {
        return true;
    }
}
