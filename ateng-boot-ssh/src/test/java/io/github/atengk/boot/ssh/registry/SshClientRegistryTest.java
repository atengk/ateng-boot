package io.github.atengk.boot.ssh.registry;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshClientRegistry 多服务节点受管注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshClientRegistry 多服务节点受管注册表单元测试")
class SshClientRegistryTest {

    @Test
    @DisplayName("测试注册与按名称检索服务节点")
    void testRegisterAndGetServer() {
        SshClientRegistry registry = new SshClientRegistry();
        SshServerInfo defaultServer = SshServerInfo.of("192.168.1.1", "root", "root123");
        SshServerInfo node1 = SshServerInfo.of("10.0.0.1", "ubuntu", "ubuntu123");

        registry.register(SshClientRegistry.DEFAULT_SERVER, defaultServer);
        registry.register("node1", node1);

        assertThat(registry.contains("default")).isTrue();
        assertThat(registry.contains("node1")).isTrue();
        assertThat(registry.contains("non-existent")).isFalse();

        assertThat(registry.getServer("node1")).isSameAs(node1);
        assertThat(registry.getServer(null)).isSameAs(defaultServer);
        assertThat(registry.getServer("   ")).isSameAs(defaultServer);
        assertThat(registry.getDefaultServer()).isSameAs(defaultServer);
        assertThat(registry.getServerNames()).containsExactlyInAnyOrder("default", "node1");
    }

    @Test
    @DisplayName("测试从 SshProperties 注册节点")
    void testRegisterFromProperties() {
        SshClientRegistry registry = new SshClientRegistry();
        SshProperties props = new SshProperties();
        props.setHost("test-host");
        props.setUsername("test-user");

        registry.register("test-node", props);
        assertThat(registry.contains("test-node")).isTrue();
        assertThat(registry.getServer("test-node").getHost()).isEqualTo("test-host");
    }

    @Test
    @DisplayName("测试查询不存在的节点时抛出 SERVER_NOT_FOUND 异常")
    void testGetNonExistentServer() {
        SshClientRegistry registry = new SshClientRegistry();
        registry.register("node1", SshServerInfo.of("10.0.0.1", "root", "pass"));

        assertThatThrownBy(() -> registry.getServer("missing-node"))
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.SERVER_NOT_FOUND);
                    assertThat(sshEx.getMessage()).contains("missing-node");
                });
    }

    @Test
    @DisplayName("测试空注册表中尝试获取默认节点时抛出异常")
    void testEmptyRegistryDefaultServer() {
        SshClientRegistry registry = new SshClientRegistry();
        assertThatThrownBy(registry::getDefaultServer)
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.SERVER_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("测试清理注册表清空所有节点")
    void testClear() {
        SshClientRegistry registry = new SshClientRegistry();
        registry.register("node1", SshServerInfo.of("10.0.0.1", "root", "pass"));
        assertThat(registry.getServerNames()).isNotEmpty();

        registry.clear();
        assertThat(registry.getServerNames()).isEmpty();
        assertThat(registry.contains("node1")).isFalse();
    }
}
