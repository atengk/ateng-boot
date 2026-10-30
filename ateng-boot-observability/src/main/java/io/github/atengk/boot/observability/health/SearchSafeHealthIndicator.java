package io.github.atengk.boot.observability.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 搜索引擎（Elasticsearch / OpenSearch）受管安全健康探针
 * <p>
 * 探查搜索引擎集群健康度（GREEN/YELLOW/RED）、活跃节点数与分片状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，
 * 在集群 RED 状态时翻转为 DOWN，在网络超时或假死时触发防假死降级并发布领域事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class SearchSafeHealthIndicator extends AbstractSafeHealthIndicator {

    private final ObjectProvider<SearchEngineClient> searchEngineClientProvider;

    public SearchSafeHealthIndicator(ObjectProvider<SearchEngineClient> searchEngineClientProvider) {
        this(searchEngineClientProvider, (Duration) null, null);
    }

    public SearchSafeHealthIndicator(
            ObjectProvider<SearchEngineClient> searchEngineClientProvider,
            ObservabilityProperties properties) {
        this(searchEngineClientProvider, properties, null);
    }

    public SearchSafeHealthIndicator(
            ObjectProvider<SearchEngineClient> searchEngineClientProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.searchEngineClientProvider = searchEngineClientProvider;
    }

    public SearchSafeHealthIndicator(
            ObjectProvider<SearchEngineClient> searchEngineClientProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.searchEngineClientProvider = searchEngineClientProvider;
    }

    @Override
    protected String getComponentName() {
        return "search";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        SearchEngineClient searchEngineClient = searchEngineClientProvider != null ? searchEngineClientProvider.getIfAvailable() : null;
        if (searchEngineClient == null) {
            builder.unknown().withDetail("message", "未检测到已装配的搜索引擎客户端实例");
            return;
        }

        SearchEngineType engineType = searchEngineClient.getEngineType();
        builder.withDetail("engine", engineType != null ? engineType.name() : "UNKNOWN");

        ElasticsearchClient client = searchEngineClient.getElasticsearchClient();
        if (client == null) {
            builder.down().withDetail("reason", "ElasticsearchClient instance is null");
            return;
        }

        HealthResponse response = client.cluster().health();
        HealthStatus status = response.status();

        builder.withDetail("clusterName", response.clusterName())
                .withDetail("status", status != null ? status.jsonValue() : "UNKNOWN")
                .withDetail("numberOfNodes", response.numberOfNodes())
                .withDetail("numberOfDataNodes", response.numberOfDataNodes())
                .withDetail("activePrimaryShards", response.activePrimaryShards())
                .withDetail("activeShards", response.activeShards())
                .withDetail("relocatingShards", response.relocatingShards())
                .withDetail("initializingShards", response.initializingShards())
                .withDetail("unassignedShards", response.unassignedShards());

        if (status == HealthStatus.Red) {
            builder.down().withDetail("reason", "Search cluster status is RED");
        } else {
            builder.up();
        }
    }
}
