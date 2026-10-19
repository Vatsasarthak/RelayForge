package com.vatsasarthak.notifyr.provider;

import com.vatsasarthak.notifyr.security.PiiMasking;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notification", name = "mock-provider", havingValue = "false", matchIfMissing = true)
@Slf4j
public class TwilioSmsProvider implements SmsProvider {

    @Value("${notification.twilio.account-sid:}")
    private String accountSid;

    @Value("${notification.twilio.auth-token:}")
    private String authToken;

    @Value("${notification.twilio.from-number:}")
    private String fromNumber;

    private boolean configured;

    @PostConstruct
    public void init(){
        configured = !accountSid.isBlank() && !authToken.isBlank() && !fromNumber.isBlank();
        if (!configured) {
            // email-only deployments do not need Twilio, so this is not a startup failure
            log.warn("Twilio credentials are not configured; SMS notifications will fail until "
                    + "TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM_NUMBER are set");
            return;
        }
        Twilio.init(accountSid, authToken);
    }


    @Override
    public void send(String phoneNumber, String message) throws Exception {

        if (!configured) {
            throw new PermanentDeliveryException("Twilio SMS provider is not configured");
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new PermanentDeliveryException("Recipient phone number is missing");
        }

        try {
            Message.creator(
                    new PhoneNumber(phoneNumber),
                    new PhoneNumber(fromNumber),
                    message
            ).create();
        } catch (ApiException e) {
            // 4xx (invalid number, unverified recipient, ...) cannot succeed on retry; 429 and 5xx can
            Integer status = e.getStatusCode();
            if (status != null && status >= 400 && status < 500 && status != 429) {
                throw new PermanentDeliveryException("Twilio rejected the message: " + e.getMessage());
            }
            throw e;
        }

        log.info("SMS sent via Twilio to {}", PiiMasking.phone(phoneNumber));
    }
}
