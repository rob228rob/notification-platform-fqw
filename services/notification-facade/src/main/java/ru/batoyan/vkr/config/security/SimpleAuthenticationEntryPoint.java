package ru.batoyan.vkr.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.batoyan.vkr.infrastructure.CustomExceptionHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper jacksonObjectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        log.info("Authentication failed: req={}, type={}, error={}",
                request.getRequestURI(),
                authException.getClass().getSimpleName(),
                authException.getMessage());
        log.debug("Authentication failed exception", authException);
        response.getOutputStream().println(
                jacksonObjectMapper.writeValueAsString(
                        new CustomExceptionHandler.ErrorResponseDto(
                                "Unauthorized error", HttpServletResponse.SC_FORBIDDEN
                        )));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
    }
}
