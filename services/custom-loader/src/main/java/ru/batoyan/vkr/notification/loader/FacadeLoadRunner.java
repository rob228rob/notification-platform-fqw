package ru.batoyan.vkr.notification.loader;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import ru.notification.common.proto.v1.Channel;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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

        try (FacadeClient client = new FacadeClient(config)) {
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
        String recipientId = recipients.get(random.nextInt(recipients.size()));
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

        return CreateEventRequest.newBuilder()
                .setIdempotencyKey("loader-" + sequence + "-" + UUID.randomUUID())
                .setTemplateId(config.templateId())
                .setTemplateVersion(config.templateVersion())
                .setPriority(priority)
                .setPreferredChannel(preferred)
                .setStrategy(strategyBuilder.build())
                .putPayload("subject", randomSubject(sequence, preferred))
                .putPayload("body", randomBody(sequence, recipientId))
                .putPayload("category", randomCategory())
                .putPayload("correlationId", UUID.randomUUID().toString())
                .putPayload("tenant", "tenant-" + (1 + random.nextInt(20)))
                .setAudience(Audience.newBuilder()
                        .setKind(AudienceKind.AUDIENCE_KIND_EXPLICIT)
                        .setSnapshotOnDispatch(true)
                        .addRecipientId(recipientId)
                        .build())
                .build();
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

    private String randomBody(long sequence, String recipientId) {
        String[] bodies = {
                "Payload for recipient %1$s, operation %2$d, segment retail",
                "Event %2$d for %1$s with diverse content and delivery metadata",
                "Reminder for %1$s, synthetic load message %2$d",
                "Operational message %2$d routed to %1$s",
                "Multi-field body for %1$s, sequence=%2$d"
        };
        String template = bodies[random.nextInt(bodies.length)];
        return String.format(Locale.ROOT, template, recipientId, sequence);
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
