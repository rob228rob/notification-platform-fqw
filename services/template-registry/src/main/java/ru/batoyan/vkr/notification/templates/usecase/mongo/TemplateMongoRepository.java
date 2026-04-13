package ru.batoyan.vkr.notification.templates.usecase.mongo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TemplateMongoRepository extends MongoRepository<TemplateDocument, String> {

    Optional<TemplateDocument> findByClientIdAndTemplateId(String clientId, String templateId);

    Optional<TemplateDocument> findByClientIdAndCreateIdempotencyKey(String clientId, String createIdempotencyKey);

    Page<TemplateDocument> findByClientId(String clientId, Pageable pageable);

    Page<TemplateDocument> findByClientIdAndStatus(String clientId, String status, Pageable pageable);
}
