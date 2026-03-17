package ru.batoyan.vkr.notification.facade.template;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import ru.notification.common.proto.v1.Channel;
import ru.notification.templates.proto.v1.RenderPreviewRequest;
import ru.notification.templates.proto.v1.TemplateRegistryGrpc;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class TemplateRegistryRenderClient {

    private final TemplateRegistryClientProperties properties;
    private ManagedChannel channel;
    private TemplateRegistryGrpc.TemplateRegistryBlockingStub blockingStub;

    public TemplateRegistryRenderClient(TemplateRegistryClientProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public RenderedTemplate renderPreview(
            String templateId,
            int templateVersion,
            Channel channel,
            Map<String, String> payload
    ) {
        if (!properties.isEnabled()) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("template registry integration is disabled")
                    .asRuntimeException();
        }

        try {
            var response = stub()
                    .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS)
                    .renderPreview(RenderPreviewRequest.newBuilder()
                            .setTemplateId(templateId)
                            .setVersion(templateVersion)
                            .setChannel(channel)
                            .putAllPayload(payload)
                            .build());
            return new RenderedTemplate(response.getSubject(), response.getBody());
        } catch (StatusRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw Status.UNAVAILABLE
                    .withDescription("template registry call failed: " + ex.getMessage())
                    .withCause(ex)
                    .asRuntimeException();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    private synchronized TemplateRegistryGrpc.TemplateRegistryBlockingStub stub() {
        if (blockingStub == null) {
            var builder = ManagedChannelBuilder.forAddress(properties.getHost(), properties.getPort());
            if (properties.isPlaintext()) {
                builder.usePlaintext();
            }
            this.channel = builder.build();
            this.blockingStub = TemplateRegistryGrpc.newBlockingStub(this.channel);
        }
        return blockingStub;
    }

    public record RenderedTemplate(String subject, String body) {
    }
}
