package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import io.github.atengk.boot.ssh.properties.SshProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshServerInfo 目标服务器连接描述符单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshServerInfo 目标服务器描述符单元测试")
class SshServerInfoTest {

    @Test
    @DisplayName("测试使用快捷工厂方法正确构建默认值并计算复合键")
    void testFactoryCreationAndDefaults() {
        SshServerInfo info = SshServerInfo.of("192.168.1.100", "deploy", "secret123");

        assertThat(info.getHost()).isEqualTo("192.168.1.100");
        assertThat(info.getPort()).isEqualTo(22);
        assertThat(info.getUsername()).isEqualTo("deploy");
        assertThat(info.getPassword()).isEqualTo("secret123");
        assertThat(info.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(info.getHostKeyPolicy()).isEqualTo(HostKeyPolicy.LENIENT);
        assertThat(info.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(info.getAuthTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(info.getCommandTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(info.toHostPortKey()).isEqualTo("192.168.1.100:22");
        assertThat(info.toSessionKey()).isEqualTo("deploy@192.168.1.100:22");
        assertThat(info.hasPassword()).isTrue();
        assertThat(info.hasPrivateKey()).isFalse();
        assertThat(info.hasJumpHost()).isFalse();
    }

    @Test
    @DisplayName("测试 Builder 构建自定义端口、跳板机及超时间隔")
    void testBuilderWithCustomSettingsAndJumpHost() {
        SshServerInfo bastion = SshServerInfo.of("bastion.internal", 2222, "jumpuser", "jumppass");

        SshServerInfo target = SshServerInfo.builder()
                .host("10.0.1.50")
                .port(22022)
                .username("appadmin")
                .privateKey("MOCK_PEM_KEY")
                .passphrase("pass123")
                .connectTimeout(Duration.ofSeconds(5))
                .jumpHost(bastion)
                .build();

        assertThat(target.getPort()).isEqualTo(22022);
        assertThat(target.hasPrivateKey()).isTrue();
        assertThat(target.hasPassword()).isFalse();
        assertThat(target.hasJumpHost()).isTrue();
        assertThat(target.getJumpHost()).isSameAs(bastion);
        assertThat(target.toHostPortKey()).isEqualTo("10.0.1.50:22022");
    }

    @Test
    @DisplayName("测试从 SshProperties 转换构建 SshServerInfo")
    void testFromProperties() {
        SshProperties props = new SshProperties();
        props.setHost("prod-db.corp");
        props.setPort(2222);
        props.setUsername("dba");
        props.setPassword("dba-secret");
        props.setHostKeyPolicy(HostKeyPolicy.STRICT);

        SshServerInfo info = SshServerInfo.fromProperties(props);
        assertThat(info).isNotNull();
        assertThat(info.getHost()).isEqualTo("prod-db.corp");
        assertThat(info.getPort()).isEqualTo(2222);
        assertThat(info.getUsername()).isEqualTo("dba");
        assertThat(info.getPassword()).isEqualTo("dba-secret");
        assertThat(info.getHostKeyPolicy()).isEqualTo(HostKeyPolicy.STRICT);

        assertThat(SshServerInfo.fromProperties(null)).isNull();
    }

    @Test
    @DisplayName("测试前置防卫断言：非法主机、非法端口或非法用户名抛出异常")
    void testDefensiveValidation() {
        assertThatThrownBy(() -> SshServerInfo.of("", "root", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must not be blank");

        assertThatThrownBy(() -> SshServerInfo.of("   ", "root", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must not be blank");

        assertThatThrownBy(() -> SshServerInfo.of("127.0.0.1", "", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username must not be blank");

        assertThatThrownBy(() -> SshServerInfo.of("127.0.0.1", 0, "root", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port must be between 1 and 65535");

        assertThatThrownBy(() -> SshServerInfo.of("127.0.0.1", 70000, "root", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port must be between 1 and 65535");
    }

    @Test
    @DisplayName("测试 toString 隐匿脱敏密码与私钥，坚决杜绝机密泄露")
    void testToStringCredentialMasking() {
        SshServerInfo info = SshServerInfo.builder()
                .host("192.168.1.1")
                .username("admin")
                .password("super_secret_password")
                .privateKey("PRIVATE_KEY_PLAINTEXT_SECRET")
                .passphrase("key_passphrase")
                .build();

        String str = info.toString();
        assertThat(str).contains("192.168.1.1");
        assertThat(str).contains("admin");
        assertThat(str).doesNotContain("super_secret_password");
        assertThat(str).doesNotContain("PRIVATE_KEY_PLAINTEXT_SECRET");
        assertThat(str).doesNotContain("key_passphrase");
        assertThat(str).contains("password=******");
        assertThat(str).contains("privateKey=[PROTECTED_PEM]");
        assertThat(str).contains("passphrase=******");
    }
}
