package ru.batoyan.vkr.notification.mail.sender.infrastructure.utility;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class ExceptionUtility {

    /**
     * Бросает исключение если хотя бы один объект - null
     *
     * @param exceptionMessage
     * @param objects
     */
    public static void throwIfAnyObjectIsNull(String exceptionMessage, Object... objects) {
        for (Object obj: objects) {
            if (obj != null) {
                log.debug("Object is not null: {}", obj);
                continue;
            }

            log.error(exceptionMessage);
            throw new IllegalStateException("Object is null!!: " + exceptionMessage);
        }
    }
}
