package io.github.atengk.boot.ssh.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshCommandExecutor 核心调度引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshCommandExecutor 核心执行引擎单元测试")
class SshCommandExecutorTest {

    @Test
    @DisplayName("测试 buildSudoCommand 各种命令模式格式规整")
    void testBuildSudoCommandPatterns() {
        // 1. 普通命令添加前缀
        assertThat(SshCommandExecutor.buildSudoCommand("whoami"))
                .isEqualTo("sudo -S -p '' whoami");
        assertThat(SshCommandExecutor.buildSudoCommand("systemctl restart nginx"))
                .isEqualTo("sudo -S -p '' systemctl restart nginx");

        // 2. 原本携带 sudo 的命令替换为标准非交互式格式
        assertThat(SshCommandExecutor.buildSudoCommand("sudo whoami"))
                .isEqualTo("sudo -S -p '' whoami");
        assertThat(SshCommandExecutor.buildSudoCommand("sudo -u postgres psql"))
                .isEqualTo("sudo -S -p '' -u postgres psql");

        // 3. 已经包含 -S -p '' 的命令幂等保持
        assertThat(SshCommandExecutor.buildSudoCommand("sudo -S -p '' whoami"))
                .isEqualTo("sudo -S -p '' whoami");

        // 4. 首尾空白字符安全清洗
        assertThat(SshCommandExecutor.buildSudoCommand("   cat /etc/shadow   "))
                .isEqualTo("sudo -S -p '' cat /etc/shadow");
        assertThat(SshCommandExecutor.buildSudoCommand("   sudo    systemctl status sshd   "))
                .isEqualTo("sudo -S -p '' systemctl status sshd");

        // 5. 空白命令防御性拦截
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
