package ru.batoyan.vkr.notification.history.writer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "history.clickhouse")
public class HistoryClickHouseProperties {

    private String url;
    private String username;
    private String password;
    private String database;
    private String table;
    private int batchSize = 500;
    private int connectionTimeoutMs = 5000;

}
