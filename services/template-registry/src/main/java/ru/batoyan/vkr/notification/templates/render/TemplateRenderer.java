package ru.batoyan.vkr.notification.templates.render;

import ru.notification.templates.proto.v1.TemplateEngine;

import java.util.Map;

public interface TemplateRenderer {
    TemplateEngine engine();

    String render(String template, Map<String, String> payload);
}
