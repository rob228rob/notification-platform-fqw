package ru.batoyan.vkr;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import com.mai.proj.config.es.EsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Import(EsConfig.class)
public class EsTest {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Test
    void contextLoads() {
        assertNotNull(elasticsearchClient);
    }

    @Test
    void testElasticsearchConnection() throws IOException {
        HealthResponse health = elasticsearchClient.cluster().health();
        assertNotNull(health);
        assertNotNull(health.status());
        System.out.println("Cluster status: " + health.status().jsonValue());
    }
}