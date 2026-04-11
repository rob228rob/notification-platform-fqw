package ru.batoyan.vkr.notification.loader;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import ru.notification.common.proto.v1.Channel;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public final class FacadeLoadRunner {

    private final LoaderConfig config;
    private final List<String> recipients;
    private final Random random = new Random();

    public FacadeLoadRunner(LoaderConfig config, List<String> recipients) {
        this.config = config;
        this.recipients = recipients;
    }

    public void run() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var sent = new AtomicLong();
        var ok = new AtomicLong();
        var failed = new AtomicLong();

        long durationSeconds = Math.max(1L, config.duration().toSeconds());
        long startedAt = System.nanoTime();

        try (var client = new FacadeClient(config)) {
            for (int second = 0; second < durationSeconds; second++) {
                int currentQps = qpsForSecond(second, durationSeconds);
                long secondStartedAt = System.nanoTime();

                CompletableFuture<?>[] futures = IntStream.range(0, currentQps)
                        .mapToObj(i -> CompletableFuture.runAsync(() -> invokeOnce(client, sent, ok, failed), executor))
                        .toArray(CompletableFuture[]::new);

                CompletableFuture.allOf(futures).join();

                long elapsedNanos = System.nanoTime() - secondStartedAt;
                long sleepNanos = TimeUnit.SECONDS.toNanos(1) - elapsedNanos;
                if (sleepNanos > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                }

                if ((second + 1) % 10 == 0 || second == durationSeconds - 1) {
                    System.out.printf(Locale.ROOT,
                            "LOAD_PROGRESS second=%d/%d qps=%d sent=%d ok=%d failed=%d%n",
                            second + 1, durationSeconds, currentQps, sent.get(), ok.get(), failed.get());
                }
            }
        } finally {
            executor.shutdown();
            boolean awaited = executor.awaitTermination(30, TimeUnit.SECONDS);
            if (!awaited) {
                executor.shutdownNow();
            }
        }

        long totalElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        double effectiveRps = totalElapsedMillis == 0 ? 0d : sent.get() * 1000d / totalElapsedMillis;
        System.out.printf(Locale.ROOT,
                "LOAD_DONE sent=%d ok=%d failed=%d durationSeconds=%d qpsStart=%d qpsEnd=%d threads=%d effectiveRps=%.2f%n",
                sent.get(), ok.get(), failed.get(), durationSeconds, config.qpsStart(), config.qpsEnd(), config.threads(), effectiveRps);
    }

    private void invokeOnce(FacadeClient client, AtomicLong sent, AtomicLong ok, AtomicLong failed) {
        long sequence = sent.incrementAndGet();
        try {
            var result = client.stub().createNotificationEvent(buildRequest(sequence));
            if (result.hasCreatedAt()) {
                ok.incrementAndGet();
                return;
            }
            ok.incrementAndGet();
        } catch (StatusRuntimeException ex) {
            failed.incrementAndGet();
            System.err.printf(Locale.ROOT, "REQUEST_FAILED index=%d status=%s desc=%s%n",
                    sequence, ex.getStatus().getCode(), ex.getStatus().getDescription());
        }
    }

    private int qpsForSecond(long second, long durationSeconds) {
        if (durationSeconds <= 1) {
            return config.qpsEnd();
        }
        double progress = (double) second / (double) (durationSeconds - 1);
        return (int) Math.round(config.qpsStart() + progress * (config.qpsEnd() - config.qpsStart()));
    }

    private CreateEventRequest buildRequest(long sequence) {
        var selectedRecipients = selectRecipients();
        Channel preferred = random.nextDouble() < config.emailShare() ? Channel.CHANNEL_EMAIL : Channel.CHANNEL_SMS;
        DeliveryPriority priority = switch (random.nextInt(3)) {
            case 0 -> DeliveryPriority.DELIVERY_PRIORITY_HIGH;
            case 1 -> DeliveryPriority.DELIVERY_PRIORITY_NORMAL;
            default -> DeliveryPriority.DELIVERY_PRIORITY_LOW;
        };
        boolean immediate = random.nextDouble() < 0.85d;
        var strategyBuilder = DeliveryStrategy.newBuilder()
                .setKind(immediate ? StrategyKind.STRATEGY_KIND_IMMEDIATE : StrategyKind.STRATEGY_KIND_SCHEDULED);
        if (!immediate) {
            strategyBuilder.setSendAt(toTimestamp(Instant.now().plusSeconds(5 + random.nextInt(90))));
        }

        var correlationId = UUID.randomUUID().toString();
        var subject = randomSubject(sequence, preferred);
        var previewText = previewText(sequence, selectedRecipients);
        var body = preferred == Channel.CHANNEL_EMAIL
                ? buildEmailBody(sequence, selectedRecipients, priority, correlationId)
                : randomSmsBody(sequence, selectedRecipients);

        return CreateEventRequest.newBuilder()
                .setIdempotencyKey("loader-" + sequence + "-" + UUID.randomUUID())
                .setTemplateId(config.templateId())
                .setTemplateVersion(config.templateVersion())
                .setPriority(priority)
                .setPreferredChannel(preferred)
                .setStrategy(strategyBuilder.build())
                .putPayload("subject", subject)
                .putPayload("body", body)
                .putPayload("previewText", previewText)
                .putPayload("category", randomCategory())
                .putPayload("correlationId", correlationId)
                .putPayload("tenant", "tenant-" + (1 + random.nextInt(20)))
                .putPayload("emailFormat", preferred == Channel.CHANNEL_EMAIL ? "text/html" : "text/plain")
                .putPayload("recipientCount", Integer.toString(selectedRecipients.size()))
                .setAudience(Audience.newBuilder()
                        .setKind(AudienceKind.AUDIENCE_KIND_EXPLICIT)
                        .setSnapshotOnDispatch(true)
                        .addAllRecipientId(selectedRecipients)
                        .build())
                .build();
    }

    private List<String> selectRecipients() {
        int requested = Math.max(1, Math.min(config.recipientsPerEvent(), recipients.size()));
        Set<String> selected = new LinkedHashSet<>();
        while (selected.size() < requested) {
            selected.add(recipients.get(random.nextInt(recipients.size())));
        }
        return List.copyOf(selected);
    }

    private String randomSubject(long sequence, Channel preferred) {
        String[] subjects = {
                "Order update",
                "Billing alert",
                "Security notice",
                "Weekly digest",
                "Promo campaign",
                "Shipment reminder"
        };
        return subjects[random.nextInt(subjects.length)] + " #" + sequence + " via " + preferred.name();
    }

    private String previewText(long sequence, List<String> recipientIds) {
        return String.format(Locale.ROOT,
                "Synthetic event #%d for %d recipients. Render preview for %s.",
                sequence, recipientIds.size(), recipientIds.getFirst());
    }

    private String randomSmsBody(long sequence, List<String> recipientIds) {
        return String.format(Locale.ROOT,
                "SMS synthetic event #%d for %d recipients. Lead recipient: %s.",
                sequence, recipientIds.size(), recipientIds.getFirst());
    }

    private String buildEmailBody(long sequence,
                                  List<String> recipientIds,
                                  DeliveryPriority priority,
                                  String correlationId) {
        StringBuilder html = new StringBuilder(8_192);
        html.append("<html><body style=\"margin:0;padding:0;background:#f3f6fb;font-family:Arial,sans-serif;color:#102033;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f6fb;padding:24px 0;\">")
                .append("<tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"720\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:720px;max-width:720px;background:#ffffff;border-radius:20px;overflow:hidden;box-shadow:0 12px 40px rgba(16,32,51,0.12);\">")
                .append("<tr><td style=\"padding:32px 40px;background:linear-gradient(135deg,#173b6d 0%,#247ba0 100%);color:#ffffff;\">")
                .append("<div style=\"font-size:12px;letter-spacing:0.12em;text-transform:uppercase;opacity:0.78;\">Notification Platform</div>")
                .append("<h1 style=\"margin:12px 0 8px;font-size:30px;line-height:1.2;\">Synthetic campaign #").append(sequence).append("</h1>")
                .append("<p style=\"margin:0;font-size:15px;line-height:1.6;max-width:560px;\">")
                .append("Large renderable email payload generated by custom-loader to stress gRPC, Kafka and downstream planning.")
                .append("</p></td></tr>")
                .append("<tr><td style=\"padding:28px 40px 8px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;\">")
                .append("<tr>")
                .append(metricCell("Priority", priority.name()))
                .append(metricCell("Recipients", Integer.toString(recipientIds.size())))
                .append(metricCell("Template", config.templateId() + " v" + config.templateVersion()))
                .append("</tr></table></td></tr>");

        for (int section = 1; section <= Math.max(1, config.emailSections()); section++) {
            html.append("<tr><td style=\"padding:16px 40px 0;\">")
                    .append("<div style=\"border:1px solid #d9e2f2;border-radius:16px;padding:20px 24px;background:#fbfdff;\">")
                    .append("<h2 style=\"margin:0 0 12px;font-size:20px;color:#173b6d;\">Section ").append(section).append("</h2>");
            for (int paragraph = 1; paragraph <= Math.max(1, config.emailParagraphRepeat()); paragraph++) {
                html.append("<p style=\"margin:0 0 14px;font-size:15px;line-height:1.72;color:#34495e;\">")
                        .append("Sequence ").append(sequence)
                        .append(", section ").append(section)
                        .append(", paragraph ").append(paragraph)
                        .append(": recipient cohort size is ").append(recipientIds.size())
                        .append(". Primary recipient is ").append(escapeHtml(recipientIds.get((section + paragraph - 2) % recipientIds.size())))
                        .append(". This payload intentionally contains enough structured copy and inline markup to resemble a real email campaign render.")
                        .append("</p>");
            }
            html.append("<ul style=\"margin:0;padding-left:20px;color:#34495e;font-size:14px;line-height:1.7;\">");
            for (int i = 0; i < Math.min(recipientIds.size(), 6); i++) {
                html.append("<li>").append(escapeHtml(recipientIds.get(i))).append("</li>");
            }
            html.append("</ul></div></td></tr>");
        }

        html.append("<tr><td style=\"padding:24px 40px 36px;\">")
                .append("<div style=\"border-radius:14px;background:#0f172a;color:#e2e8f0;padding:18px 20px;font-family:'Courier New',monospace;font-size:13px;line-height:1.7;\">")
                .append("correlationId=").append(escapeHtml(correlationId)).append("<br/>")
                .append("templateId=").append(escapeHtml(config.templateId())).append("<br/>")
                .append("templateVersion=").append(config.templateVersion()).append("<br/>")
                .append("sampleRecipients=").append(escapeHtml(String.join(", ", recipientIds.subList(0, Math.min(5, recipientIds.size())))))
                .append("</div></td></tr>")
                .append("</table></td></tr></table></body></html>");
        return html.toString();
    }

    private String metricCell(String label, String value) {
        return "<td style=\"width:33.33%;padding:0 12px 20px 0;\">"
                + "<div style=\"background:#eef4fb;border-radius:14px;padding:16px 18px;\">"
                + "<div style=\"font-size:11px;text-transform:uppercase;letter-spacing:0.08em;color:#5b708b;\">"
                + escapeHtml(label)
                + "</div><div style=\"margin-top:8px;font-size:16px;font-weight:700;color:#17324d;\">"
                + escapeHtml(value)
                + "</div></div></td>";
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String randomCategory() {
        String[] categories = {"order", "billing", "risk", "marketing", "support", "system"};
        return categories[random.nextInt(categories.length)];
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static final class FacadeClient implements AutoCloseable {
        private final ManagedChannel channel;
        private final NotificationFacadeGrpc.NotificationFacadeBlockingStub stub;

        private FacadeClient(LoaderConfig config) {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(config.facadeHost(), config.facadePort());
            if (config.facadePlaintext()) {
                builder.usePlaintext();
            }
            this.channel = builder.build();
            this.stub = NotificationFacadeGrpc.newBlockingStub(channel);
        }

        private NotificationFacadeGrpc.NotificationFacadeBlockingStub stub() {
            return stub;
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            channel.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
