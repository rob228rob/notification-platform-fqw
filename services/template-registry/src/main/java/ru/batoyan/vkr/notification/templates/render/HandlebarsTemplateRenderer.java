package ru.batoyan.vkr.notification.templates.render;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import ru.notification.templates.proto.v1.TemplateEngine;

import java.util.Map;

@Component
public class HandlebarsTemplateRenderer implements TemplateRenderer {

    private final Handlebars handlebars = new Handlebars();

    @Override
    public TemplateEngine engine() {
        return TemplateEngine.TEMPLATE_ENGINE_HANDLEBARS;
    }

    @Override
    @SneakyThrows
    public String render(@Nullable String template, Map<String, String> payload) {
        Template compiled = handlebars.compileInline(template == null ? "" : template);
        return compiled.apply(payload);
    }
}
