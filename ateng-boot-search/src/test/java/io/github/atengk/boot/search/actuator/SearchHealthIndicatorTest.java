package io.github.atengk.boot.search.actuator;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.ElasticsearchClusterClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SearchHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class SearchHealthIndicatorTest {

    @Test
    @DisplayName("集群状态 GREEN 时探针返回 UP 且包含完整集群拓扑指标")
    void testHealthGreen() throws IOException {
        SearchEngineClient engineClient = mock(SearchEngineClient.class);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient clusterClient = mock(ElasticsearchClusterClient.class);
        HealthResponse healthResponse = mock(HealthResponse.class);

        when(engineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);
        when(engineClient.getElasticsearchClient()).thenReturn(esClient);
        when(esClient.cluster()).thenReturn(clusterClient);
        when(clusterClient.health()).thenReturn(healthResponse);

        when(healthResponse.status()).thenReturn(HealthStatus.Green);
        when(healthResponse.clusterName()).thenReturn("ateng-cluster");
        when(healthResponse.numberOfNodes()).thenReturn(3);
        when(healthResponse.numberOfDataNodes()).thenReturn(2);
        when(healthResponse.activePrimaryShards()).thenReturn(5);
        when(healthResponse.activeShards()).thenReturn(10);
        when(healthResponse.relocatingShards()).thenReturn(0);
        when(healthResponse.initializingShards()).thenReturn(0);
        when(healthResponse.unassignedShards()).thenReturn(0);

        SearchHealthIndicator indicator = new SearchHealthIndicator(engineClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "green");
        assertThat(health.getDetails()).containsEntry("clusterName", "ateng-cluster");
        assertThat(health.getDetails()).containsEntry("numberOfNodes", 3);
        assertThat(health.getDetails()).containsEntry("numberOfDataNodes", 2);
    }

    @Test
    @DisplayName("集群状态 YELLOW 时探针亦返回 UP 保障服务可用性探查不熔断")
    void testHealthYellow() throws IOException {
        SearchEngineClient engineClient = mock(SearchEngineClient.class);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient clusterClient = mock(ElasticsearchClusterClient.class);
        HealthResponse healthResponse = mock(HealthResponse.class);

        when(engineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);
        when(engineClient.getElasticsearchClient()).thenReturn(esClient);
        when(esClient.cluster()).thenReturn(clusterClient);
        when(clusterClient.health()).thenReturn(healthResponse);

        when(healthResponse.status()).thenReturn(HealthStatus.Yellow);
        when(healthResponse.clusterName()).thenReturn("ateng-cluster");
        when(healthResponse.numberOfNodes()).thenReturn(1);
        when(healthResponse.numberOfDataNodes()).thenReturn(1);

        SearchHealthIndicator indicator = new SearchHealthIndicator(engineClient, Executors.newSingleThreadExecutor());
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "yellow");
    }

    @Test
    @DisplayName("集群状态 RED 时探针自愈翻转为 DOWN 状态")
    void testHealthRed() throws IOException {
        SearchEngineClient engineClient = mock(SearchEngineClient.class);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient clusterClient = mock(ElasticsearchClusterClient.class);
        HealthResponse healthResponse = mock(HealthResponse.class);

        when(engineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);
        when(engineClient.getElasticsearchClient()).thenReturn(esClient);
        when(esClient.cluster()).thenReturn(clusterClient);
        when(clusterClient.health()).thenReturn(healthResponse);

        when(healthResponse.status()).thenReturn(HealthStatus.Red);
        when(healthResponse.clusterName()).thenReturn("ateng-cluster");

        SearchHealthIndicator indicator = new SearchHealthIndicator(engineClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "red");
    }

    @Test
    @DisplayName("底层通信抛出异常时探针返回 DOWN 状态并记录原因")
    void testHealthException() throws IOException {
        SearchEngineClient engineClient = mock(SearchEngineClient.class);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient clusterClient = mock(ElasticsearchClusterClient.class);

        when(engineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);
        when(engineClient.getElasticsearchClient()).thenReturn(esClient);
        when(esClient.cluster()).thenReturn(clusterClient);
        when(clusterClient.health()).thenThrow(new IOException("Connection refused"));

        SearchHealthIndicator indicator = new SearchHealthIndicator(engineClient);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString()).contains("Connection refused");
    }

    @Test
    @DisplayName("探针执行超过 2 秒硬超时熔断翻转为 DOWN 状态")
    void testHealthTimeout() {
        SearchEngineClient engineClient = mock(SearchEngineClient.class);
        when(engineClient.getEngineType()).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return SearchEngineType.ELASTICSEARCH;
        });

        SearchHealthIndicator indicator = new SearchHealthIndicator(engineClient, Executors.newSingleThreadExecutor());
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString()).contains("timed out after 2 seconds");
    }
}
