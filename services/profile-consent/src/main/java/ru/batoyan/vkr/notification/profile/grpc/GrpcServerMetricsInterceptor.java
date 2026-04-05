package ru.batoyan.vkr.notification.profile.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@GrpcGlobalServerInterceptor
@RequiredArgsConstructor
public class GrpcServerMetricsInterceptor implements ServerInterceptor {

    private final MeterRegistry meterRegistry;

    @Value("${spring.application.name}")
    private String applicationName;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        var sample = Timer.start(meterRegistry);
        var fullMethodName = call.getMethodDescriptor().getFullMethodName();
        var serviceName = extractServiceName(fullMethodName);

        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(io.grpc.Status status, Metadata trailers) {
                try {
                    super.close(status, trailers);
                } finally {
                    sample.stop(Timer.builder("grpc.server.requests")
                            .description("gRPC server request duration")
                            .tag("application", applicationName)
                            .tag("service", serviceName)
                            .tag("method", fullMethodName)
                            .tag("status", status.getCode().name())
                            .register(meterRegistry));
                }
            }
        }, headers);
    }

    private String extractServiceName(String fullMethodName) {
        var separator = fullMethodName.lastIndexOf('/');
        return separator >= 0 ? fullMethodName.substring(0, separator) : fullMethodName;
    }
}
