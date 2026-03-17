package ru.batoyan.vkr.notification.templates.render;

import org.springframework.stereotype.Service;
import ru.notification.templates.proto.v1.TemplateEngine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class TemplateRenderService {
    private final Map<TemplateEngine, TemplateRenderer> registry;

    public TemplateRenderService(List<TemplateRenderer> renderers) {
        this.registry = new EnumMap<>(TemplateEngine.class);
        renderers.forEach(r -> registry.put(r.engine(), r));
    }

    public String render(TemplateEngine engine, String template, Map<String, String> payload) {
        var renderer = registry.get(engine);
        if (renderer == null) {
            throw new IllegalArgumentException("Renderer not found for engine " + engine);
        }
        return renderer.render(template, payload);
    }
}
