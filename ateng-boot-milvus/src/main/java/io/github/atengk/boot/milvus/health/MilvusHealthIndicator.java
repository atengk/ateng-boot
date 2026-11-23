package io.github.atengk.boot.milvus.health;

import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.util.Assert;

/**
 * Milvus 向量数据库云原生健康检查指示器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
public class MilvusHealthIndicator implements HealthIndicator {

    private final MilvusTemplate milvusTemplate;

    public MilvusHealthIndicator(MilvusTemplate milvusTemplate) {
        Assert.notNull(milvusTemplate, "MilvusTemplate must not be null");
        this.milvusTemplate = milvusTemplate;
    }

    @Override
    public Health health() {
        AtengMilvusProperties properties = milvusTemplate.getProperties();
        String targetUri = properties.resolveEffectiveUri();
        String database = properties.getDatabase();

        try {
            boolean ready = milvusTemplate.testConnection();
            if (ready) {
                String serverVersion = null;
                try {
                    serverVersion = milvusTemplate.getServerVersion();
                } catch (Exception e) {
                    log.debug("获取 Milvus 服务端版本非阻断告警: {}", e.getMessage());
                }

                return Health.up()
                        .withDetail("uri", targetUri)
                        .withDetail("database", database)
                        .withDetail("version", serverVersion != null ? serverVersion : "unknown")
                        .withDetail("consistencyLevel", properties.getConsistencyLevel().name())
                        .build();
            } else {
                return Health.down()
                        .withDetail("uri", targetUri)
                        .withDetail("database", database)
                        .withDetail("error", "Milvus 客户端探针探测未就绪 (clientIsReady == false)")
                        .build();
            }
        } catch (Throwable t) {
            log.warn("Milvus 健康探针探测失败: {}", t.getMessage());
            return Health.down(t)
                    .withDetail("uri", targetUri)
                    .withDetail("database", database)
                    .build();
        }
    }
}
