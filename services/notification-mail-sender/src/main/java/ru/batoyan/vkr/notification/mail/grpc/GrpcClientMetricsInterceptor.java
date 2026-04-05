package ru.batoyan.vkr.notification.mail.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GrpcClientMetricsInterceptor implements ClientInterceptor {

    private final MeterRegistry meterRegistry;

    @Value("${spring.application.name}")
    private String applicationName;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        var sample = Timer.start(meterRegistry);
        var fullMethodName = method.getFullMethodName();
        var serviceName = extractServiceName(fullMethodName);

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener<>(responseListener, sample, fullMethodName, serviceName), headers);
            }
        };
    }

    private String extractServiceName(String fullMethodName) {
        var separator = fullMethodName.lastIndexOf('/');
        return separator >= 0 ? fullMethodName.substring(0, separator) : fullMethodName;
    }

    private final class ForwardingClientCallListener<RespT> extends ClientCall.Listener<RespT> {

        private final ClientCall.Listener<RespT> delegate;
        private final Timer.Sample sample;
        private final String fullMethodName;
        private final String serviceName;

        private ForwardingClientCallListener(ClientCall.Listener<RespT> delegate,
                                             Timer.Sample sample,
                                             String fullMethodName,
                                             String serviceName) {
            this.delegate = delegate;
            this.sample = sample;
            this.fullMethodName = fullMethodName;
            this.serviceName = serviceName;
        }

        @Override
        public void onHeaders(Metadata headers) {
            delegate.onHeaders(headers);
        }

        @Override
        public void onMessage(RespT message) {
            delegate.onMessage(message);
        }

        @Override
        public void onClose(io.grpc.Status status, Metadata trailers) {
            try {
                delegate.onClose(status, trailers);
            } finally {
                sample.stop(Timer.builder("grpc.client.requests")
                        .description("gRPC client request duration")
                        .tag("application", applicationName)
                        .tag("service", serviceName)
                        .tag("method", fullMethodName)
                        .tag("status", status.getCode().name())
                        .register(meterRegistry));
            }
        }

        @Override
        public void onReady() {
            delegate.onReady();
        }
    }
}
