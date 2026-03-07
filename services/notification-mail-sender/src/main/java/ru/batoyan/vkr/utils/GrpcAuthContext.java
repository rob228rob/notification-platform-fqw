package ru.batoyan.vkr.utils;

import io.grpc.Context;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
public final class GrpcAuthContext {
    private GrpcAuthContext() {}
    public static final Context.Key<String> CLIENT_ID = Context.key("client_id");
}