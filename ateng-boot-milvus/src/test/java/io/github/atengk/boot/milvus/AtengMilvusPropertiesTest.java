package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AtengMilvusPropertiesTest {

    @Test
    @DisplayName("测试配置属性默认值")
    void testDefaultValues() {
        AtengMilvusProperties properties = new AtengMilvusProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(19530);
        assertThat(properties.getDatabase()).isEqualTo("default");
        assertThat(properties.isSecure()).isFalse();
        assertThat(properties.getConsistencyLevel()).isEqualTo(MilvusConsistencyLevel.BOUNDED);
        assertThat(properties.isAutoCreatePartition()).isFalse();
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getKeepAliveTime()).isEqualTo(Duration.ofSeconds(55));
        assertThat(properties.getKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofHours(24));

        // 默认解析出的 URI
        assertThat(properties.resolveEffectiveUri()).isEqualTo("http://127.0.0.1:19530");
    }

    @Test
    @DisplayName("测试显式配置 URI 与 TLS 协议推导")
    void testResolveEffectiveUri() {
        AtengMilvusProperties properties = new AtengMilvusProperties();

        // 显式配置完整 URI
        properties.setUri("https://milvus-standalone.internal:19530");
        assertThat(properties.resolveEffectiveUri()).isEqualTo("https://milvus-standalone.internal:19530");

        // 移除显式 URI，基于 host/port/secure 动态推导
        properties.setUri(null);
        properties.setHost("192.168.1.100");
        properties.setPort(19531);
        properties.setSecure(true);
        assertThat(properties.resolveEffectiveUri()).isEqualTo("https://192.168.1.100:19531");
    }
}
