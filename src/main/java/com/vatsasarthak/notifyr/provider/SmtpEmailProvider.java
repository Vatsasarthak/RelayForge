package com.vatsasarthak.notifyr.provider;

import com.vatsasarthak.notifyr.security.PiiMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notification", name = "mock-provider", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SmtpEmailProvider implements EmailProvider{

    private final JavaMailSender mailSender;

    // always set in application.yaml (MAIL_FROM); no fallback to an address on a domain this project does not own
    @Value("${notification.mail.from}")
    private String fromAddress;

    @Override
    public void send(String to, String subject, String body) throws Exception {

        if (to == null || to.isBlank()) {
            throw new PermanentDeliveryException("Recipient email address is missing");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);

        log.info("Email sent to {} with subject: {}", PiiMasking.email(to), subject);
    }
}
