package ru.batoyan.vkr.notification.facade.infrastructure.exceptions;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.Serial;

@Getter
@Slf4j
public class ApplicationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final HttpStatus httpStatus;

    public ApplicationException(String message, HttpStatus status) {
        super(message);
        this.httpStatus = status;
        log.error("APP internal error: [{}] with HTTP-STATUS: [{}]", message, status);
    }

}
