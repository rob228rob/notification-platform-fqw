package ru.batoyan.vkr.notification.loader;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

public record LoaderConfig(
        String mode,
        int users,
        int threads,
        Duration duration,
        int qpsStart,
        int qpsEnd,
        String facadeHost,
        int facadePort,
        boolean facadePlaintext,
        String redisHost,
        int redisPort,
        String redisUser,
        String redisPassword,
        String redisKeyPrefix,
        String recipientPrefix,
        double emailShare,
        double smsShare,
        String templateId,
        int templateVersion
) {

    public static LoaderConfig from(String[] args) {
        Properties defaults = loadDefaults();
        String mode = arg(args, "--mode", env(defaults, "LOADER_MODE", "seed-and-load"));
        int users = intArg(args, "--users", intEnv(defaults, "LOADER_USERS", 10000));
        int threads = intArg(args, "--threads", intEnv(defaults, "LOADER_THREADS", 24));
        int qpsStart = intArg(args, "--qps-start", intEnv(defaults, "LOADER_QPS_START", intEnv(defaults, "LOADER_QPS", 10)));
        int qpsEnd = intArg(args, "--qps-end", intEnv(defaults, "LOADER_QPS_END", intEnv(defaults, "LOADER_QPS", 250)));
        Duration duration = Duration.ofSeconds(intArg(args, "--duration-seconds", intEnv(defaults, "LOADER_DURATION_SECONDS", 600)));
        String facadeHost = arg(args, "--facade-host", env(defaults, "FACADE_HOST", "localhost"));
        int facadePort = intArg(args, "--facade-port", intEnv(defaults, "FACADE_PORT", 9090));
        boolean facadePlaintext = boolArg(args, "--facade-plaintext", boolEnv(defaults, "FACADE_PLAINTEXT", true));
        String redisHost = arg(args, "--redis-host", env(defaults, "REDIS_HOST", "localhost"));
        int redisPort = intArg(args, "--redis-port", intEnv(defaults, "REDIS_PORT", 6379));
        String redisUser = arg(args, "--redis-user", env(defaults, "REDIS_USER", "redisuser"));
        String redisPassword = arg(args, "--redis-password", env(defaults, "REDIS_PASSWORD", "redisuserpassword"));
        String redisKeyPrefix = arg(args, "--redis-key-prefix", env(defaults, "REDIS_KEY_PREFIX", "profile-consent:recipient:"));
        String recipientPrefix = arg(args, "--recipient-prefix", env(defaults, "RECIPIENT_PREFIX", "load-user-"));
        double emailShare = doubleArg(args, "--email-share", doubleEnv(defaults, "EMAIL_SHARE", 0.7d));
        double smsShare = doubleArg(args, "--sms-share", doubleEnv(defaults, "SMS_SHARE", 0.3d));
        String templateId = arg(args, "--template-id", env(defaults, "TEMPLATE_ID", "tmpl-order-reminder"));
        int templateVersion = intArg(args, "--template-version", intEnv(defaults, "TEMPLATE_VERSION", 1));
        return new LoaderConfig(mode, users, threads, duration, qpsStart, qpsEnd, facadeHost, facadePort, facadePlaintext,
                redisHost, redisPort, redisUser, redisPassword, redisKeyPrefix, recipientPrefix, emailShare, smsShare,
                templateId, templateVersion);
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int intArg(String[] args, String name, int fallback) {
        return Integer.parseInt(arg(args, name, Integer.toString(fallback)));
    }

    private static double doubleArg(String[] args, String name, double fallback) {
        return Double.parseDouble(arg(args, name, Double.toString(fallback)));
    }

    private static boolean boolArg(String[] args, String name, boolean fallback) {
        return Boolean.parseBoolean(arg(args, name, Boolean.toString(fallback)));
    }

    private static Properties loadDefaults() {
        Properties properties = new Properties();
        try (InputStream inputStream = LoaderConfig.class.getClassLoader().getResourceAsStream("loader.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static String env(Properties defaults, String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaults.getProperty(name, fallback);
        }
        return value;
    }

    private static int intEnv(Properties defaults, String name, int fallback) {
        return Integer.parseInt(env(defaults, name, Integer.toString(fallback)));
    }

    private static double doubleEnv(Properties defaults, String name, double fallback) {
        return Double.parseDouble(env(defaults, name, Double.toString(fallback)));
    }

    private static boolean boolEnv(Properties defaults, String name, boolean fallback) {
        return Boolean.parseBoolean(env(defaults, name, Boolean.toString(fallback)));
    }
}
