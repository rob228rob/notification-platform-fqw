package ru.batoyan.vkr.notification.mail.sender.services.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingMailGateway implements MailGateway {

    @Override
    public void send(MailMessage message) {
        log.info("MAIL send deliveryId={}, idempotencyKey={}, recipientId={}, email={}, templateId={}, templateVersion={}",
                message.deliveryId(),
                message.idempotencyKey(),
                message.recipientId(),
                message.email(),
                message.templateId(),
                message.templateVersion());
    }
}
