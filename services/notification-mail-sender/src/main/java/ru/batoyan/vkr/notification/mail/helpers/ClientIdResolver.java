package ru.batoyan.vkr.notification.mail.helpers;

import io.grpc.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.notification.mail.utils.GrpcAuthContext;

import java.util.UUID;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
@Component
public class ClientIdResolver {

    private final Boolean authEnabled;

    public ClientIdResolver(@Value("${grpc.server.auth.enabled:false}") Boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    private static final Logger LOG = LogManager.getLogger();

    public String requireClientId() {
        var clientId = GrpcAuthContext.CLIENT_ID.get();
        if (!authEnabled) {
            return UUID.randomUUID().toString();
        }
        if (clientId == null || clientId.isBlank()) {
            LOG.error("Client id not found, unauthorized");
            throw Status.UNAUTHENTICATED.withDescription("client_id not found in gRPC auth context").asRuntimeException();
        }
        return clientId;
    }
}