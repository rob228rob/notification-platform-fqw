package ru.batoyan.vkr.notification.templates.render;

import org.springframework.stereotype.Component;
import ru.notification.templates.proto.v1.TemplateEngine;

import java.util.Map;

@Component
public class SimpleTemplateRenderer implements TemplateRenderer {
    @Override
    public TemplateEngine engine() {
        return TemplateEngine.TEMPLATE_ENGINE_FREEMARKER;
    }

    @Override
    public String render(String template, Map<String, String> payload) {
        String result = template == null ? "" : template;
        for (var entry : payload.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
