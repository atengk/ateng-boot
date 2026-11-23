package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Milvus 云原生健康探针单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusHealthIndicatorTest {

    private MilvusTemplate mockTemplate;
    private AtengMilvusProperties properties;
    private MilvusHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        mockTemplate = Mockito.mock(MilvusTemplate.class);
        properties = new AtengMilvusProperties();
        when(mockTemplate.getProperties()).thenReturn(properties);
        healthIndicator = new MilvusHealthIndicator(mockTemplate);
    }

    @Test
    @DisplayName("测试连通成功时探针返回 UP 状态并携带详情")
    void testHealthUp() {
        when(mockTemplate.testConnection()).thenReturn(true);
        when(mockTemplate.getServerVersion()).thenReturn("v2.4.4");

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("uri", "http://127.0.0.1:19530");
        assertThat(health.getDetails()).containsEntry("database", "default");
        assertThat(health.getDetails()).containsEntry("version", "v2.4.4");
        assertThat(health.getDetails()).containsEntry("consistencyLevel", "BOUNDED");
    }

    @Test
    @DisplayName("测试连通失败时探针返回 DOWN 状态")
    void testHealthDownWhenNotReady() {
        when(mockTemplate.testConnection()).thenReturn(false);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("uri", "http://127.0.0.1:19530");
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("测试探测发生严重崩溃抛错时安全降级为 DOWN")
    void testHealthDownOnException() {
        when(mockTemplate.testConnection()).thenThrow(new RuntimeException("网络物理中断"));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "java.lang.RuntimeException: 网络物理中断");
    }
}
