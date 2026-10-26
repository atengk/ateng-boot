package io.github.atengk.boot.search.actuator;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Boot Actuator 专属搜索引擎健康探针
 * <p>
 * 周期性探查 Elasticsearch / OpenSearch 集群健康度（GREEN/YELLOW/RED）与活跃节点数，
 * 内置 2 秒硬超时熔断机制防御网络假死，并在集群 RED 时自愈翻转为 DOWN 状态。
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
public class SearchHealthIndicator extends AbstractHealthIndicator {

    private static final long TIMEOUT_SECONDS = 2L;

    private final SearchEngineClient searchEngineClient;
    private final Executor executor;

    public SearchHealthIndicator(SearchEngineClient searchEngineClient, Executor executor) {
        this.searchEngineClient = Objects.requireNonNull(searchEngineClient, "searchEngineClient must not be null");
        this.executor = executor;
    }

    public SearchHealthIndicator(SearchEngineClient searchEngineClient) {
        this(searchEngineClient, null);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        CompletableFuture<Void> checkFuture = executor != null
                ? CompletableFuture.runAsync(() -> performCheck(builder), executor)
                : CompletableFuture.runAsync(() -> performCheck(builder));
        try {
            checkFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            checkFuture.cancel(true);
            builder.down(e)
                    .withDetail("error", "Search cluster health check timed out after " + TIMEOUT_SECONDS + " seconds");
        } catch (Exception e) {
            builder.down(e)
                    .withDetail("error", "Search cluster health check failed: " + e.getMessage());
        }
    }

    private void performCheck(Health.Builder builder) {
        SearchEngineType engineType = searchEngineClient.getEngineType();
        builder.withDetail("engine", engineType != null ? engineType.name() : "UNKNOWN");

        try {
            ElasticsearchClient client = searchEngineClient.getElasticsearchClient();
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
                builder.down();
            } else {
                builder.up();
            }
        } catch (Exception e) {
            builder.down()
                    .withDetail("error", "Failed to retrieve cluster health: " + e.getMessage())
                    .withException(e);
        }
    }
}
