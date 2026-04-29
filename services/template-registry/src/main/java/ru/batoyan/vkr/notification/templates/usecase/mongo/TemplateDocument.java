package ru.batoyan.vkr.notification.templates.usecase.mongo;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "templates")
@CompoundIndexes({
        @CompoundIndex(name = "ux_client_template", def = "{'clientId': 1, 'templateId': 1}", unique = true),
        @CompoundIndex(name = "ux_client_create_idempotency", def = "{'clientId': 1, 'createIdempotencyKey': 1}", unique = true, sparse = true),
        @CompoundIndex(name = "ix_client_updated_at", def = "{'clientId': 1, 'updatedAt': -1}")
})
@Builder(toBuilder = true)
public record TemplateDocument(
        @Id String id,
        String templateId,
        String clientId,
        String name,
        String description,
        String status,
        int activeVersion,
        String createIdempotencyKey,
        long schemaVersion,
        Instant createdAt,
        Instant updatedAt,
        @Version Long revision,
        List<TemplateVersionDocument> versions
) {
}
