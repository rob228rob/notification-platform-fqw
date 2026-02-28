package ru.batoyan.vkr.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.batoyan.vkr.infrastructure.CustomExceptionHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper jacksonObjectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.sendRedirect( "/error");
        log.info("Access denied: req={}, type={}, error={}",
                request.getRequestURI(),
                accessDeniedException.getClass().getSimpleName(),
                accessDeniedException.getMessage());
        log.debug("Access denied exception", accessDeniedException);
        response.getOutputStream().println(
                jacksonObjectMapper.writeValueAsString(
                        new CustomExceptionHandler.ErrorResponseDto(
                "Access Denied", HttpServletResponse.SC_FORBIDDEN
        )));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
    }
}
