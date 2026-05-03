package ru.batoyan.vkr.notification.history.writer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "history.postgresql")
public class HistoryPostgresqlProperties {

    private String url;
    private String username;
    private String password;

}
