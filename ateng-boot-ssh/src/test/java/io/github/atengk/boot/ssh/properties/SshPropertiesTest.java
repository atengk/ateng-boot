package io.github.atengk.boot.ssh.properties;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SSH 配置属性单元测试")
class SshPropertiesTest {

    @Test
    @DisplayName("测试 SSH 默认配置参数生产就绪不变量")
    void testDefaultProperties() {
        SshProperties properties = new SshProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPort()).isEqualTo(22);
        assertThat(properties.getHostKeyPolicy()).isEqualTo(HostKeyPolicy.LENIENT);
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getAuthTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofSeconds(60));

        // Pool 默认配置
        SshPoolProperties pool = properties.getPool();
        assertThat(pool).isNotNull();
        assertThat(pool.getMaxSessions()).isEqualTo(64);
        assertThat(pool.getIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(pool.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    }
}
