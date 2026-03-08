package ru.batoyan.vkr.notification.facade.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
@Configuration
@EnableConfigurationProperties(GrpcSecurityProperties.class)
public class GrpcSecurityConfig {
}
