package ru.batoyan.vkr.notification.facade.config;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "security.grpc")
public class GrpcSecurityProperties {
    /**
     * JWT claim name containing tenant/client id (UUID string).
     * Examples: client_id, azp, tenant_id.
     */
    private String clientIdClaim = "client_id";
}