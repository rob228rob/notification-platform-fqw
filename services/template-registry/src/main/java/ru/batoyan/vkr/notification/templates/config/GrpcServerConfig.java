package ru.batoyan.vkr.notification.templates.config;

import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
@Configuration
public class GrpcServerConfig {
    @Bean(destroyMethod = "close")
    public ExecutorService grpcVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public GrpcServerConfigurer grpcExecutorConfigurer(ExecutorService grpcVirtualThreadExecutor) {
        return serverBuilder -> serverBuilder.executor(grpcVirtualThreadExecutor);
    }
}

