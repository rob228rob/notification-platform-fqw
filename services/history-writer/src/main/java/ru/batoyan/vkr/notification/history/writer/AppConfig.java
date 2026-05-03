package ru.batoyan.vkr.notification.history.writer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.batoyan.vkr.notification.history.writer.config.HistoryClickHouseProperties;
import ru.batoyan.vkr.notification.history.writer.config.HistoryPostgresqlProperties;
import ru.batoyan.vkr.notification.history.writer.config.HistoryStorageProperties;

import java.sql.Driver;
import java.sql.DriverManager;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        HistoryStorageProperties.class,
        HistoryPostgresqlProperties.class,
        HistoryClickHouseProperties.class
})
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    @Bean("historyPostgresJdbc")
    @ConditionalOnProperty(name = "history.storage.type", havingValue = "postgres")
    public NamedParameterJdbcTemplate historyPostgresJdbcTemplate(HistoryPostgresqlProperties properties) {
        return new NamedParameterJdbcTemplate(dataSource(
                "org.postgresql.Driver",
                properties.getUrl(),
                properties.getUsername(),
                properties.getPassword(),
                5000
        ));
    }

    @Bean("historyClickHouseJdbc")
    @ConditionalOnProperty(name = "history.storage.type", havingValue = "clickhouse", matchIfMissing = true)
    public NamedParameterJdbcTemplate historyClickHouseJdbcTemplate(HistoryClickHouseProperties properties) {
        return new NamedParameterJdbcTemplate(dataSource(
                "com.clickhouse.jdbc.ClickHouseDriver",
                properties.getUrl(),
                properties.getUsername(),
                properties.getPassword(),
                properties.getConnectionTimeoutMs()
        ));
    }

    private SimpleDriverDataSource dataSource(
            String driverClassName,
            String url,
            String username,
            String password,
            int connectionTimeoutMs
    ) {
        try {
            DriverManager.setLoginTimeout(Math.max(1, connectionTimeoutMs / 1000));
            var driverClass = Class.forName(driverClassName);
            var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            return new SimpleDriverDataSource(driver, url, username, password);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure JDBC datasource for " + driverClassName, ex);
        }
    }
}
