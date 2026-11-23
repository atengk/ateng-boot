package io.github.atengk.boot.observability.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.ElasticsearchClusterClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SearchSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("SearchSafeHealthIndicator 搜索引擎安全健康探针单元测试")
class SearchSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 SearchEngineClient 实例时，返回 UNKNOWN 状态")
    void testNoSearchConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        SearchSafeHealthIndicator indicator = new SearchSafeHealthIndicator(
                beanFactory.getBeanProvider(SearchEngineClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的搜索引擎客户端实例");
    }

    @Test
    @DisplayName("集群状态为 GREEN 时，返回 UP 状态")
    void testSearchGreenUp() throws Exception {
        SearchEngineClient mockEngineClient = mock(SearchEngineClient.class);
        when(mockEngineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);

        ElasticsearchClient mockEsClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient mockClusterClient = mock(ElasticsearchClusterClient.class);
        HealthResponse mockResponse = mock(HealthResponse.class);

        when(mockEngineClient.getElasticsearchClient()).thenReturn(mockEsClient);
        when(mockEsClient.cluster()).thenReturn(mockClusterClient);
        when(mockClusterClient.health()).thenReturn(mockResponse);

        when(mockResponse.status()).thenReturn(HealthStatus.Green);
        when(mockResponse.clusterName()).thenReturn("es-cluster-test");
        when(mockResponse.numberOfNodes()).thenReturn(3);
        when(mockResponse.numberOfDataNodes()).thenReturn(3);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("searchEngineClient", mockEngineClient);

        SearchSafeHealthIndicator indicator = new SearchSafeHealthIndicator(
                beanFactory.getBeanProvider(SearchEngineClient.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("engine", "ELASTICSEARCH");
        assertThat(health.getDetails()).containsEntry("clusterName", "es-cluster-test");
        assertThat(health.getDetails()).containsEntry("status", "green");
        assertThat(health.getDetails()).containsEntry("numberOfNodes", 3);
    }

    @Test
    @DisplayName("集群状态为 RED 时，翻转返回 DOWN 状态")
    void testSearchRedDown() throws Exception {
        SearchEngineClient mockEngineClient = mock(SearchEngineClient.class);
        when(mockEngineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);

        ElasticsearchClient mockEsClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient mockClusterClient = mock(ElasticsearchClusterClient.class);
        HealthResponse mockResponse = mock(HealthResponse.class);

        when(mockEngineClient.getElasticsearchClient()).thenReturn(mockEsClient);
        when(mockEsClient.cluster()).thenReturn(mockClusterClient);
        when(mockClusterClient.health()).thenReturn(mockResponse);

        when(mockResponse.status()).thenReturn(HealthStatus.Red);
        when(mockResponse.clusterName()).thenReturn("es-cluster-test");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("searchEngineClient", mockEngineClient);

        SearchSafeHealthIndicator indicator = new SearchSafeHealthIndicator(
                beanFactory.getBeanProvider(SearchEngineClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "red");
        assertThat(health.getDetails()).containsEntry("reason", "Search cluster status is RED");
    }

    @Test
    @DisplayName("Elasticsearch 抛出异常时，返回 DOWN 状态")
    void testSearchExceptionDown() throws Exception {
        SearchEngineClient mockEngineClient = mock(SearchEngineClient.class);
        when(mockEngineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);

        ElasticsearchClient mockEsClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient mockClusterClient = mock(ElasticsearchClusterClient.class);

        when(mockEngineClient.getElasticsearchClient()).thenReturn(mockEsClient);
        when(mockEsClient.cluster()).thenReturn(mockClusterClient);
        when(mockClusterClient.health()).thenThrow(new RuntimeException("ES cluster unreachable"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("searchEngineClient", mockEngineClient);

        SearchSafeHealthIndicator indicator = new SearchSafeHealthIndicator(
                beanFactory.getBeanProvider(SearchEngineClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("探测超时熔断：切断降级为 UNKNOWN 状态")
    void testSearchTimeoutDegradation() throws Exception {
        SearchEngineClient mockEngineClient = mock(SearchEngineClient.class);
        when(mockEngineClient.getEngineType()).thenReturn(SearchEngineType.ELASTICSEARCH);

        ElasticsearchClient mockEsClient = mock(ElasticsearchClient.class);
        ElasticsearchClusterClient mockClusterClient = mock(ElasticsearchClusterClient.class);

        when(mockEngineClient.getElasticsearchClient()).thenReturn(mockEsClient);
        when(mockEsClient.cluster()).thenReturn(mockClusterClient);
        when(mockClusterClient.health()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return mock(HealthResponse.class);
        });

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("searchEngineClient", mockEngineClient);

        SearchSafeHealthIndicator indicator = new SearchSafeHealthIndicator(
                beanFactory.getBeanProvider(SearchEngineClient.class),
                Duration.ofMillis(80),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }
}
