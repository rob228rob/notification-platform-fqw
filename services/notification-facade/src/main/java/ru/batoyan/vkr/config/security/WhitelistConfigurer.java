package ru.batoyan.vkr.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class WhitelistConfigurer {

    private final List<AllowedList> whiteList;

    public WhitelistConfigurer(@Value("${keycloak.whitelist.filename}") String filename,
                               final ObjectMapper objectMapper) throws IOException {
        whiteList = Arrays.asList(
                objectMapper.readValue(
                        new ClassPathResource(filename).getInputStream(),
                        AllowedList[].class
                ));
    }

    public void configure(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry httpConfigurer) {
        whiteList
                .forEach(allowed ->
                    allowed.getMethods().forEach(
                                method -> {
                                    httpConfigurer.requestMatchers(new AntPathRequestMatcher(allowed.getPath(), method.name()))
                                            .hasAnyAuthority(allowed.getRoles());
                                }));
        log.info("whitelist configured");
    }

    @Data
    @Validated
    @NoArgsConstructor
    @AllArgsConstructor
    private static class AllowedList {
        @NotBlank
        private String path;
        @NotEmpty
        private Set<HttpMethod> methods;
        @NotEmpty
        private String[] roles;
    }
}
