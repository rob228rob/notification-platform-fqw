package ru.batoyan.vkr.infrastructure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;

@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponseDto> runtimeException(RuntimeException exception) {
        log.error("Exception occure here {}", exception.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponseDto(exception.getMessage() + "\n" + Arrays.toString(exception.getStackTrace()), HttpStatus.BAD_REQUEST.value()));
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ErrorResponseDto {
        private String message;
        private int status;

    }
}
