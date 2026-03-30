package ru.batoyan.vkr.notification.sms.sender.services.kafka.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.notification.sms.sender.services.kafka.Jsons;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringMailGateway implements MailGateway {

    private final JavaMailSender mailSender;
    private final SpringMailGatewayProperties properties;

    @Override
    public BatchSendResult sendBatch(List<MailMessage> messages) {
        if (messages.isEmpty()) {
            return new BatchSendResult(List.of(), Map.of());
        }

        if (properties.isLogOnly()) {
            for (var message : messages) {
                var envelope = toEnvelope(message);
                log.info("[MAIL-LOG-ONLY] deliveryId={}, to={}, subject={}, body={}",
                        message.deliveryId(), message.email(), envelope.getSubject(), envelope.getText());
            }
            return new BatchSendResult(
                    messages.stream().map(MailMessage::deliveryId).toList(),
                    Map.of()
            );
        }

        var mailByEnvelope = new IdentityHashMap<SimpleMailMessage, MailMessage>();
        var envelopeBatch = new SimpleMailMessage[messages.size()];

        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            var envelope = toEnvelope(message);
            envelopeBatch[index] = envelope;
            mailByEnvelope.put(envelope, message);
        }

        try {
            mailSender.send(envelopeBatch);
            log.info("MAIL batch sent size={}", messages.size());
            return new BatchSendResult(
                    messages.stream().map(MailMessage::deliveryId).toList(),
                    Map.of()
            );
        } catch (MailSendException ex) {
            var failed = new LinkedHashMap<String, String>();
            ex.getFailedMessages().forEach((failedMessage, failure) -> {
                if (failedMessage instanceof SimpleMailMessage envelope) {
                    var message = mailByEnvelope.get(envelope);
                    if (message != null) {
                        failed.put(message.deliveryId(), rootMessage(failure));
                    }
                }
            });

            var succeeded = new ArrayList<String>(messages.size() - failed.size());
            for (var message : messages) {
                if (!failed.containsKey(message.deliveryId())) {
                    succeeded.add(message.deliveryId());
                }
            }

            log.warn("MAIL batch partially failed size={}, succeeded={}, failed={}",
                    messages.size(), succeeded.size(), failed.size(), ex);
            return new BatchSendResult(succeeded, failed);
        } catch (MailException ex) {
            var failed = new java.util.LinkedHashMap<String, String>();
            for (var message : messages) {
                failed.put(message.deliveryId(), rootMessage(ex));
            }
            log.warn("MAIL batch failed size={}", messages.size(), ex);
            return new BatchSendResult(List.of(), failed);
        }
    }

    private SimpleMailMessage toEnvelope(MailMessage message) {
        var payload = Jsons.read(message.payloadJson());
        var envelope = new SimpleMailMessage();
        envelope.setFrom(properties.getFrom());
        envelope.setTo(message.email());
        envelope.setSubject(resolveSubject(message, payload));
        envelope.setText(resolveBody(payload));
        return envelope;
    }

    private String resolveSubject(MailMessage message, Map<String, Object> payload) {
        var subject = firstNonBlank(
                payload.get("subject"),
                payload.get("title")
        );
        if (subject != null) {
            return subject;
        }
        return properties.getSubjectFallbackPrefix() + " " + message.templateId() + " v" + message.templateVersion();
    }

    private String resolveBody(Map<String, Object> payload) {
        var body = firstNonBlank(
                payload.get("body"),
                payload.get("text"),
                payload.get("message"),
                payload.get("content")
        );
        return body != null ? body : Jsons.write(payload);
    }

    private @Nullable String firstNonBlank(Object... values) {
        for (var value : values) {
            if (value != null) {
                var text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String rootMessage(Throwable error) {
        var current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
