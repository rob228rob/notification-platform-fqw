package ru.batoyan.vkr.notification.templates.usecase;

import ru.notification.templates.proto.v1.*;

public interface TemplateRegistryUseCase {
    CreateTemplateResponse createTemplate(String clientId, CreateTemplateRequest request);
    UpdateTemplateResponse updateTemplate(String clientId, UpdateTemplateRequest request);
    GetTemplateResponse getTemplate(String clientId, GetTemplateRequest request);
    ListTemplatesResponse listTemplates(String clientId, ListTemplatesRequest request);
    RenderPreviewResponse renderPreview(String clientId, RenderPreviewRequest request);
}
