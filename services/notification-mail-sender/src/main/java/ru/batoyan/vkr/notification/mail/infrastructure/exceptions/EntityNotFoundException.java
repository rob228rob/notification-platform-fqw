package ru.batoyan.vkr.notification.mail.infrastructure.exceptions;

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String msg) {
        super(msg);
    }
}
