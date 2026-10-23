package io.github.atengk.boot.ssh.model;

import org.apache.sshd.common.channel.PtyMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshShellOptions 终端规格配置对象单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshShellOptions 终端规格配置单元测试")
class SshShellOptionsTest {

    @Test
    @DisplayName("测试生产默认参数配置与预设单例")
    void testDefaults() {
        SshShellOptions options = SshShellOptions.defaults();

        assertThat(options.getTerm()).isEqualTo(SshShellOptions.DEFAULT_TERM);
        assertThat(options.getCols()).isEqualTo(SshShellOptions.DEFAULT_COLS);
        assertThat(options.getRows()).isEqualTo(SshShellOptions.DEFAULT_ROWS);
        assertThat(options.getWidth()).isZero();
        assertThat(options.getHeight()).isZero();
        assertThat(options.getEnv()).isEmpty();
        assertThat(options.getPtyModes()).isEmpty();
        assertThat(options.getTimeout()).isEqualTo(SshShellOptions.DEFAULT_TIMEOUT);
    }

    @Test
    @DisplayName("测试快速构建指定行列尺寸与非法参数防卫")
    void testOfSizeAndValidation() {
        SshShellOptions options = SshShellOptions.ofSize(100, 30);
        assertThat(options.getCols()).isEqualTo(100);
        assertThat(options.getRows()).isEqualTo(30);
        assertThat(options.getTerm()).isEqualTo("xterm-256color");

        // 非法行宽防卫
        assertThatThrownBy(() -> SshShellOptions.ofSize(0, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cols must be greater than 0");

        assertThatThrownBy(() -> SshShellOptions.ofSize(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows must be greater than 0");
    }

    @Test
    @DisplayName("测试快速构建指定终端仿真类型与空白防卫")
    void testOfTerm() {
        SshShellOptions options = SshShellOptions.ofTerm("vt100");
        assertThat(options.getTerm()).isEqualTo("vt100");

        assertThatThrownBy(() -> SshShellOptions.ofTerm(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("term must not be blank");
    }

    @Test
    @DisplayName("测试 Builder 构建自定义终端配置与不可变集合防卫")
    void testBuilderAndImmutability() {
        SshShellOptions options = SshShellOptions.builder()
                .term("xterm")
                .cols(120)
                .rows(40)
                .width(1024)
                .height(768)
                .env(Map.of("LANG", "en_US.UTF-8", "COLORTERM", "truecolor"))
                .ptyModes(Map.of(PtyMode.ECHO, 1))
                .timeout(Duration.ofSeconds(15))
                .build();

        assertThat(options.getTerm()).isEqualTo("xterm");
        assertThat(options.getCols()).isEqualTo(120);
        assertThat(options.getRows()).isEqualTo(40);
        assertThat(options.getWidth()).isEqualTo(1024);
        assertThat(options.getHeight()).isEqualTo(768);
        assertThat(options.getEnv()).containsEntry("LANG", "en_US.UTF-8");
        assertThat(options.getPtyModes()).containsEntry(PtyMode.ECHO, 1);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(15));

        // 防御不可变修改
        Map<String, String> env = options.getEnv();
        assertThatThrownBy(() -> env.put("TEST", "VAL"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("测试 toBuilder 增量复制与保底回退")
    void testToBuilderAndFallback() {
        SshShellOptions base = SshShellOptions.defaults();
        SshShellOptions modified = base.toBuilder()
                .cols(140)
                .build();

        assertThat(modified.getCols()).isEqualTo(140);
        assertThat(modified.getRows()).isEqualTo(24);
        assertThat(modified.getTerm()).isEqualTo("xterm-256color");

        // 构造器容错回退
        SshShellOptions fallback = new SshShellOptions(null, -1, 0, -5, -10, null, null, null);
        assertThat(fallback.getTerm()).isEqualTo("xterm-256color");
        assertThat(fallback.getCols()).isEqualTo(80);
        assertThat(fallback.getRows()).isEqualTo(24);
        assertThat(fallback.getWidth()).isZero();
        assertThat(fallback.getHeight()).isZero();
        assertThat(fallback.getTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
