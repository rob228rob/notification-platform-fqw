package ru.batoyan.vkr.config.keycloak;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class KeycloakConfig {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${keycloak.certs-url}") String keycloakCertsUrl,
                                 @Value("${keycloak.cert-algorithms}") List<String> algorithms) {
        //https://github.com/keycloak/keycloak-documentation/blob/main/securing_apps/topics/oidc/oidc-generic.adoc
        ///Certificate endpoint - realms/{realm-name}/protocol/openid-connect/certs
        final NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder jwtDecoderBuilder = NimbusJwtDecoder.withJwkSetUri(keycloakCertsUrl);
        for (String algorithm : algorithms) {
            final SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.from(algorithm);
            if (signatureAlgorithm == null) {
                throw new IllegalArgumentException("JWS algorithm - " + algorithm + " unknown");
            }
            jwtDecoderBuilder.jwsAlgorithm(signatureAlgorithm);
        }
        return jwtDecoderBuilder.build();
    }

    @Bean
    public KeyCloakTokenConverter keycloakRoleConverter() {
        return new KeyCloakTokenConverter();
    }


    public static class KeyCloakTokenConverter implements Converter<Jwt, JwtAuthenticationToken> {

        public static final String REALM_ACCESS = "realm_access";
        public static final String ROLES = "roles";

        @Override
        @SuppressWarnings("unchecked")
        public JwtAuthenticationToken convert(Jwt jwt) {
            final Map<String, Object> realmAccess = (Map<String, Object>) jwt.getClaims().get(REALM_ACCESS);
            return new JwtAuthenticationToken(jwt, ((List<String>) realmAccess.get(ROLES)).stream()
                    //.map(roleName -> "ROLE_" + roleName) // prefix to map to a Spring Security "role"
                    .map(SimpleGrantedAuthority::new)
                    .toList());
        }
    }
}
