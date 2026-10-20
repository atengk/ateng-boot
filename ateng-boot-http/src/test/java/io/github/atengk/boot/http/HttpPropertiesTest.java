package io.github.atengk.boot.http;

import io.github.atengk.boot.http.properties.HttpEngineType;
import io.github.atengk.boot.http.properties.HttpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 根配置属性与默认值单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpPropertiesTest {

    @Test
    @DisplayName("验证生产级默认连接池配置参数基线")
    void testDefaultProperties() {
        HttpProperties properties = new HttpProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getEngine()).isEqualTo(HttpEngineType.AUTO);
        assertThat(properties.getPool().getMaxConnections()).isEqualTo(200);
        assertThat(properties.getPool().getMaxPerRoute()).isEqualTo(50);
        assertThat(properties.getPool().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getPool().getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getPool().getConnectionRequestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getPool().getIdleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getPool().getValidateAfterInactivity()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getSsl().isInsecure()).isFalse();
        assertThat(properties.getProxy().isEnabled()).isFalse();
    }
}
