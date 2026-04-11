package ru.batoyan.vkr.notification.loader;

import java.util.logging.Logger;

public final class CustomLoaderMain {
    private static final Logger LOG = Logger.getLogger(CustomLoaderMain.class.getName());

    private CustomLoaderMain() {
    }

    public static void main(String[] args) throws Exception {
        var config = LoaderConfig.from(args);
        var seeder = new RedisProfileSeeder(config);

        switch (config.mode()) {
            case "seed" -> {
                System.out.printf("SEED_START users=%d redis=%s:%d%n", config.users(), config.redisHost(), config.redisPort());
                seeder.seed();
                System.out.println("SEED_DONE");
            }
            case "load" -> {
                System.out.printf("LOAD_START users=%d facade=%s:%d duration=%ss qpsStart=%d qpsEnd=%d threads=%d%n",
                        config.users(), config.facadeHost(), config.facadePort(), config.duration().toSeconds(),
                        config.qpsStart(), config.qpsEnd(), config.threads());
                new FacadeLoadRunner(config, seeder.recipientIds()).run();
            }
            case "seed-and-load" -> {
                System.out.printf("SEED_START users=%d redis=%s:%d%n", config.users(), config.redisHost(), config.redisPort());
                seeder.seed();
                System.out.println("SEED_DONE");
                System.out.printf("LOAD_START users=%d facade=%s:%d duration=%ss qpsStart=%d qpsEnd=%d threads=%d%n",
                        config.users(), config.facadeHost(), config.facadePort(), config.duration().toSeconds(),
                        config.qpsStart(), config.qpsEnd(), config.threads());
                new FacadeLoadRunner(config, seeder.recipientIds()).run();
            }
            default -> throw new IllegalArgumentException("Unsupported --mode: " + config.mode());
        }
    }
}
