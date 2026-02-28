package ru.batoyan.vkr.config.security;

import ru.batoyan.vkr.config.keycloak.KeycloakConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final String[] PERMANENT_ALLOWED = {
            "/auth/**",
            "/css/**",
            "/scripts/**",
            "/js/**",
            "/home",
            "/signup",
            "/agreement",
            "/error",
            "/kafka/*",
            "/swagger-ui/**",
            "/api-docs/**"
    };

    private final KeycloakConfig.KeyCloakTokenConverter keyCloakTokenConverter;
    private final SimpleAuthenticationEntryPoint authenticationEntryPoint;
    private final SimpleAccessDeniedHandler accessDeniedHandler;
    private final WhitelistConfigurer whitelistConfigurer;

    @Value("${keycloak.enabled}")
    private boolean keycloakEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PERMANENT_ALLOWED).permitAll();

                    if (keycloakEnabled) {
                        whitelistConfigurer.configure(auth);
                        auth.anyRequest().denyAll();
                    } else {
                        auth.anyRequest().permitAll();
                    }

                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keyCloakTokenConverter))
                )
                .exceptionHandling(handler -> {
                    handler.accessDeniedHandler(accessDeniedHandler);
                    handler.authenticationEntryPoint(authenticationEntryPoint);
                });

        return http.build();
    }
}