package ru.batoyan.vkr.notification.mail.constants;

import java.util.Set;

/**
 * Стандартный
 *
 * @author batoyan.rl
 * @since 23.02.2026
 */
public interface Constants {
    int MAX_PAGE_SIZE = 100;

    // whitelist для FieldMask
    Set<String> UPDATE_MASK_ALLOWED = Set.of(
            "template_version",
            "priority",
            "preferred_channel",
            "strategy",
            "payload"
    );

}
