package ru.batoyan.vkr.notification.templates.usecase.mongo;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder(toBuilder = true)
public record TemplateChannelContentDocument(
        String channel,
        @Nullable String subject,
        @Nullable String body
) {
}
