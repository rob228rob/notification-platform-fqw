package ru.batoyan.vkr.notification.templates.usecase.mongo;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
public record TemplateVersionDocument(
        int version,
        String engine,
        List<TemplateChannelContentDocument> contents,
        Instant createdAt
) {
}
