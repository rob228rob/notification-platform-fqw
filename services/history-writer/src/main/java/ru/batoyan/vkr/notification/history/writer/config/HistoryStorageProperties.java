package ru.batoyan.vkr.notification.history.writer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "history.storage")
public class HistoryStorageProperties {

    private String type = "clickhouse";

}
