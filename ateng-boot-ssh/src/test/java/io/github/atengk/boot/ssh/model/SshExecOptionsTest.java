package io.github.atengk.boot.ssh.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshExecOptions 扩展选项参数单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshExecOptions 扩展选项参数单元测试")
class SshExecOptionsTest {

    @Test
    @DisplayName("测试默认选项属性与静态工厂方法")
    void testDefaultsAndFactories() {
        SshExecOptions defaults = SshExecOptions.defaults();
        assertThat(defaults.getWorkingDir()).isNull();
        assertThat(defaults.getEnv()).isEmpty();
        assertThat(defaults.getTimeout()).isNull();
        assertThat(defaults.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(defaults.isPty()).isFalse();

        SshExecOptions cwdOpt = SshExecOptions.ofWorkingDir("/var/log");
        assertThat(cwdOpt.getWorkingDir()).isEqualTo("/var/log");

        Charset gbk = Charset.forName("GBK");
        SshExecOptions gbkOpt = SshExecOptions.ofCharset(gbk);
        assertThat(gbkOpt.getCharset()).isEqualTo(gbk);

        SshExecOptions timeoutOpt = SshExecOptions.ofTimeout(Duration.ofSeconds(15));
        assertThat(timeoutOpt.getTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("测试 Builder 构建环境变量与完整选项")
    void testBuilderWithOptions() {
        SshExecOptions options = SshExecOptions.builder()
                .workingDir("/opt/deploy")
                .env(Map.of("ENV", "prod", "ZONE", "sh"))
                .timeout(Duration.ofSeconds(30))
                .charset(StandardCharsets.UTF_8)
                .pty(true)
                .build();

        assertThat(options.getWorkingDir()).isEqualTo("/opt/deploy");
        assertThat(options.getEnv()).containsEntry("ENV", "prod");
        assertThat(options.getEnv()).containsEntry("ZONE", "sh");
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.isPty()).isTrue();
    }
}
